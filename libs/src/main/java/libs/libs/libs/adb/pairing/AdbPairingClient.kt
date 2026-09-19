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

            // 1. 发送 SPAKE2_MATTER (客户端公钥点 X)
            val req1 = PairingPacket(
                type = PairingPacketType.SPAKE2_MATTER.value,
                seq = sequence++,
                payload = spake2.clientPublicKey
            )
            sendFrame(transport, ProtoBuf.encodeToByteArray(PairingPacket.serializer(), req1))

            // 2. 接收服务端的 SPAKE2_MATTER (服务端公钥点 Y)
            val resp1Bytes = readFrame(transport)
            val resp1 = ProtoBuf.decodeFromByteArray(PairingPacket.serializer(), resp1Bytes)

            if (resp1.type == PairingPacketType.SPAKE2_ERROR.value) {
                throw IllegalStateException("服务端拒绝 SPAKE2 握手：配对码可能不匹配")
            }

            val serverPublicKeyY = resp1.payload
            // 派生共享 AES-128 密钥 (16 字节)
            val aesKey = spake2.deriveAesKey(serverPublicKeyY)

            // 3. 构建客户端 PeerInfo 结构体
            val peerInfo = PeerInfo(
                type = 2, // 2 = ADB_CLIENT
                guid = UUID.randomUUID().toString(),
                name = "NekoStudio-Client"
            )
            val peerInfoBytes = ProtoBuf.encodeToByteArray(PeerInfo.serializer(), peerInfo)

            // 使用派生的 AES Key 进行 AES-GCM 加密 (12字节IV + CipherText + 16字节Tag)
            val encryptedPayload = spake2.encryptPayload(aesKey, peerInfoBytes)

            // 4. 发送加密的 PAIRING_COMPLETE 报文
            val req2 = PairingPacket(
                type = PairingPacketType.PAIRING_COMPLETE.value,
                seq = sequence++,
                payload = encryptedPayload
            )
            sendFrame(transport, ProtoBuf.encodeToByteArray(PairingPacket.serializer(), req2))

            // 5. 接收服务端的 PAIRING_COMPLETE 确认报文
            val resp2Bytes = readFrame(transport)
            val resp2 = ProtoBuf.decodeFromByteArray(PairingPacket.serializer(), resp2Bytes)

            if (resp2.type != PairingPacketType.PAIRING_COMPLETE.value) {
                throw IllegalStateException("配对未完成，服务端返回异常类型: ${resp2.type}")
            }

            // 6. 验证服务端的加密 PeerInfo (尝试使用 AES 密钥解密，确保双向认证成功)
            val serverPeerInfoBytes = spake2.decryptPayload(aesKey, resp2.payload)
            val serverPeerInfo = ProtoBuf.decodeFromByteArray(PeerInfo.serializer(), serverPeerInfoBytes)

            // 服务端解密成功且 GUID 不为空，确定配对成功
            serverPeerInfo.guid.isNotEmpty()
        }.onFailure { e ->
            e.printStackTrace()
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

        if (length <= 0 || length > 16 * 1024 * 1024) {
            throw IllegalStateException("收到无效的包长度: $length")
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
                throw EOFException("配对连接意外中断")
            }
            offset += read
        }
    }
}
