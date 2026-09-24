package libs.libs.libs.adb.root

/**
 * 代表 adbd 执行 root/unroot 服务的结构化结果
 */
public data class AdbRootResult(
    val status: AdbRootStatus,
    val rawMessage: String
) {
    /**
     * 是否执行成功或已经是 Root 状态
     */
    val isSuccessful: Boolean
        get() = status == AdbRootStatus.RESTARTING_AS_ROOT ||
                status == AdbRootStatus.RESTARTING_AS_SHELL ||
                status == AdbRootStatus.ALREADY_ROOT
}
