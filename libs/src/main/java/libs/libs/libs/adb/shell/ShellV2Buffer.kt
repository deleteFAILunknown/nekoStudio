package libs.libs.libs.adb.shell

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 拼包缓冲区：处理 ADB Stream 多包/半包粘包问题
 */
public class ShellV2Buffer {
    private var buffer = ByteArray(0)

    public fun append(data: ByteArray) {
        buffer += data
    }

    public fun pollPacket(): ShellV2Packet? {
        if (buffer.size < ShellV2Packet.HEADER_SIZE) return null

        val id = buffer[0].toInt() and 0xFF
        val len = ByteBuffer.wrap(buffer, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val totalSize = ShellV2Packet.HEADER_SIZE + len

        if (buffer.size < totalSize) return null

        val payload = buffer.copyOfRange(ShellV2Packet.HEADER_SIZE, totalSize)
        buffer = buffer.copyOfRange(totalSize, buffer.size)

        return ShellV2Packet(id, payload)
    }
}
