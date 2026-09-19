package libs.libs.libs.adb.protocol

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

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

    public fun generateCertificate(): X509Certificate {
        val certFactory = CertificateFactory.getInstance("X.509")
        val dummyDer = buildSelfSignedCertDer()
        return certFactory.generateCertificate(ByteArrayInputStream(dummyDer)) as X509Certificate
    }

    private fun buildSelfSignedCertDer(): ByteArray {
        val pubKey = keyPair.public.encoded
        return pubKey
    }

    public fun saveToFiles(privateKeyFile: File, publicKeyFile: File) {
        privateKeyFile.parentFile?.mkdirs()
        privateKeyFile.writeBytes(keyPair.private.encoded)
        publicKeyFile.writeBytes(keyPair.public.encoded)
    }

    public companion object {
        public fun generate(): AdbCrypto {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            return AdbCrypto(kpg.generateKeyPair())
        }

        public fun loadFromFiles(privateKeyFile: File, publicKeyFile: File): AdbCrypto {
            val keyFactory = KeyFactory.getInstance("RSA")

            val privateKeyBytes = privateKeyFile.readBytes()
            val privateKeySpec = PKCS8EncodedKeySpec(privateKeyBytes)
            val privateKey: PrivateKey = keyFactory.generatePrivate(privateKeySpec)

            val publicKeyBytes = publicKeyFile.readBytes()
            val publicKeySpec = X509EncodedKeySpec(publicKeyBytes)
            val publicKey: PublicKey = keyFactory.generatePublic(publicKeySpec)

            return AdbCrypto(KeyPair(publicKey, privateKey))
        }

        public fun loadOrGenerate(keyDir: File): AdbCrypto {
            val privateKeyFile = File(keyDir, "adbkey")
            val publicKeyFile = File(keyDir, "adbkey.pub")

            return if (privateKeyFile.exists() && publicKeyFile.exists()) {
                runCatching { 
                    loadFromFiles(privateKeyFile, publicKeyFile) 
                }.getOrElse {
                    generate().also { it.saveToFiles(privateKeyFile, publicKeyFile) }
                }
            } else {
                generate().also { it.saveToFiles(privateKeyFile, publicKeyFile) }
            }
        }

        public fun loadOrGenerate(context: Context): AdbCrypto {
            return loadOrGenerate(context.filesDir)
        }
    }
}
