package libs.libs.libs.adb.sync

/**
 * 默认的文件权限模式
 */
public object FilePermissions {
    // 八进制 0100000 (S_IFREG 普通文件) 对应十六进制 0x8000
    public const val S_IFREG: Int = 0x8000

    // 八进制 0644 (rw-r--r--) 对应十六进制 0x01A4 (420)
    // 八进制 0755 (rwxr-xr-x) 对应十六进制 0x01ED (493)
    public const val DEFAULT_MODE: Int = S_IFREG or 0x01A4
}
