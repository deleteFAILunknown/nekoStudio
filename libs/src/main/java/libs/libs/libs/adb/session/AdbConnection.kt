package libs.libs.libs.adb.session

import kotlinx.coroutines.*
import libs.libs.libs.adb.protocol.AdbCommand
import libs.libs.libs.adb.protocol.AdbCrypto
import libs.libs.libs.adb.protocol.AdbPacket
import libs.libs.libs.adb.transport.AdbTransport
import libs.libs.libs.adb.transport.TlsTransport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

public class AdbConnection(
    public val transport: AdbTransport,
    public val crypto: AdbCrypto
) {
    public val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    public val localIdCounter: AtomicInteger = AtomicInteger(1)
    public val activeStreams: ConcurrentHashMap<Int, AdbStream> = ConcurrentHashMap()
    
    public var maxData: Int = 4096
    public var isAuthSent: Boolean = false

    public suspend fun connect() {
        val systemProps = "host::features=shell_v2,cmd\u0000".toByteArray(Charsets.UTF_8)
        
        val protocolVersion = if (transport is TlsTransport) {
            AdbCommand.A_VERSION_SKIP_CHECKSUM
        } else {
            AdbCommand.A_VERSION
        }

        sendPacket(AdbPacket(AdbCommand.CMD_CNXN, protocolVersion, 1024 * 1024, systemProps))
        scope.launch { readLoop() }
    }

    public suspend fun openStream(destination: String): AdbStream {
        val localId = localIdCounter.getAndIncrement()
        val stream = AdbStream(localId, 0, this)
        activeStreams[localId] = stream

        val payload = "$destination\u0000".toByteArray(Charsets.UTF_8)
        sendPacket(AdbPacket(AdbCommand.CMD_OPEN, localId, 0, payload))
        return stream
    }

    public suspend fun sendPacket(packet: AdbPacket): Unit = withContext(Dispatchers.IO) {
        transport.write(packet.toHeaderBytes(), 0, AdbPacket.HEADER_SIZE)
        if (packet.payload.isNotEmpty()) {
            transport.write(packet.payload, 0, packet.payload.size)
        }
    }

    public fun unregisterStream(localId: Int) {
        activeStreams.remove(localId)
    }

    public suspend fun readLoop() {
        val headerBuf = ByteArray(AdbPacket.HEADER_SIZE)
        while (scope.isActive) {
            val readBytes = transport.read(headerBuf, 0, AdbPacket.HEADER_SIZE)
            if (readBytes < AdbPacket.HEADER_SIZE) break

            val header = AdbPacket.parseHeader(headerBuf)
            val payload = if (header.dataLength > 0) {
                ByteArray(header.dataLength).also { transport.read(it, 0, header.dataLength) }
            } else ByteArray(0)

            handleIncomingPacket(AdbPacket(header.command, header.arg0, header.arg1, payload))
        }
    }

    public suspend fun handleIncomingPacket(packet: AdbPacket) {
        when (packet.command) {
            AdbCommand.CMD_CNXN -> {
                this.maxData = packet.arg1
            }
            AdbCommand.CMD_AUTH -> handleAuth(packet)
            AdbCommand.CMD_STLS -> {
                // 收到 STLS 响应，立即升级传输层 TLS 握手
                transport.startTls(crypto)
                // 升级完毕后，重新以 TLS 免校验版本号建立 CNXN 握手
                val systemProps = "host::features=shell_v2,cmd\u0000".toByteArray(Charsets.UTF_8)
                sendPacket(AdbPacket(AdbCommand.CMD_CNXN, AdbCommand.A_VERSION_SKIP_CHECKSUM, 1024 * 1024, systemProps))
            }
            AdbCommand.CMD_OKAY -> {
                val stream = activeStreams[packet.arg1]
                if (stream != null && stream.remoteId == 0) {
                    stream.remoteId = packet.arg0
                }
            }
            AdbCommand.CMD_WRTE -> {
                activeStreams[packet.arg1]?.receiveData(packet.payload)
            }
            AdbCommand.CMD_CLSE -> {
                activeStreams[packet.arg1]?.onRemoteClosed()
            }
        }
    }

    public suspend fun handleAuth(packet: AdbPacket) {
        if (packet.arg0 == AdbCommand.AUTH_TOKEN) {
            if (!isAuthSent) {
                isAuthSent = true
                val signature = crypto.sign(packet.payload)
                sendPacket(AdbPacket(AdbCommand.CMD_AUTH, AdbCommand.AUTH_SIGNATURE, 0, signature))
            } else {
                val pubKey = crypto.getAdbPublicKey()
                sendPacket(AdbPacket(AdbCommand.CMD_AUTH, AdbCommand.AUTH_RSAPUBLICKEY, 0, pubKey))
            }
        }
    }

    public fun close() {
        scope.cancel()
    }
}
