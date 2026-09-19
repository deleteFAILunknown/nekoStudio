package libs.libs.libs.adb.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libs.libs.libs.adb.protocol.AdbCommand
import libs.libs.libs.adb.protocol.AdbPacket
import java.io.Closeable
import java.io.IOException

public class AdbStream(
    public val localId: Int,
    public var remoteId: Int = 0,
    private val connection: AdbConnection
) : Closeable {

    // 1. 设置有界通道（capacity = 16），实现真正的 ADB 端到端背压流控
    private val readChannel = Channel<ByteArray>(capacity = 16)
    private val writeMutex = Mutex()
    private val ackChannel = Channel<Unit>(Channel.CONFLATED)

    // 2. 流建立异步确认对象
    private val openDeferred = CompletableDeferred<Unit>()

    @Volatile
    public var isClosed: Boolean = false
        private set

    /**
     * 收到对端首个 CMD_OKAY 时由 AdbConnection 调用，完成 remoteId 绑定
     */
    internal fun onOpened(remoteId: Int) {
        this.remoteId = remoteId
        openDeferred.complete(Unit)
    }

    /**
     * 挂起等待流建立成功
     */
    public suspend fun awaitOpen() {
        openDeferred.await()
    }

    /**
     * 向流通道写入数据（自动分片 + 严格流控 ACK 等待）
     */
    public suspend fun write(data: ByteArray) {
        check(!isClosed) { "AdbStream $localId 已关闭，无法写入" }

        // 挂起等待流建立完成（确保 remoteId 已准备就绪）
        openDeferred.await()

        writeMutex.withLock {
            var offset = 0
            val maxChunk = connection.maxData.coerceAtLeast(1024)

            while (offset < data.size && !isClosed) {
                val length = minOf(maxChunk, data.size - offset)
                val chunk = data.copyOfRange(offset, offset + length)

                // 清空可能残留的旧 ACK，防止信号误触发
                ackChannel.tryReceive()

                // 1. 发送 WRTE 报文
                connection.sendPacket(
                    AdbPacket(AdbCommand.CMD_WRTE, localId, remoteId, chunk)
                )

                // 2. 挂起等待设备端回应 CMD_OKAY
                try {
                    ackChannel.receive()
                } catch (e: Exception) {
                    throw IOException("AdbStream $localId 在等待 ACK 过程中流被关闭", e)
                }

                offset += length
            }
        }
    }

    /**
     * 收到对端发来的数据报文（CMD_WRTE）
     */
    public suspend fun receiveData(payload: ByteArray) {
        if (isClosed) return

        // 1. 压入接收管道（若管道满了会挂起，停止向对端回复 OKAY，从而触发设备端暂停发送）
        readChannel.send(payload)

        // 2. 成功入队后，回复 CMD_OKAY 触发对方继续发送下一包
        connection.sendPacket(
            AdbPacket(AdbCommand.CMD_OKAY, localId, remoteId, ByteArray(0))
        )
    }

    /**
     * 收到对端发送的 ACK 确认（CMD_OKAY）
     */
    public fun onAckReceived() {
        ackChannel.trySend(Unit)
    }

    /**
     * 挂起读取对端发来的数据块，读到 null 表示流已完全关闭且缓冲区已清空
     */
    public suspend fun read(): ByteArray? {
        return readChannel.receiveCatching().getOrNull()
    }

    /**
     * 收到对端的 CMD_CLSE 指令或底层网络断开
     */
    public fun onRemoteClosed() {
        if (isClosed) return
        isClosed = true

        if (!openDeferred.isCompleted) {
            openDeferred.completeExceptionally(IOException("AdbStream 打开请求被对端拒绝或关闭"))
        }

        readChannel.close()
        ackChannel.close()
        connection.unregisterStream(localId)
    }

    /**
     * 本地主动关闭流
     */
    override fun close() {
        if (isClosed) return
        isClosed = true

        if (!openDeferred.isCompleted) {
            openDeferred.completeExceptionally(IOException("AdbStream 在建立前已被本地主动关闭"))
        }

        readChannel.close()
        ackChannel.close()

        connection.scope.launch {
            runCatching {
                connection.sendPacket(
                    AdbPacket(AdbCommand.CMD_CLSE, localId, remoteId, ByteArray(0))
                )
            }
            connection.unregisterStream(localId)
        }
    }
}
