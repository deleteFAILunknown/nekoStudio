package libs.libs.libs.adb.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import libs.libs.libs.adb.session.AdbConnection
import libs.libs.libs.adb.session.AdbStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

public class AdbSyncService(public val connection: AdbConnection) {

    public suspend fun push(
        inputStream: InputStream,
        remotePath: String,
        mode: Int = 0x1A4,
        chunkSize: Int = 256 * 1024
    ): Boolean = withContext(Dispatchers.IO) {
        val stream = connection.openStream("sync:")
        val targetStr = "$remotePath,$mode"
        
        sendSyncReq(stream, "SEND", targetStr.toByteArray(Charsets.UTF_8))

        // 内存复用：只分配一次固定大小的 Buffer
        val buffer = ByteArray(chunkSize)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            val chunkHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .put("DATA".toByteArray(Charsets.UTF_8))
                .putInt(bytesRead)
                .array()
        
            // 写入 8 字节 DATA Header 与 256KB 数据块
            stream.write(chunkHeader)
            if (bytesRead == chunkSize) {
                stream.write(buffer)
            } else {
                stream.write(buffer.copyOf(bytesRead))
            }
        }

        val timestamp = (System.currentTimeMillis() / 1000).toInt()
        val doneHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put("DONE".toByteArray(Charsets.UTF_8))
            .putInt(timestamp)
            .array()
        stream.write(doneHeader)

        val response = stream.responseFlow.first()
        stream.close()

        if (response.size >= 4) {
            val status = String(response, 0, 4, Charsets.UTF_8)
            return@withContext status == "OKAY"
        }
        false
    }

    public suspend fun pull(
        remotePath: String,
        outputStream: OutputStream
    ): Boolean = withContext(Dispatchers.IO) {
        val stream = connection.openStream("sync:")
        
        sendSyncReq(stream, "RECV", remotePath.toByteArray(Charsets.UTF_8))

        var success = false
        stream.responseFlow.collect { chunk ->
            if (chunk.size >= 8) {
                val id = String(chunk, 0, 4, Charsets.UTF_8)
                val length = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (id == "DATA" && chunk.size >= 8 + length) {
                    outputStream.write(chunk, 8, length)
                } else if (id == "DONE") {
                    success = true
                }
            }
        }
        stream.close()
        success
    }

    public suspend fun sendSyncReq(stream: AdbStream, id: String, reqPayload: ByteArray) {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByteArray(Charsets.UTF_8))
            .putInt(reqPayload.size)
            .array()
        stream.write(header)
        if (reqPayload.isNotEmpty()) {
            stream.write(reqPayload)
        }
    }
}
