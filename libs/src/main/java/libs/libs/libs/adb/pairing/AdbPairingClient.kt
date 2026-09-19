package libs.libs.libs.adb.pairing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libs.libs.libs.adb.protocol.AdbCrypto
import libs.libs.libs.adb.transport.TlsTransport
import java.nio.ByteBuffer
import java.nio.ByteOrder

public class AdbPairingClient(
    public val host: String,
    public val port: Int,
    public val crypto: AdbCrypto
) {

    public const val PAIRING_REQ_TYPE: Byte = 1
    public const val PAIRING_RESP_TYPE: Byte = 2

    public suspend fun pair(pairingCode: String, timeoutMs: Int = 10000): Boolean = withContext(Dispatchers.IO) {
        val transport = TlsTransport(host, port, crypto, timeoutMs)
        
        return@withContext runCatching {
            transport.connect()

            val pubKeyBytes = crypto.getAdbPublicKey()
            val codeBytes = pairingCode.toByteArray(Charsets.UTF_8)

            val payloadSize = 4 + codeBytes.size + 4 + pubKeyBytes.size
            val packet = ByteBuffer.allocate(1 + 4 + payloadSize).order(ByteOrder.BIG_ENDIAN).apply {
                put(PAIRING_REQ_TYPE)
                putInt(payloadSize)
                
                putInt(codeBytes.size)
                put(codeBytes)
                putInt(pubKeyBytes.size)
                put(pubKeyBytes)
            }.array()

            transport.write(packet, 0, packet.size)

            val responseHeader = ByteArray(5)
            val readBytes = transport.read(responseHeader, 0, 5)

            transport.close()

            if (readBytes >= 5 && responseHeader[0] == PAIRING_RESP_TYPE) {
                val length = ByteBuffer.wrap(responseHeader, 1, 4).order(ByteOrder.BIG_ENDIAN).int
                length >= 0
            } else {
                false
            }
        }.getOrDefault(false)
    }
}
