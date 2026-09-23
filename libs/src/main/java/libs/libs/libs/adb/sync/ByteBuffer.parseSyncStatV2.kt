package libs.libs.libs.adb.sync

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 解析 Sync v2 响应中的 68 字节 Stat payload
 */
public fun ByteBuffer.parseSyncStatV2(path: String): FileStatV2 {
    order(ByteOrder.LITTLE_ENDIAN)
    val error = int
    val dev = long
    val ino = long
    val mode = int
    val nlink = int
    val uid = int
    val gid = int
    val size = long
    val atime = long
    val mtime = long
    val ctime = long

    return FileStatV2(path, error, dev, ino, mode, nlink, uid, gid, size, atime, mtime, ctime)
}
