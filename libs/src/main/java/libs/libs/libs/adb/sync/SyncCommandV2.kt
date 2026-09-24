package libs.libs.libs.adb.sync

import java.nio.ByteBuffer
import java.nio.ByteOrder

public object SyncCommandV2 {
    // Sync v2 指令集
    public const val ID_STA2: String = "STA2" // Stat v2
    public const val ID_LSTA: String = "LSTA" // Lstat v2 (不追踪软链接)
    public const val ID_LST2: String = "LST2" // List v2
    public const val ID_DNT2: String = "DNT2" // Dent v2
    public const val ID_SND2: String = "SND2" // Send v2 (Push)
    public const val ID_RCV2: String = "RCV2" // Recv v2 (Pull)

    /**
     * 构建 Sync V2 (SND2 / RCV2) 请求报文
     * 结构: [8 bytes Sync Header][4 bytes mode][4 bytes flags][4 bytes path_len][path_bytes]
     */
    public fun createRequestV2(
        id: String,
        mode: Int,
        flags: Int,
        remotePath: String
    ): ByteArray {
        val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
        val payloadLen = 12 + pathBytes.size // 4 + 4 + 4 + path_len

        val buffer = ByteBuffer.allocate(SyncCommand.HEADER_SIZE + payloadLen)
            .order(ByteOrder.LITTLE_ENDIAN)

        // 1. 写入 8 字节 Sync Header
        buffer.put(id.toByteArray(Charsets.US_ASCII))
        buffer.putInt(payloadLen)

        // 2. 写入 12 字节 SyncRequestV2 Payload Header
        buffer.putInt(mode)
        buffer.putInt(flags)
        buffer.putInt(pathBytes.size)

        // 3. 写入路径字节
        buffer.put(pathBytes)

        return buffer.array()
    }
}
