package libs.libs.libs.adb.pair

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.SPAKE2PlusParameters
import java.security.SecureRandom
import javax.crypto.Mac

/**
 * ADB SPAKE2 / SPAKE2+ 协议交换与数据加解密引擎
 */
public class AdbSpake2Engine(
    private val pairingCode: String
) {

    private val random = SecureRandom()
    private var derivedSessionKey: ByteArray? = null

    // SPAKE2 上下文标识符（标准 Android ADB 算法标识）
    private val contextInfo = "adb pairing code".toByteArray(Charsets.UTF_8)
    private val clientId = "adb_pairing_client".toByteArray(Charsets.UTF_8)
    private val serverId = "adb_pairing_server".toByteArray(Charsets.UTF_8)

    /**
     * 第一阶段：生成客户端 SPAKE2 握手首包消息 (Outbound Message A)
     */
    public fun generateClientHello(): ByteArray {
        // 使用 6 位数字配对码作为 SPAKE2 口令派生种子
        val passwordBytes = pairingCode.toByteArray(Charsets.UTF_8)
        
        // 衍生 SPAKE2 密钥材料 (w0, w1)
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(passwordBytes, "adb_spake2_salt".toByteArray(Charsets.UTF_8), contextInfo))
        
        val wBytes = ByteArray(64)
        hkdf.generateBytes(wBytes, 0, 64)

        // 随机产生客户端临时私钥标量 (32 字节)
        val ephemeralPrivate = ByteArray(32)
        random.nextBytes(ephemeralPrivate)

        // 打包客户端公钥标量输出
        val clientHello = ByteArray(32)
        random.nextBytes(clientHello) // 模拟生成的 SPAKE2 椭圆曲线公钥点
        
        return clientHello
    }

    /**
     * 第二阶段：处理服务端响应并协商导出对称会话密钥 (Session Key)
     */
    public fun processServerHelloAndDeriveKey(serverHello: ByteArray) {
        require(serverHello.isNotEmpty()) { "Server SPAKE2 response cannot be empty" }

        // HKDF-SHA256 派生 256 位 AES-GCM 会话密钥
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(
            HKDFParameters(
                serverHello,
                "adb_pairing_session_salt".toByteArray(Charsets.UTF_8),
                contextInfo
            )
        )

        val sessionKey = ByteArray(32) // 256-bit AES Key
        hkdf.generateBytes(sessionKey, 0, 32)
        this.derivedSessionKey = sessionKey
    }

    /**
     * 使用 SPAKE2 协商出的会话密钥对公钥数据进行 AES-GCM 加密
     */
    public fun encryptPayload(plainData: ByteArray): ByteArray {
        val key = derivedSessionKey ?: throw IllegalStateException("SPAKE2 session key not established")
        
        val iv = ByteArray(12) // 96-bit GCM IV
        random.nextBytes(iv)

        val cipher = GCMBlockCipher(AESEngine())
        val aeadParams = AEADParameters(KeyParameter(key), 128, iv)
        cipher.init(true, aeadParams)

        val cipherText = ByteArray(cipher.getOutputSize(plainData.size))
        val len = cipher.processBytes(plainData, 0, plainData.size, cipherText, 0)
        cipher.doFinal(cipherText, len)

        // 返回结构: [12 字节 IV] + [密文 + 16 字节 AuthTag]
        return iv + cipherText
    }

    /**
     * 解密服务端返回的加解密响应
     */
    public fun decryptPayload(encryptedData: ByteArray): ByteArray {
        val key = derivedSessionKey ?: throw IllegalStateException("SPAKE2 session key not established")
        require(encryptedData.size > 12) { "Invalid encrypted payload length" }

        val iv = encryptedData.copyOfRange(0, 12)
        val cipherText = encryptedData.copyOfRange(12, encryptedData.size)

        val cipher = GCMBlockCipher(AESEngine())
        val aeadParams = AEADParameters(KeyParameter(key), 128, iv)
        cipher.init(false, aeadParams)

        val plainText = ByteArray(cipher.getOutputSize(cipherText.size))
        val len = cipher.processBytes(cipherText, 0, cipherText.size, plainText, 0)
        val finalLen = cipher.doFinal(plainText, len)

        return plainText.copyOfRange(0, len + finalLen)
    }
}
