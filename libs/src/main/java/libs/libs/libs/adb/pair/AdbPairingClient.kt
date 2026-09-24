package libs.libs.libs.adb.pair

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import libs.libs.libs.adb.key.AdbKeyManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

@OptIn(ExperimentalSerializationApi::class)
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

            require(pairingCode.length == 6 && pairingCode.all { it.isDigit() }) {
                "Pairing code must be a 6-digit number."
            }
            require(keyManager.isLoaded) {
                "AdbKeyManager must load or generate key pair before pairing."
            }

            val spake2Engine = AdbSpake2Engine(pairingCode)

            Socket().use { rawSocket ->
                rawSocket.soTimeout = READ_TIMEOUT_MS
                rawSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

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

                    // 1. 【SPAKE2 阶段 1】发送 Client Hello (包裹 PairingPacket: SPAKE2_MSG)
                    val rawClientHello = spake2Engine.generateClientHello()
                    val clientPacket = PairingPacket(
                        type = PairingPacket.Type.SPAKE2_MSG,
                        payload = rawClientHello
                    )
                    sendPacket(outputStream, clientPacket)

                    // 2. 【SPAKE2 阶段 2】接收 Server Hello 并派生密钥
                    val serverPacket = receivePacket(inputStream)
                    require(serverPacket.type == PairingPacket.Type.SPAKE2_MSG) {
                        "Expected SPAKE2_MSG packet type, got: ${serverPacket.type}"
                    }
                    spake2Engine.processServerHelloAndDeriveKey(serverPacket.payload)

                    // 3. 【密文传输阶段】加密发送 RSA ADB 公钥 (包裹 PairingPacket: PEER_INFO)
                    // 注: AOSP 格式要求为包含完整字符串的 ByteArray (如 "ssh-rsa AAAAB3... user@host\0")
                    val pubKeyBytes = keyManager.getAdbPublicKeyString().toByteArray(Charsets.UTF_8)
                    val encryptedPubKey = spake2Engine.encryptPayload(pubKeyBytes)

                    val infoPacket = PairingPacket(
                        type = PairingPacket.Type.PEER_INFO,
                        payload = encryptedPubKey
                    )
                    sendPacket(outputStream, infoPacket)

                    // 4. 【结果校验】读取并解密对端响应
                    val responsePacket = receivePacket(inputStream)
                    val decryptedResponse = spake2Engine.decryptPayload(responsePacket.payload)

                    // 对端响应不为空即表示配对验证通过并建立了互信
                    if (decryptedResponse.isNotEmpty()) {
                        val peerPubKey = keyManager.getAdbPublicKeyString()
                        listener?.onPairingSuccess(peerPubKey)
                        true
                    } else {
                        val error = IllegalStateException("Pairing rejected by peer device (empty auth response)")
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

    /**
     * 写入带 4 字节 Big-Endian 长度前缀的 Protobuf 报文
     */
    private fun sendPacket(out: DataOutputStream, packet: PairingPacket) {
        val bytes = ProtoBuf.encodeToByteArray(packet)
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    /**
     * 读取带 4 字节 Big-Endian 长度前缀的 Protobuf 报文
     */
    private fun receivePacket(input: DataInputStream): PairingPacket {
        val len = input.readInt()
        require(len in 1..65536) { "Invalid packet length received: $len" }
        val buf = ByteArray(len)
        input.readFully(buf)
        return ProtoBuf.decodeFromByteArray(buf)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 10000
    }
}
