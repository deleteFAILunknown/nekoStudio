package libs.libs.libs.adb.tls

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.util.Date

public object AdbTlsCertificate {

    /**
     * 根据 keyPair 动态生成自签名 X.509 证书
     */
    public fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val startDate = Date(now - 24 * 3600 * 1000) // 昨天生效
        val endDate = Date(now + 10L * 365 * 24 * 3600 * 1000) // 10年有效期

        val serialNumber = BigInteger.valueOf(now)
        val issuerName = X500Name("CN=NekoStudio, O=AndroidADB")

        val builder = JcaX509v3CertificateBuilder(
            issuerName,
            serialNumber,
            startDate,
            endDate,
            issuerName,
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val holder = builder.build(signer)

        return JcaX509CertificateConverter().getCertificate(holder)
    }
}
