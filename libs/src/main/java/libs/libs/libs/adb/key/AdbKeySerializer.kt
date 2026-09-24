package libs.libs.libs.adb.key

import org.bouncycastle.asn1.pkcs.RSAPrivateKey
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemReader
import org.bouncycastle.util.io.pem.PemWriter
import java.io.StringReader
import java.io.StringWriter

public object AdbKeySerializer {

    /**
     * 将 BC 私钥对象导出为包含 -----BEGIN PRIVATE KEY----- 的 PKCS#8 PEM 格式文本 (`adbkey`)
     */
    public fun privateKeyToPem(privateKey: AsymmetricKeyParameter): String {
        val stringWriter = StringWriter()
        PemWriter(stringWriter).use { pemWriter ->
            val privateKeyInfo = PrivateKeyInfoFactory.createPrivateKeyInfo(privateKey)
            pemWriter.writeObject(PemObject("PRIVATE KEY", privateKeyInfo.encoded))
        }
        return stringWriter.toString()
    }

    /**
     * 从 PEM 文本解析私钥，兼容 PKCS#8 ("PRIVATE KEY") 与 PKCS#1 ("RSA PRIVATE KEY")
     */
    public fun privateKeyFromPem(pemString: String): AsymmetricKeyParameter {
        PemReader(StringReader(pemString)).use { pemReader ->
            val pemObject = pemReader.readPemObject()
                ?: throw IllegalArgumentException("Invalid PEM format: empty or unparseable content")

            return when (pemObject.type) {
                "PRIVATE KEY" -> PrivateKeyFactory.createKey(pemObject.content)
                "RSA PRIVATE KEY" -> {
                    val rsa = RSAPrivateKey.getInstance(pemObject.content)
                    RSAPrivateCrtKeyParameters(
                        rsa.modulus,
                        rsa.publicExponent,
                        rsa.privateExponent,
                        rsa.prime1,
                        rsa.prime2,
                        rsa.exponent1,
                        rsa.exponent2,
                        rsa.coefficient
                    )
                }
                else -> throw IllegalArgumentException("Unsupported PEM type: ${pemObject.type}")
            }
        }
    }
}
