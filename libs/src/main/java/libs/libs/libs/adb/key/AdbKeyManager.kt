package libs.libs.libs.adb.key

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.signers.RSADigestSigner
import java.math.BigInteger
import java.security.SecureRandom

public class AdbKeyManager {

    private var privateKey: AsymmetricKeyParameter? = null
    private var publicKeyString: String? = null

    /**
     * 加载现有的 adbkey (PEM 文本) 和 adbkey.pub (文本)
     */
    public fun loadKeys(adbKeyPem: String, adbKeyPub: String) {
        this.privateKey = AdbKeySerializer.privateKeyFromPem(adbKeyPem)
        this.publicKeyString = adbKeyPub.trim()
    }

    /**
     * 生成全新的 2048 位 RSA 密钥对
     */
    public fun generateKeyPair(comment: String = "adb@key"): Pair<String, String> {
        val generator = RSAKeyPairGenerator()
        generator.init(
            RSAKeyGenerationParameters(
                BigInteger.valueOf(65537),
                SecureRandom(),
                2048,
                80
            )
        )

        val pair: AsymmetricCipherKeyPair = generator.generateKeyPair()
        val privKey = pair.private
        val pubKeyParams = pair.public as RSAKeyParameters

        val pemPrivKey = AdbKeySerializer.privateKeyToPem(privKey)
        val pubKeyStr = AdbKeyUtils.convertToAdbPublicKeyString(pubKeyParams, comment)

        this.privateKey = privKey
        this.publicKeyString = pubKeyStr

        return Pair(pemPrivKey, pubKeyStr)
    }

    /**
     * CMD_AUTH 阶段收到 AUTH_TOKEN 时进行 SHA1WithRSA 签名
     */
    public fun signToken(token: ByteArray): ByteArray {
        val privKey = privateKey ?: throw IllegalStateException("PrivateKey not loaded")
        val signer = RSADigestSigner(SHA1Digest())
        signer.init(true, privKey)
        signer.update(token, 0, token.size)
        return signer.generateSignature()
    }

    public fun getAdbPublicKeyString(): String {
        return publicKeyString ?: throw IllegalStateException("PublicKey not loaded")
    }

    public fun getAdbPublicKeyBytes(): ByteArray {
        val keyStr = getAdbPublicKeyString()
        return "$keyStr\u0000".toByteArray(Charsets.UTF_8)
    }
}
