package libs.libs.libs.adb.sync

/**
 * 远程文件或目录状态
 */
public data class FileStat(
    val path: String,
    val mode: Int,
    val size: Int,
    val mtime: Long
) {
    val exists: Boolean get() = mode != 0
    val isDirectory: Boolean get() = (mode and 0x4000) != 0
    val isFile: Boolean get() = (mode and 0x8000) != 0
}
