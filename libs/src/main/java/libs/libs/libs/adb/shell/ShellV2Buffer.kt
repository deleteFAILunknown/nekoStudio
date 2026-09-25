package libs.libs.libs.adb.shell

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 高性能 Shell V2 拼包缓冲区
 * 采用动态扩容数组与双指针偏移，避免重复内存分配与 O(N^2) 复制开销
 */
public class ShellV2Buffer {
    private var buffer = ByteArray(4096)
    private var head = 0
    private var tail = 0

    val size: Int get() = tail - head

    public fun append(data: ByteArray) {
        ensureCapacity(size + data.size)
        System.arraycopy(data, 0, buffer, tail, data.size)
        tail += data.size
    }

    public fun pollPacket(): ShellV2Packet? {
        if (size < ShellV2Packet.HEADER_SIZE) return null

        val id = buffer[head].toInt() and 0xFF
        val len = ByteBuffer.wrap(buffer, head + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val totalSize = ShellV2Packet.HEADER_SIZE + len

        if (size < totalSize) return null

        val payload = buffer.copyOfRange(head + ShellV2Packet.HEADER_SIZE, head + totalSize)
        head += totalSize

        // 当游标过大且数据已被消费完毕时，重置指针复用内存
        if (head == tail) {
            head = 0
            tail = 0
        } else if (head > 8192 && head > size) {
            // 整理剩余数据回原点
            System.arraycopy(buffer, head, buffer, 0, size)
            tail = size
            head = 0
        }

        return ShellV2Packet(id, payload)
    }

    private fun ensureCapacity(required: Int) {
        if (buffer.size - tail < required - size) {
            var newCap = buffer.size * 2
            while (newCap - size < required) newCap *= 2
            val newBuf = ByteArray(newCap)
            if (size > 0) {
                System.arraycopy(buffer, head, newBuf, 0, size)
            }
            buffer = newBuf
            tail = size
            head = 0
        }
    }
}
