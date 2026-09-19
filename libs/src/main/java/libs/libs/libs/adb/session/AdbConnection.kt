package libs.libs.libs.adb.session

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libs.libs.libs.adb.protocol.AdbCommand
import libs.libs.libs.adb.protocol.AdbCrypto
import libs.libs.libs.adb.protocol.AdbPacket
import libs.libs.libs.adb.transport.AdbTransport
import libs.libs.libs.adb.transport.TlsTransport
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

public class AdbConnection(
    public val transport: AdbTransport,
    public val crypto: AdbCrypto,
    public val features: String = DEFAULT_FEATURES
) : Closeable {

    public companion object {
        public const val DEFAULT_FEATURES: String =
            "shell_v2,cmd,abb,abb_exec,stat_v2,ls_v2,sendrecv_v2,fixed_push_mkdir"
        public const val DEFAULT_MAX_DATA: Int = 1024 * 1024
    }

    public val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val localIdCounter: AtomicInteger = AtomicInteger(1)
    public val activeStreams: ConcurrentHashMap<Int, AdbStream> = ConcurrentHashMap()

    // 写 Socket 互斥锁，防止多协程并发写导致 ADB 报文交织损坏
    private val writeMutex: Mutex = Mutex()

    // 连接握手 CompletableDeferred，确保 connect() 等到 CNXN 握手成功后才返回
    private val connectionDeferred: CompletableDeferred<Unit> = CompletableDeferred()

    @Volatile
    public var isClosed: Boolean = false
        private set

    public var maxData: Int = 4096
        private set

    public var isAuthSent: Boolean = false
        private set

    /**
     * 建立 ADB 连接并进行 CNXN 握手与认证（挂起直到成功）
     */
    public suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        check(!isClosed) { "AdbConnection 已关闭，无法重新连接" }

        // 启动后台读取循环
        scope.launch { readLoop() }

        // 发送初始 CNXN 报文
        sendCnxnPacket()

        // 挂起等待握手成功或超时
        connectionDeferred.await()
    }

    private suspend fun sendCnxnPacket() {
        val systemProps = "host::$features\u0000".toByteArray(Charsets.UTF_8)
        val protocolVersion = if (transport is TlsTransport) {
            AdbCommand.A_VERSION_SKIP_CHECKSUM
        } else {
            AdbCommand.A_VERSION
        }
        sendPacket(AdbPacket(AdbCommand.CMD_CNXN, protocolVersion, DEFAULT_MAX_DATA, systemProps))
    }

    /**
     * 打开一个新的 ADB Stream（挂起直到收到对端的 CMD_OKAY 确认）
     */
    public suspend fun openStream(destination: String): AdbStream = withContext(Dispatchers.IO) {
        check(!isClosed) { "AdbConnection 已关闭，无法打开 Stream" }

        val localId = localIdCounter.getAndIncrement()
        val stream = AdbStream(localId, 0, this@AdbConnection)
        activeStreams[localId] = stream

        val payload = "$destination\u0000".toByteArray(Charsets.UTF_8)
        sendPacket(AdbPacket(AdbCommand.CMD_OPEN, localId, 0, payload))

        // 等待对端返回 CMD_OKAY 填充 remoteId（若被拒绝会收到 CMD_CLSE 抛出异常）
        stream.awaitOpen()
        return@withContext stream
    }

    /**
     * 线程安全的报文发送逻辑
     */
    public suspend fun sendPacket(packet: AdbPacket): Unit = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext

        writeMutex.withLock {
            transport.write(packet.toHeaderBytes(), 0, AdbPacket.HEADER_SIZE)
            if (packet.payload.isNotEmpty()) {
                transport.write(packet.payload, 0, packet.payload.size)
            }
        }
    }

    public fun unregisterStream(localId: Int) {
        activeStreams.remove(localId)
    }

    /**
     * 循环读取 Socket 数据（修正了网络拆包读取问题）
     */
    private suspend fun readLoop() {
        val headerBuf = ByteArray(AdbPacket.HEADER_SIZE)
        try {
            while (scope.isActive && !isClosed) {
                // 1. 严格读满 24 字节 Header
                readFully(headerBuf, 0, AdbPacket.HEADER_SIZE)
                val header = AdbPacket.parseHeader(headerBuf)

                // 2. 如果有 Payload，严格读满指定长度
                val payload = if (header.dataLength > 0) {
                    ByteArray(header.dataLength).also {
                        readFully(it, 0, header.dataLength)
                    }
                } else {
                    ByteArray(0)
                }

                handleIncomingPacket(AdbPacket(header.command, header.arg0, header.arg1, payload))
            }
        } catch (e: Exception) {
            close()
        }
    }

    /**
     * 保证从 Transport 读满指定的 length 字节（解决 TCP 拆包问题）
     */
    private suspend fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        var totalRead = 0
        while (totalRead < length) {
            val read = transport.read(buffer, offset + totalRead, length - totalRead)
            if (read <= 0) {
                throw EOFException("Transport closed while reading ADB packet (Expected: $length, Read: $totalRead)")
            }
            totalRead += read
        }
    }

    /**
     * 处理收到的 ADB 报文
     */
    public suspend fun handleIncomingPacket(packet: AdbPacket) {
        when (packet.command) {
            AdbCommand.CMD_CNXN -> {
                this.maxData = packet.arg1
                if (!connectionDeferred.isCompleted) {
                    connectionDeferred.complete(Unit)
                }
            }
            AdbCommand.CMD_AUTH -> handleAuth(packet)
            AdbCommand.CMD_STLS -> {
                // 收到 STLS 响应，升级 TLS 握手
                transport.startTls(crypto)
                // 升级完毕后重新发送 CNXN 报文
                sendCnxnPacket()
            }
            AdbCommand.CMD_OKAY -> {
                val localId = packet.arg1
                val remoteId = packet.arg0
                val stream = activeStreams[localId]
                if (stream != null) {
                    if (stream.remoteId == 0) {
                        // 首次建立连接时确认 remoteId 并解除 openStream 挂起
                        stream.onOpened(remoteId)
                    } else {
                        // 数据传输流控 ACK
                        stream.onAckReceived()
                    }
                }
            }
            AdbCommand.CMD_WRTE -> {
                val localId = packet.arg1
                val stream = activeStreams[localId]
                if (stream != null) {
                    // AdbStream.receiveData 内部会自动入队并向设备端发送 CMD_OKAY 触发后续包，无需在此重复发送
                    stream.receiveData(packet.payload)
                }
            }
            AdbCommand.CMD_CLSE -> {
                val localId = packet.arg1
                activeStreams[localId]?.onRemoteClosed()
                activeStreams.remove(localId)
            }
        }
    }

    private suspend fun handleAuth(packet: AdbPacket) {
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

    /**
     * 关闭连接并清理所有关联资源（普通函数，符合 Closeable 规范）
     */
    override fun close() {
        if (isClosed) return
        isClosed = true

        if (!connectionDeferred.isCompleted) {
            connectionDeferred.completeExceptionally(IOException("AdbConnection 已主动关闭"))
        }

        // 通知并关闭所有解绑挂起的 AdbStream
        activeStreams.values.forEach { stream ->
            runCatching { stream.onRemoteClosed() }
        }
        activeStreams.clear()

        // 取消协程作用域并关闭底层传输
        scope.cancel()
        runCatching { transport.close() }
    }
}
