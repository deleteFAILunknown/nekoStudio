package libs.libs.libs.adb.protocol

public object AdbCommand {
    public val CMD_CNXN: Int = unpack("CNXN")
    public val CMD_AUTH: Int = unpack("AUTH")
    public val CMD_OPEN: Int = unpack("OPEN")
    public val CMD_OKAY: Int = unpack("OKAY")
    public val CMD_WRTE: Int = unpack("WRTE")
    public val CMD_CLSE: Int = unpack("CLSE")

    public const val AUTH_TOKEN: Int = 1
    public const val AUTH_SIGNATURE: Int = 2
    public const val AUTH_RSAPUBLICKEY: Int = 3

    public fun unpack(str: String): Int {
        return (str[0].code) or (str[1].code shl 8) or (str[2].code shl 16) or (str[3].code shl 24)
    }
}
