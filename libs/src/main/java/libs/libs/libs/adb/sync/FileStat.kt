package libs.libs.libs.adb.sync

public data class FileStat(
    val path: String,
    val mode: Int,
    val size: Long,
    val mtime: Long
) {
    public val exists: Boolean get() = mode != 0
    public val isDirectory: Boolean get() = (mode and 0x4000) != 0
}
