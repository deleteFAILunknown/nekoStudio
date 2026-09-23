package libs.libs.libs.adb.key

import org.bouncycastle.crypto.params.RSAPublicKeyParameters
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

public object AdbKeyUtils {

    private const val RSANUMWORDS = 64 // 2048 位 / 32 位

    /**
     * 将 RSA 公钥导出为标准 `adbkey.pub` 格式：
     * "<Base64(524B ADB RSAPublicKey)> <Comment>"
     */
    public fun convertToAdbPublicKeyString(
        pubKeyParams: RSAPublicKeyParameters,
        comment: String = "adb@key"
    ): String {
        val n = pubKeyParams.modulus
        val e = pubKeyParams.exponent

        // 1. 计算 n0inv = -1 / N[0] mod 2^32
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.modInverse(r32).negate().toByteArray()
        var n0invInt = 0
        for (i in 0 until minOf(4, n0inv.size)) {
            n0invInt = (n0invInt or ((n0inv[n0inv.size - 1 - i].toInt() and 0xFF) shl (i * 8)))
        }

        // 2. 计算 rr = (R^2) mod N (R = 2^2048)
        val r = BigInteger.ONE.shiftLeft(2048)
        val rr = r.multiply(r).mod(n)

        // 3. 打包 524 字节的结构体 (小端序)
        // [4B RSANUMWORDS] + [4B n0inv] + [256B Modulus] + [256B R^2] + [4B Exponent]
        val buffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(RSANUMWORDS)
        buffer.putInt(n0invInt)
        buffer.put(toLittleEndianByteArray(n, 256))
        buffer.put(toLittleEndianByteArray(rr, 256))
        buffer.putInt(e.toInt())

        // 4. Base64 编码并拼接 comment
        val base64Key = Base64.getEncoder().encodeToString(buffer.array())
        return "$base64Key$comment"
    }

    private fun toLittleEndianByteArray(bigInt: BigInteger, length: Int): ByteArray {
        val rawBytes = bigInt.toByteArray()
        val result = ByteArray(length)
        var srcPos = 0
        var copyLen = rawBytes.size
        
        // 移除 BigInteger 可能包含的符号位 0x00
        if (rawBytes[0] == 0.toByte()) {
            srcPos = 1
            copyLen--
        }

        // 大端序转小端序
        for (i in 0 until minOf(copyLen, length)) {
            result[i] = rawBytes[srcPos + copyLen - 1 - i]
        }
        return result
    }
}
