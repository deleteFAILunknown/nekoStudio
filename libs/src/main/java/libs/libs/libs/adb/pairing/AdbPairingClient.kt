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
import java.util.UUID

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

            // 1. 发送 SPAKE2_MATTER (发送客户端公钥点 X)
            val req1 = PairingPacket(
                type = PairingPacketType.SPAKE2_MATTER.value,
                seq = sequence++,
                payload = spake2.clientPublicKey
            )
            sendFrame(transport, ProtoBuf.encodeToByteArray(PairingPacket.serializer(), req1))

            // 2. 接收服务端的 SPAKE2_MATTER (获取服务端公钥点 Y)
            val resp1Bytes = readFrame(transport)
            val resp1 = ProtoBuf.decodeFromByteArray(PairingPacket.serializer(), resp1Bytes)

            if (resp1.type == PairingPacketType.SPAKE2_ERROR.value) {
                throw IllegalStateException("服务端拒绝配对：配对码错误或握手失效")
            }

            val serverPublicKeyY = resp1.payload
            val aesKey = spake2.deriveAesKey(serverPublicKeyY)

            // 3. 构建规范要求的 PeerInfo 结构体 (AOSP adbd 必须校验此结构体)
            val peerInfo = PeerInfo(
                type = 2, // ADB_CLIENT
                guid = UUID.randomUUID().toString(),
                name = "NekoStudio-Client"
            )
            val peerInfoBytes = ProtoBuf.encodeToByteArray(PeerInfo.serializer(), peerInfo)

            // 使用派生的 AES-128 Key 加密 PeerInfo 字节流
            val encryptedPayload = spake2.encryptPayload(aesKey, peerInfoBytes)

            // 4. 发送 PAIRING_COMPLETE 报文
            val req2 = PairingPacket(
                type = PairingPacketType.PAIRING_COMPLETE.value,
                seq = sequence++,
                payload = encryptedPayload
            )
            sendFrame(transport, ProtoBuf.encodeToByteArray(PairingPacket.serializer(), req2))

            // 5. 接收服务端响应，验证确认
            val resp2Bytes = readFrame(transport)
            val resp2 = ProtoBuf.decodeFromByteArray(PairingPacket.serializer(), resp2Bytes)

            resp2.type == PairingPacketType.PAIRING_COMPLETE.value
        }.onFailure {
            runCatching { transport.close() }
        }.also {
            runCatching { transport.close() }
        }.getOrDefault(false)
    }

    private suspend fun sendFrame(transport: TlsTransport, payload: ByteArray) {
        val header = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(payload.size)
            .array()
        transport.write(header, 0, header.size)
        transport.write(payload, 0, payload.size)
    }

    private suspend fun readFrame(transport: TlsTransport): ByteArray {
        val lengthBuf = ByteArray(4)
        readFully(transport, lengthBuf)
        val length = ByteBuffer.wrap(lengthBuf).order(ByteOrder.BIG_ENDIAN).int

        if (length <= 0 || length > 10 * 1024 * 1024) {
            throw IllegalStateException("无效的帧长度: $length")
        }

        val payload = ByteArray(length)
        readFully(transport, payload)
        return payload
    }

    private suspend fun readFully(transport: TlsTransport, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = transport.read(buffer, offset, buffer.size - offset)
            if (read <= 0) {
                throw EOFException("配对 TLS 通道断开")
            }
            offset += read
        }
    }
}
