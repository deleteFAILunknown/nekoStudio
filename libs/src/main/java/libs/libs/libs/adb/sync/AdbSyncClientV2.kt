package libs.libs.libs.adb.sync

import libs.libs.libs.adb.connect.AdbConnection
import libs.libs.libs.adb.connect.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

public class AdbSyncClientV2(
    private val connection: AdbConnection
) {
    public val supportsStatV2: Boolean get() = connection.hasFeature("stat_v2")
    public val supportsLsV2: Boolean get() = connection.hasFeature("ls_v2")
    public val supportsSendV2: Boolean get() = connection.hasFeature("send_v2")
    public val supportsRecvV2: Boolean get() = connection.hasFeature("recv_v2")

    private suspend fun openSyncStream(): AdbStream {
        return connection.openStream("sync:")
            ?: throw IllegalStateException("Failed to open ADB sync: service")
    }

    private suspend fun readExactBytes(stream: AdbStream, length: Int): ByteArray {
        val buffer = ByteArrayOutputStream(length)
        var remaining = length
        while (remaining > 0) {
            val chunk = stream.read() ?: break
            if (chunk.isNotEmpty()) {
                val toWrite = minOf(chunk.size, remaining)
                buffer.write(chunk, 0, toWrite)
                remaining -= toWrite
            }
        }
        check(buffer.size() == length) { "Unexpected EOF: expected $length bytes, got ${buffer.size()}" }
        return buffer.toByteArray()
    }

    /**
     * 推送文件到远程设备 (PUSH)
     */
    public suspend fun push(
        inputStream: InputStream,
        remotePath: String,
        totalSize: Long,
        onProgress: ((written: Long, total: Long) -> Unit)? = null,
        mode: Int = 0x1B4 // 0664 (rw-r--r--)
    ): Unit = withContext(Dispatchers.IO) {
        val stream = openSyncStream()
        try {
            // 格式: SEND<path_length>,<path>,<mode>
            val pathWithMode = "$remotePath,$mode"
            val pathBytes = pathWithMode.toByteArray(Charsets.UTF_8)
            stream.write(SyncCommand.createHeader(SyncCommand.ID_SEND, pathBytes.size) + pathBytes)

            val buffer = ByteArray(64 * 1024) // 64KB 块
            var totalWritten = 0L

            while (true) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead <= 0) break

                // 发送 DATA<length> 报文
                stream.write(SyncCommand.createHeader(SyncCommand.ID_DATA, bytesRead))
                stream.write(buffer.copyOf(bytesRead))

                totalWritten += bytesRead
                onProgress?.invoke(totalWritten, totalSize)
            }

            // 发送 DONE 标示结束，附带 mtime (当前时间戳)
            val mtime = (System.currentTimeMillis() / 1000).toInt()
            stream.write(SyncCommand.createHeader(SyncCommand.ID_DONE, mtime))

            // 读取响应 OKAY 或 FAIL
            val respHeader = readExactBytes(stream, SyncCommand.HEADER_SIZE)
            val (id, msgLen) = SyncCommand.parseHeader(respHeader)

            if (id == SyncCommand.ID_FAIL) {
                val failMsg = String(readExactBytes(stream, msgLen), Charsets.UTF_8)
                throw IllegalStateException("Push failed: $failMsg")
            }
            check(id == SyncCommand.ID_OKAY) { "Unexpected push response ID: $id" }
        } finally {
            stream.close()
        }
    }

    /**
     * 从远程设备拉取文件 (PULL)
     */
    public suspend fun pull(
        remotePath: String,
        outputStream: OutputStream,
        onProgress: ((read: Long, total: Long) -> Unit)? = null
    ): Unit = withContext(Dispatchers.IO) {
        val stream = openSyncStream()
        try {
            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            stream.write(SyncCommand.createHeader(SyncCommand.ID_RECV, pathBytes.size) + pathBytes)

            var totalRead = 0L

            while (true) {
                val respHeader = readExactBytes(stream, SyncCommand.HEADER_SIZE)
                val (id, len) = SyncCommand.parseHeader(respHeader)

                when (id) {
                    SyncCommand.ID_DATA -> {
                        val data = readExactBytes(stream, len)
                        outputStream.write(data)
                        totalRead += len
                        onProgress?.invoke(totalRead, -1)
                    }
                    SyncCommand.ID_DONE -> break
                    SyncCommand.ID_FAIL -> {
                        val failMsg = String(readExactBytes(stream, len), Charsets.UTF_8)
                        throw IllegalStateException("Pull failed: $failMsg")
                    }
                    else -> throw IllegalStateException("Unexpected response tag during pull: $id")
                }
            }
            outputStream.flush()
        } finally {
            stream.close()
        }
    }

    /**
     * 智能 Stat：优先使用 STA2 (v2)，不支持则降级为 STAT (v1)
     */
    public suspend fun stat(remotePath: String): FileStatV2 = withContext(Dispatchers.IO) {
        if (supportsStatV2) {
            statV2(remotePath)
        } else {
            val v1 = statV1(remotePath)
            FileStatV2(
                path = remotePath,
                error = if (v1.exists) 0 else 2,
                dev = 0, ino = 0, mode = v1.mode, nlink = 1,
                uid = 0, gid = 0, size = v1.size.toLong(),
                atime = v1.mtime, mtime = v1.mtime, ctime = v1.mtime
            )
        }
    }

    private suspend fun statV2(remotePath: String): FileStatV2 {
        val stream = openSyncStream()
        try {
            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            stream.write(SyncCommand.createHeader(SyncCommandV2.ID_STA2, pathBytes.size) + pathBytes)

            val respHeader = readExactBytes(stream, SyncCommand.HEADER_SIZE)
            val (id, _) = SyncCommand.parseHeader(respHeader)

            check(id == SyncCommandV2.ID_STA2 || id == SyncCommandV2.ID_LSTA) { "Unexpected STA2 response tag: $id" }

            val payload = readExactBytes(stream, 68)
            return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).parseSyncStatV2(remotePath)
        } finally {
            stream.close()
        }
    }

    private suspend fun statV1(remotePath: String): FileStat {
        val stream = openSyncStream()
        try {
            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            stream.write(SyncCommand.createHeader(SyncCommand.ID_STAT, pathBytes.size) + pathBytes)

            val respHeader = readExactBytes(stream, SyncCommand.HEADER_SIZE)
            val (id, _) = SyncCommand.parseHeader(respHeader)
            check(id == SyncCommand.ID_STAT) { "Unexpected STAT response: $id" }

            val statBytes = readExactBytes(stream, 12)
            val buf = ByteBuffer.wrap(statBytes).order(ByteOrder.LITTLE_ENDIAN)
            return FileStat(remotePath, buf.int, buf.int, buf.int.toLong() and 0xFFFFFFFFL)
        } finally {
            stream.close()
        }
    }

    /**
     * 执行 Sync v2 (LST2) 目录枚举
     */
    public suspend fun listV2(remotePath: String): List<FileStatV2> = withContext(Dispatchers.IO) {
        check(supportsLsV2) { "Device does not support ls_v2 feature" }
        val stream = openSyncStream()
        val entries = mutableListOf<FileStatV2>()

        try {
            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            stream.write(SyncCommand.createHeader(SyncCommandV2.ID_LST2, pathBytes.size) + pathBytes)

            while (true) {
                val headerBytes = readExactBytes(stream, SyncCommand.HEADER_SIZE)
                val (id, nameLen) = SyncCommand.parseHeader(headerBytes)

                when (id) {
                    SyncCommandV2.ID_DNT2 -> {
                        val statBytes = readExactBytes(stream, 68)
                        val nameBytes = readExactBytes(stream, nameLen)
                        val fileName = String(nameBytes, Charsets.UTF_8)

                        if (fileName != "." && fileName != "..") {
                            val fileStat = ByteBuffer.wrap(statBytes).order(ByteOrder.LITTLE_ENDIAN).parseSyncStatV2("$remotePath/$fileName")
                            entries.add(fileStat)
                        }
                    }
                    SyncCommand.ID_DONE -> break
                    else -> throw IllegalStateException("Unexpected LST2 response tag: $id")
                }
            }
        } finally {
            stream.close()
        }

        entries
    }
}
