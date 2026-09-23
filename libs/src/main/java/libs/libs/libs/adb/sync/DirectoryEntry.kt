package libs.libs.libs.adb.sync

/**
 * 目录列表子项
 */
public data class DirectoryEntry(
    val name: String,
    val mode: Int,
    val size: Int,
    val mtime: Long
) {
    val isDirectory: Boolean get() = (mode and 0x4000) != 0
    val isFile: Boolean get() = (mode and 0x8000) != 0
}
