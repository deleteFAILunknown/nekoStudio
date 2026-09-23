package libs.libs.libs.adb.pair

import libs.libs.libs.adb.key.AdbKeyManager
import libs.libs.libs.adb.tls.AdbTlsCertificate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

public class AdbPairingManager(private val keyManager: AdbKeyManager) {

    /**
     * 发起 6 位配对码配对
     * @param host 设备的 IP 地址
     * @param port 设置页面展示的“配对端口”
     * @param pairingCode 设置页面展示的 6 位数字配对码
     */
    public suspend fun pair(host: String, port: Int, pairingCode: String): Boolean = withContext(Dispatchers.IO) {
        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null

        try {
            val keyPair = keyManager.getKeyPair()
            val cert = AdbTlsCertificate.generateSelfSignedCertificate(keyPair)

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setKeyEntry("adb_pair_key", keyPair.private, pairingCode.toCharArray(), arrayOf<X509Certificate>(cert))
            }

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, pairingCode.toCharArray())
            }

            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })

            val sslContext = SSLContext.getInstance("TLSv1.3").apply {
                init(kmf.keyManagers, trustAllCerts, SecureRandom())
            }

            rawSocket = Socket()
            rawSocket.connect(InetSocketAddress(host, port), 10000)

            sslSocket = sslContext.socketFactory.createSocket(
                rawSocket, host, port, true
            ) as SSLSocket

            // 执行 SSL 握手交换配对信息
            sslSocket.startHandshake()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try {
                sslSocket?.close()
                rawSocket?.close()
            } catch (_: Exception) {}
        }
    }
}
