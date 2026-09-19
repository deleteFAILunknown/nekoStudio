package libs.libs.libs.adb.sync

import libs.libs.libs.adb.session.AdbConnection
import libs.libs.libs.adb.session.AdbStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

public data class AdbFileEntry(
    public val name: String,
    public val mode: Int,
    public val size: Long,
    public val mtime: Long,
    public val error: Int = 0
) {
    public val isDirectory: Boolean get() = (mode and 0x4000) != 0 // S_IFDIR
    public val isFile: Boolean get() = (mode and 0x8000) != 0     // S_IFREG
    public val isSymbolicLink: Boolean get() = (mode and 0xa000) != 0 // S_IFLNK
}

public class AdbSyncClient(
    private val stream: AdbStream,
    private val features: Set<String>
) : AutoCloseable {

    public val hasStatV2: Boolean = "stat_v2" in features
    public val hasLsV2: Boolean = "ls_v2" in features
    public val hasSendRecvV2: Boolean = "sendrecv_v2" in features
    public val hasFixedPushMkdir: Boolean = "fixed_push_mkdir" in features

    // sendrecv_v2 模式下使用 256KB 大缓冲区提升传输吞吐量，普通模式保持 64KB
    private val bufferSize: Int = if (hasSendRecvV2) 256 * 1024 else 64 * 1024

    companion object {
        private fun id(s: String): Int {
            return (s[0].code) or (s[1].code shl 8) or (s[2].code shl 16) or (s[3].code shl 24)
        }

        // Sync 协议标识符 (Little-Endian 4-byte Int)
        private val ID_LSTAT_V2 = id("LST2")
        private val ID_STAT_V2 = id("STA2")
        private val ID_LIST_V2 = id("LST2")
        private val ID_DENT_V2 = id("DNT2")

        private val ID_STAT = id("STAT")
        private val ID_LIST = id("LIST")
        private val ID_DENT = id("DENT")
        private val ID_SEND = id("SEND")
        private val ID_RECV = id("RECV")
        private val ID_DATA = id("DATA")
        private val ID_DONE = id("DONE")
        private val ID_OKAY = id("OKAY")
        private val ID_FAIL = id("FAIL")

        public suspend fun open(connection: AdbConnection): AdbSyncClient {
            val stream = connection.openStream("sync:")
            val featureSet = connection.features.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            return AdbSyncClient(stream, featureSet)
        }
    }

    /**
     * 查询文件/目录状态 (优先使用 STAT_V2 64 位解析，失败降级到 V1)
     */
    public suspend fun stat(remotePath: String): AdbFileEntry {
        return if (hasStatV2) {
            statV2(remotePath)
        } else {
            statV1(remotePath)
        }
    }

    private suspend fun statV2(remotePath: String): AdbFileEntry {
        val pathBytes = remotePath.toByteArray(StandardCharsets.UTF_8)
        val req = ByteBuffer.allocate(8 + pathBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        req.putInt(ID_STAT_V2)
        req.putInt(pathBytes.size)
        req.put(pathBytes)
        stream.write(req.array())

        val header = readExactly(4) ?: return emptyEntry(remotePath)
        val resId = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int

        if (resId == ID_STAT_V2 || resId == ID_LSTAT_V2) {
            // STAT_V2 结构体固定 72 字节
            val payload = readExactly(72) ?: return emptyEntry(remotePath)
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val error = buf.int
            buf.long // dev
            buf.long // ino
            val mode = buf.int
            buf.int  // nlink
            buf.int  // uid
            buf.int  // gid
            val size = buf.long  // 64-bit uint64，突破 4GB 限制
            buf.long // atime
            val mtime = buf.long // 64-bit timestamp
            buf.long // ctime
            return AdbFileEntry(
                name = remotePath.substringAfterLast('/'),
                mode = mode,
                size = size,
                mtime = mtime,
                error = error
            )
        } else {
            return statV1(remotePath)
        }
    }

    private suspend fun statV1(remotePath: String): AdbFileEntry {
        val pathBytes = remotePath.toByteArray(StandardCharsets.UTF_8)
        val req = ByteBuffer.allocate(8 + pathBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        req.putInt(ID_STAT)
        req.putInt(pathBytes.size)
        req.put(pathBytes)
        stream.write(req.array())

        val resp = readExactly(16) ?: return emptyEntry(remotePath)
        val buf = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN)
        val id = buf.int
        if (id != ID_STAT) return emptyEntry(remotePath)

        val mode = buf.int
        val size = buf.int.toLong() and 0xFFFFFFFFL
        val mtime = buf.int.toLong() and 0xFFFFFFFFL

        return AdbFileEntry(
            name = remotePath.substringAfterLast('/'),
            mode = mode,
            size = size,
            mtime = mtime
        )
    }

    /**
     * 获取远程目录列表 (支持 DENT_V2 及 V1 降级)
     */
    public suspend fun list(remotePath: String): List<AdbFileEntry> {
        return if (hasLsV2) {
            listV2(remotePath)
        } else {
            listV1(remotePath)
        }
    }

    private suspend fun listV2(remotePath: String): List<AdbFileEntry> {
        val pathBytes = remotePath.toByteArray(StandardCharsets.UTF_8)
        val req = ByteBuffer.allocate(8 + pathBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        req.putInt(ID_LIST_V2)
        req.putInt(pathBytes.size)
        req.put(pathBytes)
        stream.write(req.array())

        val list = mutableListOf<AdbFileEntry>()
        while (true) {
            val header = readExactly(4) ?: break
            val id = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == ID_DONE) break
            if (id != ID_DENT_V2) break

            // DENT_V2 帧头固定 76 字节 (含 namelen)
            val payload = readExactly(76) ?: break
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val error = buf.int
            buf.long // dev
            buf.long // ino
            val mode = buf.int
            buf.int  // nlink
            buf.int  // uid
            buf.int  // gid
            val size = buf.long
            buf.long // atime
            val mtime = buf.long
            buf.long // ctime
            val namelen = buf.int

            val nameBytes = readExactly(namelen) ?: break
            val name = String(nameBytes, StandardCharsets.UTF_8)

            list.add(AdbFileEntry(name = name, mode = mode, size = size, mtime = mtime, error = error))
        }
        return list
    }

    private suspend fun listV1(remotePath: String): List<AdbFileEntry> {
        val pathBytes = remotePath.toByteArray(StandardCharsets.UTF_8)
        val req = ByteBuffer.allocate(8 + pathBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        req.putInt(ID_LIST)
        req.putInt(pathBytes.size)
        req.put(pathBytes)
        stream.write(req.array())

        val list = mutableListOf<AdbFileEntry>()
        while (true) {
            val header = readExactly(16) ?: break
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val id = buf.int
            if (id == ID_DONE) break
            if (id != ID_DENT) break

            val mode = buf.int
            val size = buf.int.toLong() and 0xFFFFFFFFL
            val mtime = buf.int.toLong() and 0xFFFFFFFFL
            val namelen = buf.int

            val nameBytes = readExactly(namelen) ?: break
            val name = String(nameBytes, StandardCharsets.UTF_8)

            list.add(AdbFileEntry(name = name, mode = mode, size = size, mtime = mtime))
        }
        return list
    }

    /**
     * 推送文件 (Push)
     */
    public suspend fun push(
        localFile: File,
        remotePath: String,
        mode: Int = 33188,
        progress: ((transferred: Long, total: Long) -> Unit)? = null
    ) {
        localFile.inputStream().use { input ->
            pushStream(input, remotePath, mode, localFile.length(), localFile.lastModified() / 1000, progress)
        }
    }

    public suspend fun pushStream(
        inputStream: InputStream,
        remotePath: String,
        mode: Int = 33188,
        totalSize: Long = -1L,
        mtime: Long = System.currentTimeMillis() / 1000,
        progress: ((transferred: Long, total: Long) -> Unit)? = null
    ) {
        val destination = "$remotePath,$mode"
        val destBytes = destination.toByteArray(StandardCharsets.UTF_8)

        val req = ByteBuffer.allocate(8 + destBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        req.putInt(ID_SEND)
        req.putInt(destBytes.size)
        req.put(destBytes)
        stream.write(req.array())

        val buf = ByteArray(bufferSize)
        var transferred = 0L
        var read: Int

        while (inputStream.read(buf).also { read = it } != -1) {
            val chunkHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            chunkHeader.putInt(ID_DATA)
            chunkHeader.putInt(read)
            stream.write(chunkHeader.array())

            if (read == buf.size) {
                stream.write(buf)
            } else {
                stream.write(buf.copyOf(read))
            }

            transferred += read
            progress?.invoke(transferred, totalSize)
        }

        val doneHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        doneHeader.putInt(ID_DONE)
        doneHeader.putInt(mtime.toInt())
        stream.write(doneHeader.array())

        val responseHeader = readExactly(8) ?: throw IllegalStateException("Push 失败: 服务器断开连接")
        val respBuf = ByteBuffer.wrap(responseHeader).order(ByteOrder.LITTLE_ENDIAN)
        val respId = respBuf.int
        val respLen = respBuf.int

        if (respId == ID_FAIL) {
            val errMsgBytes = readExactly(respLen) ?: ByteArray(0)
            val errMsg = String(errMsgBytes, StandardCharsets.UTF_8)
            throw IllegalStateException("Push 失败: $errMsg")
        } else if (respId != ID_OKAY) {
            throw IllegalStateException("Push 失败: 收到异常响应 ID $respId")
        }
    }

    /**
     * 拉取文件 (Pull)
     */
    public suspend fun pull(
        remotePath: String,
        outputStream: OutputStream,
        progress: ((transferred: Long, total: Long) -> Unit)? = null
    ) {
        val remoteBytes = remotePath.toByteArray(StandardCharsets.UTF_8)
        val req = ByteBuffer.allocate(8 + remoteBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        req.putInt(ID_RECV)
        req.putInt(remoteBytes.size)
        req.put(remoteBytes)
        stream.write(req.array())

        var transferred = 0L

        while (true) {
            val header = readExactly(8) ?: throw IllegalStateException("Pull 异常中断")
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val id = buf.int
            val len = buf.int

            when (id) {
                ID_DATA -> {
                    val chunk = readExactly(len) ?: throw IllegalStateException("Pull 读取 DATA 数据块失败")
                    outputStream.write(chunk)
                    transferred += len
                    progress?.invoke(transferred, -1L)
                }
                ID_DONE -> break
                ID_FAIL -> {
                    val msgBytes = readExactly(len) ?: ByteArray(0)
                    val msg = String(msgBytes, StandardCharsets.UTF_8)
                    throw IllegalStateException("Pull 失败: $msg")
                }
                else -> throw IllegalStateException("Pull 收到未知类型标识: $id")
            }
        }
        outputStream.flush()
    }

    public suspend fun pull(
        remotePath: String,
        localFile: File,
        progress: ((transferred: Long, total: Long) -> Unit)? = null
    ) {
        localFile.outputStream().use { output ->
            pull(remotePath, output, progress)
        }
    }

    override fun close() {
        stream.close()
    }

    /**
     * 安全无阻塞精准读取指定长度字节流（解决 ADB Socket 拆包/分包问题）
     */
    private suspend fun readExactly(size: Int): ByteArray? {
        val result = ByteArray(size)
        var readSoFar = 0
        while (readSoFar < size) {
            val chunk = stream.read() ?: return null
            if (chunk.isEmpty()) continue
            val toCopy = minOf(chunk.size, size - readSoFar)
            System.arraycopy(chunk, 0, result, readSoFar, toCopy)
            readSoFar += toCopy
        }
        return result
    }

    private fun emptyEntry(path: String) = AdbFileEntry(path.substringAfterLast('/'), 0, 0, 0, -1)
}
