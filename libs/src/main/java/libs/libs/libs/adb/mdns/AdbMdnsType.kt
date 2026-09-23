package libs.libs.libs.adb.mdns

public enum class AdbMdnsType(public val rawType: String) {
    /**
     * 配对服务 (_adb-tls-pairing._tcp.)
     * 用于 Android 11+ 的 6 位验证码无线配对
     */
    PAIRING("_adb-tls-pairing._tcp."),

    /**
     * TLS 连接服务 (_adb-tls-connect._tcp.)
     * 用于 Android 11+ 已配对设备后的加密连接
     */
    CONNECT("_adb-tls-connect._tcp."),

    /**
     * 传统 ADB over Wi-Fi 服务 (_adb._tcp.)
     * 对应传统的 5555 端口明文连接
     */
    LEGACY("_adb._tcp.");

    companion object {
        public fun fromRawType(rawType: String): AdbMdnsType? {
            val normalized = if (rawType.endsWith(".")) rawType else "$rawType."
            return values().firstOrNull { it.rawType.equals(normalized, ignoreCase = true) }
        }
    }
}
