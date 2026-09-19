package libs.libs.libs.adb.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libs.libs.libs.adb.protocol.AdbCrypto
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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
        val sslContext = createAdbSslContext(crypto)
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

    public companion object {
        public fun createAdbSslContext(crypto: AdbCrypto): SSLContext {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                val cert = crypto.generateCertificate()
                setKeyEntry("adb_key", crypto.keyPair.private, "adb".toCharArray(), arrayOf(cert))
            }

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, "adb".toCharArray())
            }

            val sslContext = SSLContext.getInstance("TLSv1.3")
            sslContext.init(kmf.keyManagers, trustAllCerts, null)
            return sslContext
        }
    }
}
