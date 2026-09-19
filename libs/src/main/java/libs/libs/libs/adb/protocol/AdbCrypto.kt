package libs.libs.libs.adb.protocol

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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
        return "$encoded user@android\u0000".toByteArray(Charsets.UTF_8)
    }

    /**
     * 生成符合 X.509 ASN.1 规范的自签名证书，确保 KeyManager 和 TLS 握手不崩溃
     */
    public fun generateCertificate(): X509Certificate {
        val derBytes = generateSelfSignedCertDer(
            keyPair = keyPair,
            subjectDn = "CN=ADB Key, O=Android",
            validityDays = 3650
        )
        val certFactory = CertificateFactory.getInstance("X.509")
        return certFactory.generateCertificate(ByteArrayInputStream(derBytes)) as X509Certificate
    }

    public fun createSslContext(): SSLContext {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            val cert = generateCertificate()
            setKeyEntry("adb_key", keyPair.private, "adb".toCharArray(), arrayOf(cert))
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, "adb".toCharArray())
        }

        val sslContext = SSLContext.getInstance("TLSv1.3")
        sslContext.init(kmf.keyManagers, trustAllCerts, null)
        return sslContext
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

        /**
         * 轻量级构建符合 X.509 标准结构（TBSCertificate + SignatureAlgorithm + SignatureValue）的自签名 DER 字节流
         */
        private fun generateSelfSignedCertDer(
            keyPair: KeyPair,
            subjectDn: String,
            validityDays: Long
        ): ByteArray {
            val now = System.currentTimeMillis()
            val notBefore = Date(now - 86400000L) // 昨天
            val notAfter = Date(now + validityDays * 86400000L)

            // TBS (To-Be-Signed) Certificate 封装
            val tbsStream = ByteArrayOutputStream()
            // 1. Version (v3 -> 2)
            tbsStream.write(byteArrayOf(0xA0.toByte(), 0x03, 0x02, 0x01, 0x02))
            // 2. Serial Number
            val serialBytes = BigInteger.valueOf(now).toByteArray()
            tbsStream.write(0x02)
            tbsStream.write(serialBytes.size)
            tbsStream.write(serialBytes)
            // 3. Signature AlgorithmIdentifier (SHA256withRSA: 1.2.840.113549.1.1.11)
            val algId = byteArrayOf(0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00)
            tbsStream.write(algId)
            // 4. Issuer & Subject Name
            val nameDer = encodeName(subjectDn)
            tbsStream.write(nameDer) // Issuer
            // 5. Validity
            val validityDer = encodeValidity(notBefore, notAfter)
            tbsStream.write(validityDer)
            tbsStream.write(nameDer) // Subject (自签名同 Issuer)
            // 6. SubjectPublicKeyInfo
            tbsStream.write(keyPair.public.encoded)

            val tbsBytes = encodeSequence(tbsStream.toByteArray())

            // 用私钥对 TBS 进行签名
            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(keyPair.private)
            signer.update(tbsBytes)
            val signatureBytes = signer.sign()

            // 最终装配为 Signed Certificate Sequence
            val certStream = ByteArrayOutputStream()
            certStream.write(tbsBytes)
            certStream.write(algId)
            // Bit String 格式签名（带 0x00 填充位）
            val bitStringStream = ByteArrayOutputStream()
            bitStringStream.write(0x00)
            bitStringStream.write(signatureBytes)
            val bitStringBytes = bitStringStream.toByteArray()
            certStream.write(0x03)
            writeLength(certStream, bitStringBytes.size)
            certStream.write(bitStringBytes)

            return encodeSequence(certStream.toByteArray())
        }

        private fun encodeSequence(content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(0x30)
            writeLength(out, content.size)
            out.write(content)
            return out.toByteArray()
        }

        private fun encodeName(dn: String): ByteArray {
            val out = ByteArrayOutputStream()
            val attrOut = ByteArrayOutputStream()
            attrOut.write(byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)) // OID: 2.5.4.3 (CN)
            val valBytes = "ADB Key".toByteArray(Charsets.UTF_8)
            attrOut.write(0x0C) // UTF8String
            attrOut.write(valBytes.size)
            attrOut.write(valBytes)
            
            val setBytes = encodeSet(encodeSequence(attrOut.toByteArray()))
            out.write(setBytes)
            return encodeSequence(out.toByteArray())
        }

        private fun encodeSet(content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(0x31)
            writeLength(out, content.size)
            out.write(content)
            return out.toByteArray()
        }

        private fun encodeValidity(notBefore: Date, notAfter: Date): ByteArray {
            fun formatDate(d: Date): ByteArray {
                val s = String.format("%02d%02d%02d%02d%02d%02dZ", 
                    d.year % 100, d.month + 1, d.date, d.hours, d.minutes, d.seconds)
                val bytes = s.toByteArray(Charsets.US_ASCII)
                val out = ByteArrayOutputStream()
                out.write(0x17) // UTCTime
                out.write(bytes.size)
                out.write(bytes)
                return out.toByteArray()
            }
            val out = ByteArrayOutputStream()
            out.write(formatDate(notBefore))
            out.write(formatDate(notAfter))
            return encodeSequence(out.toByteArray())
        }

        private fun writeLength(out: ByteArrayOutputStream, length: Int) {
            if (length < 128) {
                out.write(length)
            } else if (length < 256) {
                out.write(0x81)
                out.write(length)
            } else {
                out.write(0x82)
                out.write(length shr 8)
                out.write(length and 0xFF)
            }
        }
    }
}
