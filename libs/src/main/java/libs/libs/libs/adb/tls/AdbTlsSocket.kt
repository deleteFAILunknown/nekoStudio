package libs.libs.libs.adb.tls

import libs.libs.libs.adb.key.AdbKeyManager
import libs.libs.libs.adb.public.AdbPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

public class AdbTlsSocket(private val keyManager: AdbKeyManager) {

    private var sslSocket: SSLSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    /**
     * 建立加密 TLS Socket 连接
     */
    public suspend fun connect(host: String, port: Int, timeoutMs: Int = 10000) = withContext(Dispatchers.IO) {
        close()

        val keyPair = keyManager.getKeyPair()
        val cert = AdbTlsCertificate.generateSelfSignedCertificate(keyPair)

        // 1. 将 KeyPair 和 Cert 装载到内存 KeyStore
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("adb_key", keyPair.private, "password".toCharArray(), arrayOf<X509Certificate>(cert))
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, "password".toCharArray())
        }

        // 2. 忽略服务端证书校验（ADB 使用 Client 证书作为凭证）
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })

        // 3. 初始化 TLS 1.3 上下文
        val sslContext = SSLContext.getInstance("TLSv1.3").apply {
            init(kmf.keyManagers, trustAllCerts, SecureRandom())
        }

        // 4. 连接并建立 TLS 握手
        val rawSocket = Socket()
        rawSocket.connect(InetSocketAddress(host, port), timeoutMs)
        rawSocket.tcpNoDelay = true

        val ssl = sslContext.socketFactory.createSocket(
            rawSocket, host, port, true
        ) as SSLSocket

        ssl.startHandshake()

        this@AdbTlsSocket.sslSocket = ssl
        this@AdbTlsSocket.inputStream = ssl.inputStream
        this@AdbTlsSocket.outputStream = ssl.outputStream
    }

    private fun readExactly(buffer: ByteArray, length: Int) {
        val stream = inputStream ?: throw IllegalStateException("TLS Socket is not connected")
        var bytesRead = 0
        while (bytesRead < length) {
            val count = stream.read(buffer, bytesRead, length - bytesRead)
            if (count == -1) throw IllegalStateException("TLS Socket stream closed unexpectedly")
            bytesRead += count
        }
    }

    public suspend fun readPacket(): AdbPacket = withContext(Dispatchers.IO) {
        val headerBytes = ByteArray(AdbPacket.HEADER_SIZE)
        readExactly(headerBytes, AdbPacket.HEADER_SIZE)

        val header = AdbPacket.parseHeader(headerBytes)
        check(header.isValid) { "Invalid ADB packet header over TLS" }

        val payload = if (header.dataLength > 0) {
            ByteArray(header.dataLength).also { readExactly(it, header.dataLength) }
        } else {
            ByteArray(0)
        }

        AdbPacket(
            command = header.command,
            arg0 = header.arg0,
            arg1 = header.arg1,
            payload = payload
        )
    }

    public suspend fun writePacket(packet: AdbPacket, skipChecksum: Boolean = true) = withContext(Dispatchers.IO) {
        val stream = outputStream ?: throw IllegalStateException("TLS Socket is not connected")
        val bytes = packet.toByteArray(skipChecksum = skipChecksum)
        stream.write(bytes)
        stream.flush()
    }

    public fun close() {
        try {
            inputStream?.close()
            outputStream?.close()
            sslSocket?.close()
        } catch (_: Exception) {
        } finally {
            inputStream = null
            outputStream = null
            sslSocket = null
        }
    }

    public val isConnected: Boolean get() = sslSocket?.isConnected == true && sslSocket?.isClosed == false
}
