package libs.libs.libs.adb.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

public data class AdbPacket(
    public val command: Int,
    public val arg0: Int,
    public val arg1: Int,
    public val payload: ByteArray = ByteArray(0)
) {
    public val dataLength: Int get() = payload.size
    public val dataCrc32: Int get() = checksum(payload)
    public val magic: Int get() = command inv 0

    public fun toHeaderBytes(): ByteArray {
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(command)
            putInt(arg0)
            putInt(arg1)
            putInt(dataLength)
            putInt(dataCrc32)
            putInt(magic)
        }.array()
    }

    public companion object {
        public const val HEADER_SIZE: Int = 24

        public fun checksum(data: ByteArray): Int {
            var sum = 0
            for (b in data) {
                sum += b.toInt() and 0xFF
            }
            return sum
        }

        public fun parseHeader(bytes: ByteArray): HeaderData {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return HeaderData(
                command = buf.int,
                arg0 = buf.int,
                arg1 = buf.int,
                dataLength = buf.int,
                dataCrc32 = buf.int,
                magic = buf.int
            )
        }
    }

    public data class HeaderData(
        public val command: Int,
        public val arg0: Int,
        public val arg1: Int,
        public val dataLength: Int,
        public val dataCrc32: Int,
        public val magic: Int
    )
}
