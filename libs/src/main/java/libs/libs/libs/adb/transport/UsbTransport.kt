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
        while (totalWritten < length) {
            val chunkSize = minOf(4096, length - totalWritten)
            val chunk = buffer.copyOfRange(offset + totalWritten, offset + totalWritten + chunkSize)
            val written = connection.bulkTransfer(outEndpoint, chunk, chunkSize, timeoutMs)
            if (written < 0) throw IllegalStateException("USB 写入数据失败")
            totalWritten += written
        }
    }

    override suspend fun close(): Unit = withContext(Dispatchers.IO) {
        runCatching { connection.close() }
    }
}
