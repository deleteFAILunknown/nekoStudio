package libs.libs.libs.adb.connect

import libs.libs.libs.adb.key.AdbKeyManager
import libs.libs.libs.adb.public.AdbCommand
import libs.libs.libs.adb.public.AdbPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

public class AdbConnection(private val keyManager: AdbKeyManager) {

    private val socket = AdbSocket()
    private val localIdGenerator = AtomicInteger(1)
    
    // 底层 Socket 写入锁，保证并发 writePacket 时的字节流顺序完整
    private val writeMutex = Mutex()

    private val _state = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
    public val state: StateFlow<AdbConnectionState> = _state.asStateFlow()

    private var negotiatedVersion: Int = AdbCommand.A_VERSION

    private var _features: Set<String> = emptySet()
    public val features: Set<String> get() = _features

    // 管理活动中的 Stream 映射表 (localId -> AdbStream)
    private val activeStreams = ConcurrentHashMap<Int, AdbStream>()
    
    // 监听 openStream 等待 RESP 的通道
    private val pendingOpenRequests = ConcurrentHashMap<Int, kotlinx.coroutines.channels.Channel<AdbPacket>>()

    private var dispatchJob: Job? = null
    private val connectionScope = CoroutineScope(Dispatchers.IO)

    public fun hasFeature(feature: String): Boolean = _features.contains(feature)

    public val isSkipChecksum: Boolean 
        get() = negotiatedVersion >= AdbCommand.A_VERSION_SKIP_CHECKSUM

    public suspend fun connect(
        host: String,
        port: Int = 5555,
        systemIdentity: String = "host::host_model=NekoStudio;mobile_model=Android;",
        timeoutMs: Int = 10000
    ) = withContext(Dispatchers.IO) {
        try {
            _state.value = AdbConnectionState.Connecting
            socket.connect(host, port, timeoutMs)

            // 1. 发送 CNXN 握手
            val systemBanner = "$systemIdentity\u0000".toByteArray(Charsets.UTF_8)
            val cnxnPacket = AdbPacket(
                command = AdbCommand.CMD_CNXN,
                arg0 = AdbCommand.A_VERSION_SKIP_CHECKSUM,
                arg1 = AdbCommand.MAX_PAYLOAD,
                payload = systemBanner
            )
            sendPacket(cnxnPacket)

            // 2. 握手 & RSA 鉴权阶段（此时尚未启动 Dispatcher，单线程同步读取）
            var isHandshakeDone = false
            var sentPublicKey = false

            while (!isHandshakeDone) {
                val response = socket.readPacket()

                when (response.command) {
                    AdbCommand.CMD_CNXN -> {
                        negotiatedVersion = response.arg0
                        val banner = String(response.payload, Charsets.UTF_8).trimEnd('\u0000')
                        _features = parseFeatures(banner)
                        _state.value = AdbConnectionState.Connected(banner)
                        isHandshakeDone = true
                    }

                    AdbCommand.CMD_AUTH -> {
                        _state.value = AdbConnectionState.Authenticating

                        if (response.arg0 == AdbCommand.AUTH_TOKEN) {
                            if (!sentPublicKey) {
                                val signature = keyManager.signToken(response.payload)
                                val authSignaturePacket = AdbPacket(
                                    command = AdbCommand.CMD_AUTH,
                                    arg0 = AdbCommand.AUTH_SIGNATURE,
                                    arg1 = 0,
                                    payload = signature
                                )
                                sendPacket(authSignaturePacket)
                            } else {
                                throw IllegalStateException("ADB Authorization rejected by device.")
                            }
                        } else {
                            val pubKeyBytes = keyManager.getAdbPublicKeyBytes()
                            val authPubKeyPacket = AdbPacket(
                                command = AdbCommand.CMD_AUTH,
                                arg0 = AdbCommand.AUTH_RSAPUBLICKEY,
                                arg1 = 0,
                                payload = pubKeyBytes
                            )
                            sendPacket(authPubKeyPacket)
                            sentPublicKey = true
                        }
                    }

                    else -> {
                        throw IllegalStateException("Unexpected packet during handshake: 0x${Integer.toHexString(response.command)}")
                    }
                }
            }

            // 3. 握手成功后，启动后台解复用分发器 Loop
            startDispatchLoop()

        } catch (e: Exception) {
            disconnect()
            _state.value = AdbConnectionState.Error(e)
            throw e
        }
    }

    /**
     * 中央包解复发器：单协程轮询 Socket 并将包分发到对应的 Stream
     */
    private fun startDispatchLoop() {
        dispatchJob?.cancel()
        dispatchJob = connectionScope.launch {
            try {
                while (socket.isConnected) {
                    val packet = socket.readPacket()
                    val targetLocalId = packet.arg1

                    // A. 处理等待 openStream 的响应包 (CMD_OKAY / CMD_CLSE)
                    val pendingChannel = pendingOpenRequests[targetLocalId]
                    if (pendingChannel != null) {
                        pendingChannel.send(packet)
                        continue
                    }

                    // B. 处理已知活动 Stream 的数据包
                    val stream = activeStreams[targetLocalId]
                    if (stream != null) {
                        when (packet.command) {
                            AdbCommand.CMD_OKAY -> {
                                // 收到写确认 ACK，通知 AdbStream.write() 继续
                                stream.writeAckChannel.trySend(Unit)
                            }
                            AdbCommand.CMD_WRTE, AdbCommand.CMD_CLSE -> {
                                stream.incomingChannel.send(packet)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Socket 发生断开或重置，关闭所有关联 Stream
                activeStreams.values.forEach { it.closeInternal() }
                activeStreams.clear()
                _state.value = AdbConnectionState.Disconnected
            }
        }
    }

    private fun parseFeatures(banner: String): Set<String> {
        val featuresSegment = banner.split(';')
            .firstOrNull { it.startsWith("features=") } ?: return emptySet()

        return featuresSegment.removePrefix("features=")
            .split(',')
            .filter { it.isNotBlank() }
            .toSet()
    }

    /**
     * 打开指定的 ADB 服务通道
     */
    public suspend fun openStream(destination: String): AdbStream? = withContext(Dispatchers.IO) {
        val localId = localIdGenerator.getAndIncrement()

        val destBytes = if (destination.endsWith("\u0000")) {
            destination.toByteArray(Charsets.UTF_8)
        } else {
            "$destination\u0000".toByteArray(Charsets.UTF_8)
        }

        val openPacket = AdbPacket(
            command = AdbCommand.CMD_OPEN,
            arg0 = localId,
            arg1 = 0,
            payload = destBytes
        )

        // 创建临时等待 Channel
        val openChannel = kotlinx.coroutines.channels.Channel<AdbPacket>(1)
        pendingOpenRequests[localId] = openChannel

        try {
            sendPacket(openPacket)

            // 等待中央分发器投递 CMD_OKAY 或 CMD_CLSE
            val response = openChannel.receive()

            if (response.command == AdbCommand.CMD_OKAY) {
                val remoteId = response.arg0
                val stream = AdbStream(this@AdbConnection, localId, remoteId)
                activeStreams[localId] = stream
                return@withContext stream
            } else {
                return@withContext null
            }
        } finally {
            pendingOpenRequests.remove(localId)
        }
    }

    internal fun removeStream(localId: Int) {
        activeStreams.remove(localId)
    }

    /**
     * 线程安全的写包方法（受 Mutex 保护）
     */
    public suspend fun sendPacket(packet: AdbPacket) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            socket.writePacket(packet, skipChecksum = isSkipChecksum)
        }
    }

    public fun disconnect() {
        dispatchJob?.cancel()
        dispatchJob = null
        activeStreams.values.forEach { it.closeInternal() }
        activeStreams.clear()
        pendingOpenRequests.clear()
        socket.close()
        _state.value = AdbConnectionState.Disconnected
    }
}
