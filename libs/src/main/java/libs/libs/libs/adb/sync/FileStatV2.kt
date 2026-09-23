package libs.libs.libs.adb.sync

public data class FileStatV2(
    val path: String,
    val error: Int,
    val dev: Long,
    val ino: Long,
    val mode: Int,
    val nlink: Int,
    val uid: Int,
    val gid: Int,
    val size: Long,
    val atime: Long,
    val mtime: Long,
    val ctime: Long
) {
    public val exists: Boolean get() = error == 0
    public val isDirectory: Boolean get() = (mode and 0x4000) != 0
}
