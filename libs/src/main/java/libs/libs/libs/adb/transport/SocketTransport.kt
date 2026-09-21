package libs.libs.libs.adb.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libs.libs.libs.adb.protocol.AdbCrypto
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

public class SocketTransport(
    public val host: String,
    public val port: Int = 5555,
    public val timeoutMs: Int = 10000
) : AdbTransport {
    public var rawSocket: Socket? = null
    public var inputStream: InputStream? = null
    public var outputStream: OutputStream? = null

    public suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        val s = Socket().apply {
            tcpNoDelay = true
            sendBufferSize = 1024 * 1024
            receiveBufferSize = 1024 * 1024
            connect(InetSocketAddress(host, port), timeoutMs)
            soTimeout = timeoutMs
        }
        rawSocket = s
        inputStream = s.getInputStream()
        outputStream = s.getOutputStream()
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = withContext(Dispatchers.IO) {
        val input = inputStream ?: throw IllegalStateException("Socket Nullify")
        var totalRead = 0
        while (totalRead < length) {
            val bytes = input.read(buffer, offset + totalRead, length - totalRead)
            if (bytes == -1) break
            totalRead += bytes
        }
        totalRead
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int): Unit = withContext(Dispatchers.IO) {
        val output = outputStream ?: throw IllegalStateException("Socket Nullify")
        output.write(buffer, offset, length)
        output.flush()
    }

    override suspend fun startTls(crypto: AdbCrypto): Unit = withContext(Dispatchers.IO) {
        val currentSocket = rawSocket ?: throw IllegalStateException("Socket Nullify")
        val sslContext = crypto.createSslContext()
        val sslSocket = sslContext.socketFactory.createSocket(
            currentSocket,
            host,
            port,
            true // autoClose
        ) as SSLSocket

        sslSocket.useClientMode = true
        sslSocket.startHandshake()

        rawSocket = sslSocket
        inputStream = sslSocket.inputStream
        outputStream = sslSocket.outputStream
    }

    override suspend fun close(): Unit = withContext(Dispatchers.IO) {
        runCatching { rawSocket?.close() }
        rawSocket = null
        inputStream = null
        outputStream = null
    }
}
