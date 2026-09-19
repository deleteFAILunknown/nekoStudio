package libs.libs.libs.adb.pairing

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

public object Spake2Native {
    init {
        System.loadLibrary("native-lib")
    }

    external fun nativeGenerateClientPoint(
        pairingCode: String,
        clientPrivateKey: ByteArray
    ): ByteArray?

    external fun nativeDeriveKey(
        pairingCode: String,
        clientPrivateKey: ByteArray,
        serverPointY: ByteArray
    ): ByteArray?
}

public class Spake2Engine(
    public val pairingCode: String
) {
    private val random = SecureRandom()

    // 随机生成 32 字节客户端私钥
    public val clientPrivateKey: ByteArray = ByteArray(32).also { random.nextBytes(it) }

    // 调用 C++ / OpenSSL 计算客户端公钥点 X = x*G + w*M
    public val clientPublicKey: ByteArray by lazy {
        Spake2Native.nativeGenerateClientPoint(pairingCode, clientPrivateKey)
            ?: throw IllegalStateException("SPAKE2 客户端公钥点生成失败，请检查 Native 库")
    }

    /**
     * 结合服务端公钥点 Y，调用 C++ / OpenSSL 计算共享密钥并执行 HKDF-SHA256 派生
     */
    public fun deriveAesKey(serverPublicKeyY: ByteArray): ByteArray {
        return Spake2Native.nativeDeriveKey(pairingCode, clientPrivateKey, serverPublicKeyY)
            ?: throw IllegalStateException("SPAKE2 共享 AES 密钥派生失败，配对码可能不匹配")
    }

    /**
     * 使用 Android 原生 javax.crypto 执行 AES-128-GCM 加密 (12 字节 IV + 16 字节 Tag)
     */
    public fun encryptPayload(aesKey: ByteArray, plainText: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(aesKey, "AES")
        val gcmSpec = GCMParameterSpec(128, nonce)

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val cipherText = cipher.doFinal(plainText)

        return nonce + cipherText
    }
}
