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

public class AdbSyncClient(
    @PublishedApi internal val connection: AdbConnection
) {

    /**
     * 打开底层的 sync: 服务流
     */
    private suspend fun openSyncStream(): AdbStream {
        return connection.openStream("sync:")
            ?: throw IllegalStateException("Failed to open ADB sync: service")
    }

    /**
     * 从 Sync 流中读取刚好 [length] 长度的字节，自动处理 TCP 分片拆包
     */
    private suspend fun readExactBytes(stream: AdbStream, length: Int): ByteArray {
        val buffer = ByteArrayOutputStream(length)
        var remaining = length

        while (remaining > 0) {
            val chunk = stream.read() ?: break
            if (chunk.isNotEmpty()) {
                val toWrite = minOf(chunk.size, remaining)
                buffer.write(chunk, 0, toWrite)
                remaining -= toWrite

                // 如果多读出了字节（不属于本次 payload），需注意 ADB 内部是包对包机制
                // 通常 Sync 传输严格按 Header 指定的 length 响应
            }
        }

        check(buffer.size() == length) { "Unexpected EOF: expected $length bytes, got ${buffer.size()}" }
        return buffer.toByteArray()
    }

    /**
     * 查询远程文件/目录的状态 (STAT)
     */
    public suspend fun stat(remotePath: String): FileStat = withContext(Dispatchers.IO) {
        val stream = openSyncStream()
        try {
            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            val reqHeader = SyncCommand.createHeader(SyncCommand.ID_STAT, pathBytes.size)

            stream.write(reqHeader + pathBytes)

            val respHeaderBytes = readExactBytes(stream, SyncCommand.HEADER_SIZE)
            val (id, _) = SyncCommand.parseHeader(respHeaderBytes)

            check(id == SyncCommand.ID_STAT) { "Unexpected STAT response: $id" }

            // STAT 响应体包含 12 字节: [4 bytes mode][4 bytes size][4 bytes mtime]
            val statBytes = readExactBytes(stream, 12)
            val buf = ByteBuffer.wrap(statBytes).order(ByteOrder.LITTLE_ENDIAN)

            val mode = buf.int
            val size = buf.int.toLong() and 0xFFFFFFFFL
            val mtime = buf.int.toLong() and 0xFFFFFFFFL

            FileStat(remotePath, mode, size, mtime)
        } finally {
            stream.close()
        }
    }

    /**
     * 枚举远程目录下的所有文件 (LIST)
     */
    public suspend fun list(remotePath: String): List<DirectoryEntry> = withContext(Dispatchers.IO) {
        val stream = openSyncStream()
        val entries = mutableListOf<DirectoryEntry>()

        try {
            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            stream.write(SyncCommand.createHeader(SyncCommand.ID_LIST, pathBytes.size) + pathBytes)

            while (true) {
                val headerBytes = readExactBytes(stream, SyncCommand.HEADER_SIZE)
                val (id, value) = SyncCommand.parseHeader(headerBytes)

                when (id) {
                    SyncCommand.ID_DENT -> {
                        // DENT 结构: [4 bytes mode][4 bytes size][4 bytes mtime][4 bytes name_len] + [name_len bytes name]
                        val dentBytes = readExactBytes(stream, 16)
                        val buf = ByteBuffer.wrap(dentBytes).order(ByteOrder.LITTLE_ENDIAN)

                        val mode = buf.int
                        val size = buf.int.toLong() and 0xFFFFFFFFL
                        val mtime = buf.int.toLong() and 0xFFFFFFFFL
                        val nameLen = buf.int

                        val nameBytes = readExactBytes(stream, nameLen)
                        val name = String(nameBytes, Charsets.UTF_8)

                        if (name != "." && name != "..") {
                            entries.add(DirectoryEntry(name, mode, size, mtime))
                        }
                    }
                    SyncCommand.ID_DONE -> break
                    else -> throw IllegalStateException("Unexpected LIST response tag: $id")
                }
            }
        } finally {
            stream.close()
        }

        entries
    }

    /**
     * 推送文件到被控设备 (PUSH / SEND)
     *
     * @param inputStream 要上传的文件输入流
     * @param remotePath 目标路径 (如 "/sdcard/test.txt")
     * @param totalSize 文件总大小（用于进度计算，未知可传 -1）
     * @param mode 文件权限，默认 0644 普通文件
     * @param mtime 修改时间戳 (秒)
     * @param onProgress 传输进度回调 (writtenBytes, totalBytes)
     */
    public suspend fun push(
        inputStream: InputStream,
        remotePath: String,
        totalSize: Long = -1L,
        mode: Int = FilePermissions.DEFAULT_MODE,
        mtime: Long = System.currentTimeMillis() / 1000,
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val stream = openSyncStream()
            try {
                // 1. 发送 SEND 请求，Payload 格式为: "remote_path,mode"
                val destinationStr = "$remotePath,$mode"
                val destBytes = destinationStr.toByteArray(Charsets.UTF_8)
                stream.write(SyncCommand.createHeader(SyncCommand.ID_SEND, destBytes.size) + destBytes)

                // 2. 循环发送 DATA 数据块 (推荐每块 64KB)
                val buffer = ByteArray(64 * 1024)
                var bytesWritten = 0L
                var read: Int

                while (inputStream.read(buffer).also { read = it } != -1) {
                    if (read > 0) {
                        val dataHeader = SyncCommand.createHeader(SyncCommand.ID_DATA, read)
                        val payload = if (read == buffer.size) buffer else buffer.copyOf(read)
                        
                        stream.write(dataHeader + payload)
                        bytesWritten += read
                        onProgress?.invoke(bytesWritten, totalSize)
                    }
                }

                // 3. 发送 DONE 标识完成，传输修改时间戳 mtime
                val doneHeader = SyncCommand.createHeader(SyncCommand.ID_DONE, mtime.toInt())
                stream.write(doneHeader)

                // 4. 读取最终结果 (OKAY 或 FAIL)
                val respHeaderBytes = readExactBytes(stream, SyncCommand.HEADER_SIZE)
                val (id, len) = SyncCommand.parseHeader(respHeaderBytes)

                if (id == SyncCommand.ID_FAIL) {
                    val errorMsg = String(readExactBytes(stream, len), Charsets.UTF_8)
                    throw IllegalStateException("Push failed: $errorMsg")
                }

                check(id == SyncCommand.ID_OKAY) { "Unexpected push response: $id" }
            } finally {
                stream.close()
            }
        }
    }

    /**
     * 从被控设备拉取文件到本地 (PULL / RECV)
     *
     * @param remotePath 被控端文件路径
     * @param outputStream 本地输出流
     * @param onProgress 传输进度回调 (readBytes, totalBytes)
     */
    public suspend fun pull(
        remotePath: String,
        outputStream: OutputStream,
        onProgress: ((read: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val stream = openSyncStream()
            try {
                // 1. 先查询文件大小以便精准回调进度
                val fileStat = stat(remotePath)
                check(fileStat.exists) { "Remote file does not exist: $remotePath" }

                // 2. 发送 RECV 请求
                val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
                stream.write(SyncCommand.createHeader(SyncCommand.ID_RECV, pathBytes.size) + pathBytes)

                var bytesRead = 0L

                // 3. 循环接收 DATA 帧直到 DONE
                while (true) {
                    val headerBytes = readExactBytes(stream, SyncCommand.HEADER_SIZE)
                    val (id, len) = SyncCommand.parseHeader(headerBytes)

                    when (id) {
                        SyncCommand.ID_DATA -> {
                            val chunk = readExactBytes(stream, len)
                            outputStream.write(chunk)
                            bytesRead += len
                            onProgress?.invoke(bytesRead, fileStat.size)
                        }
                        SyncCommand.ID_DONE -> break
                        SyncCommand.ID_FAIL -> {
                            val errorMsg = String(readExactBytes(stream, len), Charsets.UTF_8)
                            throw IllegalStateException("Pull failed: $errorMsg")
                        }
                        else -> throw IllegalStateException("Unexpected pull response tag: $id")
                    }
                }
            } finally {
                stream.close()
            }
        }
    }
}
