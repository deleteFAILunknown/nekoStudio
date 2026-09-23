package libs.libs.libs.adb.public

public object AdbCommand {
    // 基础 ADB 报文指令 (小端序 32 位整型)
    public val CMD_CNXN: Int = unpack("CNXN") // 0x4e584e43
    public val CMD_AUTH: Int = unpack("AUTH") // 0x48545541
    public val CMD_OPEN: Int = unpack("OPEN") // 0x4e45504f
    public val CMD_OKAY: Int = unpack("OKAY") // 0x59414b4f
    public val CMD_WRTE: Int = unpack("WRTE") // 0x45545257
    public val CMD_CLSE: Int = unpack("CLSE") // 0x45534c43
    public val CMD_STLS: Int = unpack("STLS") // 0x534c5453 (Android 11+ TLS 握手指令)

    // AUTH 类型子参数
    public const val AUTH_TOKEN: Int = 1
    public const val AUTH_SIGNATURE: Int = 2
    public const val AUTH_RSAPUBLICKEY: Int = 3

    // ADB 协议版本号
    public const val A_VERSION: Int = 0x01000000 // 基础协议版本 1.0
    public const val A_VERSION_SKIP_CHECKSUM: Int = 0x01000001 // TLS / 跳过 CRC32 校验版本

    // 最大 Payload 限制
    public const val MAX_PAYLOAD: Int = 1024 * 1024 // 现代 ADB 默认为 1MB (早期为 4096)

    public fun unpack(str: String): Int {
        return (str[0].code) or (str[1].code shl 8) or (str[2].code shl 16) or (str[3].code shl 24)
    }

    /**
     * 计算 command 的 magic 值 (command xor 0xFFFFFFFF)
     */
    public fun calculateMagic(command: Int): Int = command.inv()

    /**
     * 计算 payload 的简单累加校验和 (Sum of bytes)
     * 注意：ADB 协议的 Checksum 不是标准 CRC32，而是所有 unsigned byte 的累加和！
     */
    public fun calculateChecksum(payload: ByteArray): Int {
        var sum = 0
        for (b in payload) {
            sum += (b.toInt() and 0xFF)
        }
        return sum
    }
}
