package libs.libs.libs.adb.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libs.libs.libs.adb.protocol.AdbCrypto
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket

public class TlsTransport(
    public val host: String,
    public val port: Int,
    public val crypto: AdbCrypto,
    public val timeoutMs: Int = 10000
) : AdbTransport {

    public var sslSocket: SSLSocket? = null
    public var inputStream: InputStream? = null
    public var outputStream: OutputStream? = null

    public suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        val sslContext = crypto.createSslContext()
        val socket = sslContext.socketFactory.createSocket() as SSLSocket
        
        socket.tcpNoDelay = true
        socket.sendBufferSize = 1024 * 1024
        socket.receiveBufferSize = 1024 * 1024
        socket.soTimeout = timeoutMs

        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.startHandshake()

        sslSocket = socket
        inputStream = socket.inputStream
        outputStream = socket.outputStream
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = withContext(Dispatchers.IO) {
        val input = inputStream ?: throw IllegalStateException("TLS Socket 未连接")
        var totalRead = 0
        while (totalRead < length) {
            val bytes = input.read(buffer, offset + totalRead, length - totalRead)
            if (bytes == -1) break
            totalRead += bytes
        }
        totalRead
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int): Unit = withContext(Dispatchers.IO) {
        val output = outputStream ?: throw IllegalStateException("TLS Socket 未连接")
        output.write(buffer, offset, length)
        output.flush()
    }

    override suspend fun close(): Unit = withContext(Dispatchers.IO) {
        runCatching { sslSocket?.close() }
        sslSocket = null
        inputStream = null
        outputStream = null
    }
}
