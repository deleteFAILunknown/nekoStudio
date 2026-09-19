package libs.libs.libs.adb.protocol

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

    public fun unpack(str: String): Int {
        return (str[0].code) or (str[1].code shl 8) or (str[2].code shl 16) or (str[3].code shl 24)
    }
}
