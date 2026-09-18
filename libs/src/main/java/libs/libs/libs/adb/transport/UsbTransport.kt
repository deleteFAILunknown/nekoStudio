package libs.libs.libs.adb.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public class UsbTransport(
    public val connection: UsbDeviceConnection,
    public val inEndpoint: UsbEndpoint,
    public val outEndpoint: UsbEndpoint,
    public val timeoutMs: Int = 5000
) : AdbTransport {

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = withContext(Dispatchers.IO) {
        var totalRead = 0
        val tempBuf = ByteArray(length)
        while (totalRead < length) {
            val bytesRead = connection.bulkTransfer(inEndpoint, tempBuf, length - totalRead, timeoutMs)
            if (bytesRead < 0) break
            System.arraycopy(tempBuf, 0, buffer, offset + totalRead, bytesRead)
            totalRead += bytesRead
        }
        totalRead
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int): Unit = withContext(Dispatchers.IO) {
        var totalWritten = 0
    // 将分块从 4KB 提升至 64KB（甚至 256KB），大幅减少 JNI 调用开销
        val maxChunkSize = 64 * 1024 
        while (totalWritten < length) {
            val chunkSize = minOf(maxChunkSize, length - totalWritten)
            val written = connection.bulkTransfer(outEndpoint, buffer, offset + totalWritten, chunkSize, timeoutMs)
            if (written < 0) throw IllegalStateException("USB 写入失败")
            totalWritten += written
        }
    }

    override suspend fun close(): Unit = withContext(Dispatchers.IO) {
        runCatching { connection.close() }
    }
}
