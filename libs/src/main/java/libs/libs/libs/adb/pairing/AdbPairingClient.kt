package libs.libs.libs.adb.pairing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import libs.libs.libs.adb.protocol.AdbCrypto
import libs.libs.libs.adb.transport.TlsTransport
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

public class AdbPairingClient(
    public val host: String,
    public val port: Int,
    public val crypto: AdbCrypto
) {

    @OptIn(ExperimentalSerializationApi::class)
    public suspend fun pair(
        pairingCode: String,
        timeoutMs: Int = 10000
    ): Boolean = withContext(Dispatchers.IO) {
        val transport = TlsTransport(host, port, crypto, timeoutMs)

        return@withContext runCatching {
            transport.connect()

            val spake2 = Spake2Engine(pairingCode)
            var sequence = 1

            // -------------------------------------------------------------
            // 1. 发送 SPAKE2_MATTER (交换客户端公钥点 X)
            // -------------------------------------------------------------
            val req1 = PairingPacket(
                type = PairingPacketType.SPAKE2_MATTER.value,
                seq = sequence++,
                payload = spake2.clientPublicKey
            )
            sendFrame(transport, ProtoBuf.encodeToByteArray(PairingPacket.serializer(), req1))

            // -------------------------------------------------------------
            // 2. 接收服务端的 SPAKE2_MATTER (获取服务端公钥点 Y)
            // -------------------------------------------------------------
            val resp1Bytes = readFrame(transport)
            val resp1 = ProtoBuf.decodeFromByteArray(PairingPacket.serializer(), resp1Bytes)

            if (resp1.type == PairingPacketType.SPAKE2_ERROR.value) {
                throw IllegalStateException("配对失败：配对码错误或服务端拒绝")
            }

            val serverPublicKeyY = resp1.payload
            val aesKey = spake2.deriveAesKey(serverPublicKeyY)

            // -------------------------------------------------------------
            // 3. 使用推导出的 AES Key 加密 adbkey.pub 并发送 PAIRING_COMPLETE
            // -------------------------------------------------------------
            val pubKeyBytes = crypto.getAdbPublicKey()
            val encryptedPubKey = spake2.encryptPayload(aesKey, pubKeyBytes)

            val req2 = PairingPacket(
                type = PairingPacketType.PAIRING_COMPLETE.value,
                seq = sequence++,
                payload = encryptedPubKey
            )
            sendFrame(transport, ProtoBuf.encodeToByteArray(PairingPacket.serializer(), req2))

            // -------------------------------------------------------------
            // 4. 接收配对完成响应确认
            // -------------------------------------------------------------
            val resp2Bytes = readFrame(transport)
            val resp2 = ProtoBuf.decodeFromByteArray(PairingPacket.serializer(), resp2Bytes)

            resp2.type == PairingPacketType.PAIRING_COMPLETE.value
        }.onFailure {
            runCatching { transport.close() }
        }.also {
            runCatching { transport.close() }
        }.getOrDefault(false)
    }

    /**
     * 发送 Length-Prefixed 帧：[4 字节 Big-Endian 长度 Header] + [Protobuf 数据包]
     */
    private fun sendFrame(transport: TlsTransport, payload: ByteArray) {
        val header = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(payload.size)
            .array()
        transport.write(header, 0, header.size)
        transport.write(payload, 0, payload.size)
    }

    /**
     * 读取 Length-Prefixed 帧
     */
    private fun readFrame(transport: TlsTransport): ByteArray {
        val lengthBuf = ByteArray(4)
        readFully(transport, lengthBuf)
        val length = ByteBuffer.wrap(lengthBuf).order(ByteOrder.BIG_ENDIAN).int

        if (length <= 0 || length > 10 * 1024 * 1024) { // 防御性上限 10MB
            throw IllegalStateException("无效的数据帧长度: $length")
        }

        val payload = ByteArray(length)
        readFully(transport, payload)
        return payload
    }

    /**
     * 解决 TCP 粘包/分包问题，保证读取完整字节流
     */
    private fun readFully(transport: TlsTransport, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = transport.read(buffer, offset, buffer.size - offset)
            if (read <= 0) {
                throw EOFException("TLS 连接中断 (目标长度: ${buffer.size}, 已读: $offset)")
            }
            offset += read
        }
    }
}
