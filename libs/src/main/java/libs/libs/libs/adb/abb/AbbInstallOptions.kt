package libs.libs.libs.adb.abb

public data class AbbInstallOptions(
    val replaceExisting: Boolean = true,         // -r: 覆盖安装
    val allowTestPackages: Boolean = true,       // -t: 允许测试包
    val grantAllPermissions: Boolean = true,     // -g: 自动授予所有运行时权限
    val bypassLowTargetSdkBlock: Boolean = true, // --bypass-low-target-sdk-block (Android 14+)
    val installLocation: Int = 0                 // --install-location: 0=auto, 1=internal, 2=sdcard
) {
    public fun toArgs(includeBypassLowSdk: Boolean = true): List<String> {
        val args = mutableListOf<String>()
        if (replaceExisting) args.add("-r")
        if (allowTestPackages) args.add("-t")
        if (grantAllPermissions) args.add("-g")
        if (bypassLowTargetSdkBlock && includeBypassLowSdk) args.add("--bypass-low-target-sdk-block")
        if (installLocation != 0) {
            args.add("--install-location")
            args.add(installLocation.toString())
        }
        return args
    }
}
