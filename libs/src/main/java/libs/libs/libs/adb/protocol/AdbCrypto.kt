package libs.libs.libs.adb.protocol

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
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

    /**
     * 将密钥对持久化保存到本地文件 (PKCS#8 私钥与 X.509 公钥)
     */
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

        /**
         * 从本地 adbkey 和 adbkey.pub 文件加载密钥对
         */
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

        /**
         * 自动本地化加载逻辑：如果文件存在则读取，不存在或损坏则自动生成并持久化保存
         */
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

        /**
         * 基于 Android Context 读取应用私有存储区 (filesDir)
         */
        public fun loadOrGenerate(context: Context): AdbCrypto {
            return loadOrGenerate(context.filesDir)
        }
    }
}
