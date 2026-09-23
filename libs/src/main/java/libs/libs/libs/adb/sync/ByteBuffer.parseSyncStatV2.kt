package libs.libs.libs.adb.sync

public fun ByteBuffer.parseSyncStatV2(path: String): FileStatV2 {
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

    return FileStatV2(
        path = path, error = error, dev = dev, ino = ino,
        mode = mode, nlink = nlink, uid = uid, gid = gid,
        size = size, atime = atime, mtime = mtime, ctime = ctime
    )
}
