package libs.libs.libs.adb.protocol

import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey

public class AdbCrypto(public val keyPair: KeyPair) {

    public fun sign(token: ByteArray): ByteArray {
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(token)
        return signer.sign()
    }

    public fun getAdbPublicKey(): ByteArray {
        val pubKey = keyPair.public as RSAPublicKey
        val encoded = Base64.encodeToString(pubKey.encoded, Base64.NO_WRAP)
        return "$encoded user@android\0".toByteArray(Charsets.UTF_8)
    }

    public companion object {
        public fun generate(): AdbCrypto {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            return AdbCrypto(kpg.generateKeyPair())
        }
    }
}
