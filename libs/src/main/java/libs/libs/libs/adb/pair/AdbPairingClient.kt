package libs.libs.libs.adb.pair

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libs.libs.libs.adb.key.AdbKeyManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

public class AdbPairingClient(
    private val keyManager: AdbKeyManager
) : AdbPairing {

    override suspend fun pair(
        host: String,
        port: Int,
        pairingCode: String,
        listener: AdbPairingListener?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            listener?.onPairingStarted()

            // 1. 校验 6 位配对码
            require(pairingCode.length == 6 && pairingCode.all { it.isDigit() }) {
                "Pairing code must be a 6-digit number."
            }

            // 2. 建立底层 Socket 连接
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

                // 3. 初始化配对 TLS 环境
                val sslContext = SSLContext.getInstance("TLSv1.3").apply {
                    init(null, arrayOf(AdbPairingTrustManager()), java.security.SecureRandom())
                }

                // 4. 包装为 SSLSocket 并开始握手
                val sslSocket = sslContext.socketFactory.createSocket(
                    socket,
                    host,
                    port,
                    true
                ) as SSLSocket

                sslSocket.use { tlsSocket ->
                    // 启用服务器端鉴权/握手
                    tlsSocket.startHandshake()

                    val inputStream = DataInputStream(tlsSocket.inputStream)
                    val outputStream = DataOutputStream(tlsSocket.outputStream)

                    // 5. 获取本地明文公钥 bytes (`adbkey.pub`) 并发送给设备
                    val pubKeyBytes = keyManager.getAdbPublicKeyBytes()
                    outputStream.writeInt(pubKeyBytes.size)
                    outputStream.write(pubKeyBytes)
                    outputStream.flush()

                    // 6. 读取设备的配对响应结果
                    // 0x00 代表配对成功 (Adb pairing success response)
                    val responseCode = inputStream.readByte().toInt()
                    if (responseCode == 0x00) {
                        listener?.onPairingSuccess(keyManager.getAdbPublicKeyString())
                        true
                    } else {
                        throw IllegalStateException("Pairing rejected by device, response code: $responseCode")
                    }
                }
            }
        } catch (e: Exception) {
            listener?.onPairingFailed(e)
            false
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5000
    }
}
