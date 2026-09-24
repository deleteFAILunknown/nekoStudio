package libs.libs.libs.adb.pair

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libs.libs.libs.adb.key.AdbKeyManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
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

            // 1. 校验配对码格式与 KeyManager 状态
            require(pairingCode.length == 6 && pairingCode.all { it.isDigit() }) {
                "Pairing code must be a 6-digit number."
            }
            require(keyManager.isLoaded) {
                "AdbKeyManager must load or generate key pair before pairing."
            }

            // 2. 初始化 SPAKE2 密码学引擎
            val spake2Engine = AdbSpake2Engine(pairingCode)

            // 3. 建立底层 Socket 连接
            Socket().use { rawSocket ->
                rawSocket.soTimeout = READ_TIMEOUT_MS
                rawSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

                // 4. 创建配对 TLS 1.3 通道
                val sslContext = SSLContext.getInstance("TLSv1.3").apply {
                    init(null, arrayOf(AdbPairingTrustManager()), SecureRandom())
                }

                val sslSocket = sslContext.socketFactory.createSocket(
                    rawSocket,
                    host,
                    port,
                    true
                ) as SSLSocket

                sslSocket.use { tlsSocket ->
                    tlsSocket.startHandshake()

                    val inputStream = DataInputStream(tlsSocket.inputStream)
                    val outputStream = DataOutputStream(tlsSocket.outputStream)

                    // 5. 【SPAKE2 握手阶段 1】发送 Client Hello
                    val clientHello = spake2Engine.generateClientHello()
                    outputStream.writeInt(clientHello.size)
                    outputStream.write(clientHello)
                    outputStream.flush()

                    // 6. 【SPAKE2 握手阶段 2】读取 Server Hello 并计算共享会话密钥
                    val serverHelloLen = inputStream.readInt()
                    require(serverHelloLen in 1..4096) { "Invalid SPAKE2 server hello length: $serverHelloLen" }
                    
                    val serverHello = ByteArray(serverHelloLen)
                    inputStream.readFully(serverHello)
                    
                    spake2Engine.processServerHelloAndDeriveKey(serverHello)

                    // 7. 【密文传输阶段】使用 SPAKE2 派生的 Key 加密发送 adbkey.pub
                    val pubKeyBytes = keyManager.getAdbPublicKeyBytes()
                    val encryptedPubKey = spake2Engine.encryptPayload(pubKeyBytes)

                    outputStream.writeInt(encryptedPubKey.size)
                    outputStream.write(encryptedPubKey)
                    outputStream.flush()

                    // 8. 读取配对结果状态码（0x00 代表配对成功）
                    val responseCode = inputStream.readByte().toInt()
                    if (responseCode == 0x00) {
                        val peerPubKey = keyManager.getAdbPublicKeyString()
                        listener?.onPairingSuccess(peerPubKey)
                        true
                    } else {
                        val error = IllegalStateException("Pairing rejected by peer device (SPAKE2 auth failed), code: $responseCode")
                        listener?.onPairingFailed(error)
                        throw error
                    }
                }
            }
        } catch (e: Exception) {
            listener?.onPairingFailed(e)
            false
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 10000
    }
}
