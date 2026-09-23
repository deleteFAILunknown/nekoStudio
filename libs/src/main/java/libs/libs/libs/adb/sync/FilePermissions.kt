package libs.libs.libs.adb.sync

/**
 * 默认的文件权限模式 (Linux 0644 S_IFREG)
 */
public object FilePermissions {
    public const val S_IFREG: Int = 0100000 // 普通文件
    public const val DEFAULT_MODE: Int = S_IFREG or 0644
}
