package libs.libs.libs.adb.pair

import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 基于 Bouncy Castle 基础椭圆曲线 API 与 JDK JCE 实现的 ADB SPAKE2 (P-256) 引擎
 */
public class AdbSpake2Engine(
    private val pairingCode: String
) {

    private val random = SecureRandom()

    // 1. 获取 P-256 (secp256r1) 椭圆曲线参数
    private val ecParams = ECNamedCurveTable.getByName("secp256r1")
        ?: throw IllegalStateException("secp256r1 curve not supported by BouncyCastle")
    private val curve = ecParams.curve
    private val G = ecParams.g
    private val q = ecParams.n

    // 2. 确定性推演 SPAKE2 生成点 M 与 N
    private val M: ECPoint by lazy {
        deriveGeneratorPoint("SPAKE2 P-256 Point M")
    }

    private val N: ECPoint by lazy {
        deriveGeneratorPoint("SPAKE2 P-256 Point N")
    }

    // 内部状态保存
    private var clientPrivateScalar: BigInteger? = null
    private var clientHelloBytes: ByteArray? = null
    private var derivedSessionKey: ByteArray? = null

    /**
     * 第一阶段：生成客户端 SPAKE2 握手点 (Client Hello)
     * 计算公式：X = x * G + w * M
     */
    public fun generateClientHello(): ByteArray {
        // 计算口令标量 w = Hash(pairingCode) mod q
        val w = hashToScalar(pairingCode.toByteArray(Charsets.UTF_8))

        // 随机产生客户端临时私钥标量 x (1 <= x < q)
        val x = BigInteger(256, random).mod(q.subtract(BigInteger.ONE)).add(BigInteger.ONE)
        this.clientPrivateScalar = x

        // 计算 X = x * G + w * M
        val wM = M.multiply(w)
        val xG = G.multiply(x)
        val X = xG.add(wM).normalize()

        // 编码为未压缩点字节数组 (65 字节)
        val encodedX = X.getEncoded(false)
        this.clientHelloBytes = encodedX
        return encodedX
    }

    /**
     * 第二阶段：接收服务端握手点 Y 并衍生会话密钥
     * 计算公式：K = x * (Y - w * N)
     */
    public fun processServerHelloAndDeriveKey(serverHello: ByteArray) {
        val x = clientPrivateScalar ?: throw IllegalStateException("Client hello not generated yet")
        val clientHello = clientHelloBytes ?: throw IllegalStateException("Client hello not generated yet")

        // 1. 反解析服务端的 ECPoint Y
        val Y = curve.decodePoint(serverHello)

        // 2. 计算口令标量 w
        val w = hashToScalar(pairingCode.toByteArray(Charsets.UTF_8))

        // 3. 计算共享导出的点 K = x * (Y - w * N)
        val wN = N.multiply(w)
        val YminusWN = Y.subtract(wN)
        val K = YminusWN.multiply(x).normalize()

        // 4. 将点 K, X, Y 作为熵输入 HKDF-SHA256 派生会话密钥
        val kBytes = K.getEncoded(false)
        val hkdfInput = kBytes + clientHello + Y.getEncoded(false)

        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(
            HKDFParameters(
                hkdfInput,
                "adb_spake2_salt".toByteArray(Charsets.UTF_8),
                "adb pairing code".toByteArray(Charsets.UTF_8)
            )
        )

        val sessionKey = ByteArray(32) // 256-bit AES Key
        hkdf.generateBytes(sessionKey, 0, 32)
        this.derivedSessionKey = sessionKey
    }

    /**
     * 使用 SPAKE2 协商出的会话密钥对公钥数据进行 AES-GCM 加密 (使用 Java JCE 标准 API)
     */
    public fun encryptPayload(plainData: ByteArray): ByteArray {
        val key = derivedSessionKey ?: throw IllegalStateException("SPAKE2 session key not established")

        val iv = ByteArray(12) // 96-bit GCM IV
        random.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val cipherText = cipher.doFinal(plainData)

        return iv + cipherText
    }

    /**
     * 解密服务端返回的响应 (使用 Java JCE 标准 API)
     */
    public fun decryptPayload(encryptedData: ByteArray): ByteArray {
        val key = derivedSessionKey ?: throw IllegalStateException("SPAKE2 session key not established")
        require(encryptedData.size > 12) { "Invalid encrypted payload length" }

        val iv = encryptedData.copyOfRange(0, 12)
        val cipherText = encryptedData.copyOfRange(12, encryptedData.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        return cipher.doFinal(cipherText)
    }

    /**
     * 计算映射标量 w
     */
    private fun hashToScalar(data: ByteArray): BigInteger {
        val digest = SHA256Digest()
        digest.update(data, 0, data.size)
        val hash = ByteArray(digest.digestSize)
        digest.doFinal(hash, 0)
        return BigInteger(1, hash).mod(q)
    }

    /**
     * 确定性推演 SPAKE2 算法所需的基点 M 和 N
     */
    private fun deriveGeneratorPoint(seed: String): ECPoint {
        val w = hashToScalar(seed.toByteArray(Charsets.UTF_8))
        return G.multiply(w).normalize()
    }
}
