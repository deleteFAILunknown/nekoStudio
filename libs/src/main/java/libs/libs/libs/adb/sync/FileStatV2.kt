package libs.libs.libs.adb.sync

/**
 * Sync v2 完整的 64 位文件元数据结构 (68 字节)
 */
public data class FileStatV2(
    val path: String,
    val error: Int,      // 0 表示成功，非 0 为 errno (如 ENOENT = 2)
    val dev: Long,
    val ino: Long,
    val mode: Int,
    val nlink: Int,
    val uid: Int,
    val gid: Int,
    val size: Long,      // 64 位文件大小
    val atime: Long,
    val mtime: Long,
    val ctime: Long
) {
    val exists: Boolean get() = error == 0 && mode != 0
    val isDirectory: Boolean get() = (mode and 0x4000) != 0
    val isFile: Boolean get() = (mode and 0x8000) != 0
}
