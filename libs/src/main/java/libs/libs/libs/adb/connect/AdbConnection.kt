package libs.libs.libs.adb.connect

import libs.libs.libs.adb.key.AdbKeyManager
import libs.libs.libs.adb.public.AdbCommand
import libs.libs.libs.adb.public.AdbPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

public class AdbConnection(private val keyManager: AdbKeyManager) {

    private val socket = AdbSocket()

    private val _state = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
    public val state: StateFlow<AdbConnectionState> = _state.asStateFlow()

    // 记录握手协商后的实际协议版本，默认最小版本
    private var negotiatedVersion: Int = A_VERSION_MIN

    // 是否需要跳过 Checksum 计算
    public val isSkipChecksum: Boolean 
        get() = negotiatedVersion >= A_VERSION_SKIP_CHECKSUM

    companion object {
        public const val A_VERSION_MIN: Int = 0x01000000
        public const val A_VERSION_SKIP_CHECKSUM: Int = 0x01000001
        public const val A_VERSION: Int = 0x01000001 // 默认期望请求的最高版本

        public const val MAX_PAYLOAD: Int = 1048576   // 1MB

        private const val ADB_AUTH_TOKEN = 1
        private const val ADB_AUTH_SIGNATURE = 2
        private const val ADB_AUTH_RSAPUBLICKEY = 3
    }

    /**
     * 发起连接并完成 CNXN / AUTH 握手
     */
    public suspend fun connect(
        host: String,
        port: Int = 5555,
        systemIdentity: String = "host::host_model=NekoStudio;mobile_model=Android;",
        timeoutMs: Int = 10000
    ) = withContext(Dispatchers.IO) {
        try {
            _state.value = AdbConnectionState.Connecting
            socket.connect(host, port, timeoutMs)

            // 1. 发送 CNXN 请求，宣称支持 0x01000001 (A_VERSION_SKIP_CHECKSUM)
            val systemBanner = "$systemIdentity\u0000".toByteArray(Charsets.UTF_8)
            val cnxnPacket = AdbPacket(
                command = AdbCommand.CNXN,
                arg0 = A_VERSION, // 0x01000001
                arg1 = MAX_PAYLOAD,
                payload = systemBanner
            )
            // 握手包 CNXN 本身发送时也遵循 skipChecksum
            socket.writePacket(cnxnPacket, skipChecksum = true)

            var isHandshakeDone = false
            var sentPublicKey = false

            while (!isHandshakeDone) {
                val response = socket.readPacket()

                when (response.command) {
                    AdbCommand.CNXN -> {
                        // 设备端确认 CNXN，response.arg0 即为设备端同意的协议版本
                        negotiatedVersion = response.arg0

                        val banner = String(response.payload, Charsets.UTF_8).trimEnd('\u0000')
                        _state.value = AdbConnectionState.Connected(banner)
                        isHandshakeDone = true
                    }

                    AdbCommand.AUTH -> {
                        _state.value = AdbConnectionState.Authenticating

                        if (response.arg0 == ADB_AUTH_TOKEN) {
                            if (!sentPublicKey) {
                                val signature = keyManager.signToken(response.payload)
                                val authSignaturePacket = AdbPacket(
                                    command = AdbCommand.AUTH,
                                    arg0 = ADB_AUTH_SIGNATURE,
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
                                command = AdbCommand.AUTH,
                                arg0 = ADB_AUTH_RSAPUBLICKEY,
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
            _state.value = AdbConnectionState.Error(e)
            throw e
        }
    }

    /**
     * 发送 ADB 数据包（自动根据协商版本决定是否跳过 Checksum）
     */
    public suspend fun sendPacket(packet: AdbPacket) {
        socket.writePacket(packet, skipChecksum = isSkipChecksum)
    }

    /**
     * 接收 ADB 数据包
     */
    public suspend fun receivePacket(): AdbPacket {
        return socket.readPacket()
    }

    /**
     * 断开连接
     */
    public fun disconnect() {
        socket.close()
        _state.value = AdbConnectionState.Disconnected
    }
}
