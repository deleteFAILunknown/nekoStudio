package libs.libs.libs.adb.pairing

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

public class Spake2Engine(pairingCode: String) {

    private object Spake2Native {
        init {
            System.loadLibrary("native-lib")
        }

        external fun nativeGenerateClientPoint(wScalar: ByteArray, privateKey: ByteArray): ByteArray
        external fun nativeDeriveKey(pairingCode: String, privateKey: ByteArray, serverPointY: ByteArray): ByteArray
    }

    private val random = SecureRandom()
    public val clientPrivateKey: ByteArray = ByteArray(32).also { random.nextBytes(it) }
    public val clientPublicKey: ByteArray

    init {
        // 调用 C++ / OpenSSL 生成 Point X
        clientPublicKey = Spake2Native.nativeGenerateClientPoint(pairingCode.toByteArray(), clientPrivateKey)
    }

    public fun deriveAesKey(serverPublicKeyY: ByteArray): ByteArray {
        // 调用 C++ / OpenSSL 计算共享密钥并执行 HKDF
        return Spake2Native.nativeDeriveKey(pairingCode, clientPrivateKey, serverPublicKeyY)
    }

    /**
     * 直接使用 Android 系统自带的 javax.crypto (Android 5.0+ 原生支持，不需要 BC)
     */
    public fun encryptPayload(aesKey: ByteArray, plainText: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding") // 系统原生 Provider
        val keySpec = SecretKeySpec(aesKey, "AES")
        val gcmSpec = GCMParameterSpec(128, nonce)

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val cipherText = cipher.doFinal(plainText)

        return nonce + cipherText
    }
}
