package libs.libs.libs.adb.connect

import libs.libs.libs.adb.key.AdbKeyManager
import libs.libs.libs.adb.public.AdbCommand
import libs.libs.libs.adb.public.AdbPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

public class AdbConnection(private val keyManager: AdbKeyManager) {

    private val socket = AdbSocket()
    private val localIdGenerator = AtomicInteger(1)

    private val _state = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
    public val state: StateFlow<AdbConnectionState> = _state.asStateFlow()

    private var negotiatedVersion: Int = AdbCommand.A_VERSION

    /**
     * 设备支持的 ADB 特性集合 (如 stat_v2, ls_v2, send_v2, recv_v2, abb, abb_exec 等)
     */
    private var _features: Set<String> = emptySet()
    public val features: Set<String> get() = _features

    /**
     * 检查设备是否支持特定的 ADB Feature
     */
    public fun hasFeature(feature: String): Boolean {
        return _features.contains(feature)
    }

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

            val systemBanner = "$systemIdentity\u0000".toByteArray(Charsets.UTF_8)
            val cnxnPacket = AdbPacket(
                command = AdbCommand.CMD_CNXN,
                arg0 = AdbCommand.A_VERSION_SKIP_CHECKSUM,
                arg1 = AdbCommand.MAX_PAYLOAD,
                payload = systemBanner
            )
            socket.writePacket(cnxnPacket, skipChecksum = true)

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
                                socket.writePacket(authSignaturePacket, skipChecksum = isSkipChecksum)
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
                            socket.writePacket(authPubKeyPacket, skipChecksum = isSkipChecksum)
                            sentPublicKey = true
                        }
                    }

                    else -> {
                        throw IllegalStateException("Unexpected packet during handshake: 0x${Integer.toHexString(response.command)}")
                    }
                }
            }
        } catch (e: Exception) {
            socket.close()
            _state.value = AdbConnectionState.Disconnected
            _state.value = AdbConnectionState.Error(e)
            throw e
        }
    }

    /**
     * 解析握手返回的 Banner 中的 features= 字段
     * Banner 示例: "device::ro.product.name=marlin;features=stat_v2,ls_v2,send_v2,recv_v2,abb,abb_exec"
     */
    private fun parseFeatures(banner: String): Set<String> {
        val featuresSegment = banner.split(';')
            .firstOrNull { it.startsWith("features=") } ?: return emptySet()

        return featuresSegment.removePrefix("features=")
            .split(',')
            .filter { it.isNotBlank() }
            .toSet()
    }

    /**
     * 打开指定 ADB 服务流 (如 "root:", "unroot:", "shell:ls -l", "exec:getprop")
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

        sendPacket(openPacket)

        while (true) {
            val response = receivePacket()
            if (response.arg1 == localId) {
                when (response.command) {
                    AdbCommand.CMD_OKAY -> {
                        val remoteId = response.arg0
                        return@withContext AdbStream(this@AdbConnection, localId, remoteId)
                    }
                    AdbCommand.CMD_CLSE -> {
                        return@withContext null
                    }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    public suspend fun sendPacket(packet: AdbPacket) {
        socket.writePacket(packet, skipChecksum = isSkipChecksum)
    }

    public suspend fun receivePacket(): AdbPacket {
        return socket.readPacket()
    }

    public fun disconnect() {
        socket.close()
        _state.value = AdbConnectionState.Disconnected
    }
}
