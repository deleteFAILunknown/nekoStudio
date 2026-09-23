package libs.libs.libs.adb.sync

import libs.libs.libs.adb.connect.AdbConnection
import libs.libs.libs.adb.connect.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

public class AdbSyncClientV2(
    private val connection: AdbConnection
) {
    // 检查设备是否支持 Sync v2 特性
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

    /**
     * 执行 Sync v2 (STA2) 查询
     */
    private suspend fun statV2(remotePath: String): FileStatV2 {
        val stream = openSyncStream()
        try {
            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            // STA2 请求头部包含路径长度
            stream.write(SyncCommand.createHeader(SyncCommandV2.ID_STA2, pathBytes.size) + pathBytes)

            val respHeader = readExactBytes(stream, SyncCommand.HEADER_SIZE)
            val (id, _) = SyncCommand.parseHeader(respHeader)

            check(id == SyncCommandV2.ID_STA2 || id == SyncCommandV2.ID_LSTA) { "Unexpected STA2 response tag: $id" }

            // STA2 返回固定 68 字节 Payload
            val payload = readExactBytes(stream, 68)
            return ByteBuffer.wrap(payload).parseSyncStatV2(remotePath)
        } finally {
            stream.close()
        }
    }

    /**
     * 执行 Sync v1 (STAT) 回退查询
     */
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
                        // DNT2 Payload: 68 字节 Stat + nameLen 字节文件名
                        val statBytes = readExactBytes(stream, 68)
                        val nameBytes = readExactBytes(stream, nameLen)
                        val fileName = String(nameBytes, Charsets.UTF_8)

                        if (fileName != "." && fileName != "..") {
                            val fileStat = ByteBuffer.wrap(statBytes).parseSyncStatV2("$remotePath/$fileName")
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
