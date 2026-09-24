package libs.libs.libs.adb.connect

import libs.libs.libs.adb.public.AdbCommand
import libs.libs.libs.adb.public.AdbPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

public class AdbStream(
    private val connection: AdbConnection,
    public val localId: Int,
    public val remoteId: Int
) {
    private var isClosed = false
    
    // 用于接收中央分发器派发给当前 Stream 的 Packet 队列
    internal val incomingChannel = Channel<AdbPacket>(Channel.UNLIMITED)
    
    // 用于写数据时的流控 Ack 信号（收到 CMD_OKAY 后解除挂起）
    internal val writeAckChannel = Channel<Unit>(Channel.CONFLATED)

    /**
     * 读取对端发送的数据块 (CMD_WRTE)
     */
    public suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext null

        for (packet in incomingChannel) {
            when (packet.command) {
                AdbCommand.CMD_WRTE -> {
                    // 收到 CMD_WRTE 后，必须向对端回复 CMD_OKAY，告知可以继续发送下一块
                    val okayPacket = AdbPacket(
                        command = AdbCommand.CMD_OKAY,
                        arg0 = localId,
                        arg1 = remoteId,
                        payload = ByteArray(0)
                    )
                    connection.sendPacket(okayPacket)
                    return@withContext packet.payload
                }
                AdbCommand.CMD_CLSE -> {
                    closeInternal()
                    return@withContext null
                }
            }
        }
        null
    }

    /**
     * 向流中写入数据（严格遵循流控：发送 CMD_WRTE ➔ 挂起等待 CMD_OKAY）
     */
    public suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        if (isClosed) throw IllegalStateException("AdbStream $localId is closed")

        val writePacket = AdbPacket(
            command = AdbCommand.CMD_WRTE,
            arg0 = localId,
            arg1 = remoteId,
            payload = data
        )
        
        // 1. 发送数据块
        connection.sendPacket(writePacket)

        // 2. 挂起等待对端回复 CMD_OKAY（流控关键）
        val ack = writeAckChannel.receiveCatching()
        if (ack.isFailure || isClosed) {
            throw IllegalStateException("Stream closed while waiting for write ACK")
        }
    }

    /**
     * 关闭流并告知对端
     */
    public suspend fun close() = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext
        closeInternal()

        val closePacket = AdbPacket(
            command = AdbCommand.CMD_CLSE,
            arg0 = localId,
            arg1 = remoteId,
            payload = ByteArray(0)
        )
        try {
            connection.sendPacket(closePacket)
        } catch (_: Exception) {}
    }

    internal fun closeInternal() {
        if (isClosed) return
        isClosed = true
        incomingChannel.close()
        writeAckChannel.close()
        connection.removeStream(localId)
    }
}
