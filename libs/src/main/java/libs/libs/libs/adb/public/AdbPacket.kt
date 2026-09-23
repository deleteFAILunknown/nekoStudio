package libs.libs.libs.adb.public

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 代表一个 ADB 协议底层数据包
 */
public data class AdbPacket(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = ByteArray(0)
) {
    val dataLength: Int = payload.size
    val magic: Int = AdbCommand.calculateMagic(command)

    /**
     * 导出为 24 字节 Header + Payload 的完整 Buffer
     */
    public fun toByteArray(skipChecksum: Boolean = false): ByteArray {
        val checksum = if (skipChecksum) 0 else AdbCommand.calculateChecksum(payload)
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(command)
            putInt(arg0)
            putInt(arg1)
            putInt(dataLength)
            putInt(checksum)
            putInt(magic)
            put(payload)
        }
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdbPacket) return false
        return command == other.command && arg0 == other.arg0 && arg1 == other.arg1 && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + arg0
        result = 31 * result + arg1
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        public const val HEADER_SIZE: Int = 24

        /**
         * 从 ByteArray 解析出 Header 结构
         */
        public fun parseHeader(headerBytes: ByteArray): Header {
            require(headerBytes.size >= HEADER_SIZE) { "Header length must be at least 24 bytes" }
            val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            return Header(
                command = buffer.int,
                arg0 = buffer.int,
                arg1 = buffer.int,
                dataLength = buffer.int,
                dataCheck = buffer.int,
                magic = buffer.int
            )
        }
    }

    public data class Header(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val dataLength: Int,
        val dataCheck: Int,
        val magic: Int
    ) {
        val isValid: Boolean get() = (command inv magic) == 0
    }
}
