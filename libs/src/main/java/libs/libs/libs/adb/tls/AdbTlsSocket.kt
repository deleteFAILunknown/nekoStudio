package libs.libs.libs.adb.tls

import libs.libs.libs.adb.key.AdbKeyManager
import libs.libs.libs.adb.public.AdbCommand
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
     * @param timeoutMs 连接与读取超时时间 (毫秒)
     */
    public suspend fun connect(host: String, port: Int, timeoutMs: Int = 10000) = withContext(Dispatchers.IO) {
        close()

        val keyPair = keyManager.getKeyPair()
        val cert = AdbTlsCertificate.generateSelfSignedCertificate(keyPair)

        // 1. 显式使用 PKCS12 构建内存 KeyStore，避免部分 OEM 系统默认 KeyStore 类型不一致
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("adb_client_key", keyPair.private, KEY_PASSWORD.toCharArray(), arrayOf<X509Certificate>(cert))
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, KEY_PASSWORD.toCharArray())
        }

        // 2. 忽略服务端证书校验（ADB 服务端同样使用自签名证书，通过 Client 证书在白名单中比对公钥）
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })

        // 3. 初始化 TLS 1.3 上下文
        val sslContext = SSLContext.getInstance("TLSv1.3").apply {
            init(kmf.keyManagers, trustAllCerts, SecureRandom())
        }

        // 4. 建立底层 Socket 并配置超时与 TCP 选项
        val rawSocket = Socket()
        rawSocket.soTimeout = timeoutMs
        rawSocket.tcpNoDelay = true
        rawSocket.connect(InetSocketAddress(host, port), timeoutMs)

        // 5. 升级为 SSLSocket 并发起握手
        val ssl = sslContext.socketFactory.createSocket(
            rawSocket, host, port, true
        ) as SSLSocket

        ssl.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
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

    /**
     * 从 TLS 流中读取并解析出一个标准的 AdbPacket
     */
    public suspend fun readPacket(): AdbPacket = withContext(Dispatchers.IO) {
        val headerBytes = ByteArray(AdbPacket.HEADER_SIZE)
        readExactly(headerBytes, AdbPacket.HEADER_SIZE)

        val header = AdbPacket.parseHeader(headerBytes)
        check(header.isValid) { "Invalid ADB packet header over TLS: command=0x${Integer.toHexString(header.command)}" }

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

    /**
     * 发送 AdbPacket 数据包（TLS 链路上默认 skipChecksum = true）
     */
    public suspend fun writePacket(packet: AdbPacket, skipChecksum: Boolean = true) = withContext(Dispatchers.IO) {
        val stream = outputStream ?: throw IllegalStateException("TLS Socket is not connected")
        val bytes = packet.toByteArray(skipChecksum = skipChecksum)
        stream.write(bytes)
        stream.flush()
    }

    public fun close() {
        try {
            // 优先关闭 SSL Socket，触发优雅的 TLS close_notify 握手
            sslSocket?.close()
            inputStream?.close()
            outputStream?.close()
        } catch (_: Exception) {
        } finally {
            inputStream = null
            outputStream = null
            sslSocket = null
        }
    }

    public val isConnected: Boolean
        get() = sslSocket?.isConnected == true && sslSocket?.isClosed == false && sslSocket?.isOutputShutdown == false

    companion object {
        private const val KEY_PASSWORD = "adb_tls_password"
    }
}
