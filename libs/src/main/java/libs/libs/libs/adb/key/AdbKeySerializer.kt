package libs.libs.libs.adb.key

import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemReader
import org.bouncycastle.util.io.pem.PemWriter
import java.io.StringReader
import java.io.StringWriter

public object AdbKeySerializer {

    /**
     * 将 BC 私钥对象导出为包含 -----BEGIN PRIVATE KEY----- 的标准的 PKCS#8 PEM 文本
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
     * 从 Pem 格式文本解析私钥
     */
    public fun privateKeyFromPem(pemString: String): AsymmetricKeyParameter {
        PemReader(StringReader(pemString)).use { pemReader ->
            val pemObject = pemReader.readPemObject()
                ?: throw IllegalArgumentException("Invalid PEM format")
            return PrivateKeyFactory.createKey(pemObject.content)
        }
    }
}
