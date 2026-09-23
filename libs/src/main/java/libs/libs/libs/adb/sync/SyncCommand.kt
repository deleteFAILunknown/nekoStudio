package libs.libs.libs.adb.sync

import java.nio.ByteBuffer

public object SyncCommand {
    public const val HEADER_SIZE: Int = 8

    public const val ID_STAT: String = "STAT"
    public const val ID_SEND: String = "SEND"
    public const val ID_RECV: String = "RECV"
    public const val ID_DATA: String = "DATA"
    public const val ID_DONE: String = "DONE"
    public const val ID_OKAY: String = "OKAY"
    public const val ID_FAIL: String = "FAIL"

    public fun createHeader(id: String, length: Int): ByteArray {
        require(id.length == 4)
        val bytes = ByteArray(8)
        System.arraycopy(id.toByteArray(Charsets.US_ASCII), 0, bytes, 0, 4)
        bytes[4] = (length and 0xFF).toByte()
        bytes[5] = ((length shr 8) and 0xFF).toByte()
        bytes[6] = ((length shr 16) and 0xFF).toByte()
        bytes[7] = ((length shr 24) and 0xFF).toByte()
        return bytes
    }

    public fun parseHeader(bytes: ByteArray): Pair<String, Int> {
        val id = String(bytes, 0, 4, Charsets.US_ASCII)
        val length = (bytes[4].toInt() and 0xFF) or
                ((bytes[5].toInt() and 0xFF) shl 8) or
                ((bytes[6].toInt() and 0xFF) shl 16) or
                ((bytes[7].toInt() and 0xFF) shl 24)
        return Pair(id, length)
    }
}
