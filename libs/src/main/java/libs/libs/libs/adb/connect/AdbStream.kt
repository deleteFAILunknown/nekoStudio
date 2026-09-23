package libs.libs.libs.adb.connect

import libs.libs.libs.adb.public.AdbCommand
import libs.libs.libs.adb.public.AdbPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public class AdbStream(
    private val connection: AdbConnection,
    public val localId: Int,
    public val remoteId: Int
) {
    private var isClosed = false

    /**
     * 读取对端发送的数据块 (CMD_WRTE)
     */
    public suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext null

        while (true) {
            val packet = connection.receivePacket()

            if (packet.arg1 != localId) continue

            when (packet.command) {
                AdbCommand.CMD_WRTE -> {
                    // 收到 CMD_WRTE 后，必须回复 CMD_OKAY 告之对端可以继续发包
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
        @Suppress("UNREACHABLE_CODE")
        null
    }

    /**
     * 向流中写入数据
     */
    public suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        if (isClosed) throw IllegalStateException("AdbStream is closed")

        val writePacket = AdbPacket(
            command = AdbCommand.CMD_WRTE,
            arg0 = localId,
            arg1 = remoteId,
            payload = data
        )
        connection.sendPacket(writePacket)
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

    private fun closeInternal() {
        isClosed = true
    }
}
