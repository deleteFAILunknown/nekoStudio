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
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
            subjectDn = "CN=ADB Key, O=Android, OU=NekoStudio, C=US",
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

    /**
     * 将密钥导出并保存为标准明文文本文件：
     * - adbkey: 明文 PEM 格式私钥
     * - adbkey.pub: 标准 ADB 公钥明文文本
     */
    public fun saveToFiles(privateKeyFile: File, publicKeyFile: File) {
        privateKeyFile.parentFile?.mkdirs()
        publicKeyFile.parentFile?.mkdirs()

        // 1. 私钥转化为 PEM 明文格式 (PKCS#8 Base64 每 64 字符分行)
        val privBase64 = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)
        val pemBody = privBase64.chunked(64).joinToString("\n")
        val pemString = "-----BEGIN PRIVATE KEY-----\n$pemBody\n-----END PRIVATE KEY-----\n"
        privateKeyFile.writeText(pemString, Charsets.US_ASCII)

        // 2. 公钥保存为 ADB 明文文本格式
        val pubBytes = getAdbPublicKey()
        publicKeyFile.writeBytes(pubBytes)
    }

    public companion object {
        // 常见 DN 属性及其 OID 和 ASN.1 String 类型 Tag 映射
        private val DN_OIDS = mapOf(
            "CN" to Pair(byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03), 0x0C.toByte()), // UTF8String
            "O"  to Pair(byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x0A), 0x0C.toByte()), // UTF8String
            "OU" to Pair(byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x0B), 0x0C.toByte()), // UTF8String
            "C"  to Pair(byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x06), 0x13.toByte()), // PrintableString (两字母国家代码)
            "ST" to Pair(byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x08), 0x0C.toByte()), // UTF8String
            "L"  to Pair(byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x07), 0x0C.toByte()), // UTF8String
            "EMAIL" to Pair(
                byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x09, 0x01),
                0x16.toByte() // IA5String
            )
        )

        public fun generate(): AdbCrypto {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            return AdbCrypto(kpg.generateKeyPair())
        }

        /**
         * 从文件读取密钥（无缝兼容 PEM 明文文本 与 旧版 DER 二进制格式）
         */
        public fun loadFromFiles(privateKeyFile: File, publicKeyFile: File): AdbCrypto {
            val keyFactory = KeyFactory.getInstance("RSA")

            // 1. 读取私钥内容
            val privateKeyContent = privateKeyFile.readText(Charsets.US_ASCII)
            val privateKeyBytes = if (privateKeyContent.contains("-----BEGIN")) {
                // 过滤 PEM 标头标尾及换行符，提取纯 Base64 字节
                val cleanBase64 = privateKeyContent
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replace("\\s+".toRegex(), "")
                Base64.decode(cleanBase64, Base64.DEFAULT)
            } else {
                // 向前兼容：读取旧版本的原始二进制 DER 数据
                privateKeyFile.readBytes()
            }

            val privateKeySpec = PKCS8EncodedKeySpec(privateKeyBytes)
            val privateKey: PrivateKey = keyFactory.generatePrivate(privateKeySpec)

            // 2. 从 RSA 私钥直接派生对应的公钥 (RSA 私钥内含 Modulus 和 Exponent)
            val publicKey: PublicKey = runCatching {
                val rsaPrivate = privateKey as RSAPrivateCrtKey
                val pubSpec = RSAPublicKeySpec(rsaPrivate.modulus, rsaPrivate.publicExponent)
                keyFactory.generatePublic(pubSpec)
            }.getOrElse {
                // 备用兜底逻辑：读取 publicKeyFile 文本解析
                val pubContent = publicKeyFile.readText(Charsets.US_ASCII)
                val cleanBase64 = pubContent
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace("\\s+".toRegex(), "")
                    .split(" ")[0]
                val pubBytes = runCatching { Base64.decode(cleanBase64, Base64.DEFAULT) }.getOrDefault(publicKeyFile.readBytes())
                keyFactory.generatePublic(X509EncodedKeySpec(pubBytes))
            }

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

        /**
         * 动态解析并编码 DN 字符串（如 "CN=ADB Key, O=Android, OU=Studio, C=US"）为标准的 ASN.1 RDNSequence
         */
        private fun encodeName(dn: String): ByteArray {
            val out = ByteArrayOutputStream()
            val pairs = dn.split(",")

            for (pair in pairs) {
                val parts = pair.trim().split("=", limit = 2)
                if (parts.size == 2) {
                    val rawKey = parts[0].trim().uppercase()
                    val value = parts[1].trim()
                    
                    val key = if (rawKey == "E") "EMAIL" else rawKey
                    val attrInfo = DN_OIDS[key]

                    if (attrInfo != null) {
                        val rdnBytes = encodeAttribute(attrInfo.first, attrInfo.second, value)
                        out.write(rdnBytes)
                    }
                }
            }

            return encodeSequence(out.toByteArray())
        }

        /**
         * 编码单个 RDN：SET { SEQUENCE { AttributeType (OID), AttributeValue } }
         */
        private fun encodeAttribute(oid: ByteArray, tag: Byte, value: String): ByteArray {
            val attrStream = ByteArrayOutputStream()
            
            // 1. 写入 OID
            attrStream.write(oid)
            
            // 2. 写入 Value (Tag + Length + UTF8/Printable/IA5 Bytes)
            val valBytes = value.toByteArray(Charsets.UTF_8)
            val valStream = ByteArrayOutputStream()
            valStream.write(tag.toInt())
            writeLength(valStream, valBytes.size)
            valStream.write(valBytes)

            attrStream.write(valStream.toByteArray())

            // 3. 封装为 SEQUENCE 节点
            val seqBytes = encodeSequence(attrStream.toByteArray())

            // 4. 封装为 SET 节点 (RelativeDistinguishedName)
            return encodeSet(seqBytes)
        }

        private fun encodeSet(content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(0x31)
            writeLength(out, content.size)
            out.write(content)
            return out.toByteArray()
        }

        private fun encodeValidity(notBefore: Date, notAfter: Date): ByteArray {
            val utcFormat = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

            fun formatDate(d: Date): ByteArray {
                val bytes = utcFormat.format(d).toByteArray(Charsets.US_ASCII)
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
