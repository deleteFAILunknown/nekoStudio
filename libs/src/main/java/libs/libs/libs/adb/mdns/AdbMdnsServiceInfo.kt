package libs.libs.libs.adb.mdns

import java.net.InetAddress

public data class AdbMdnsServiceInfo(
    val name: String,
    val type: AdbMdnsType,
    val host: InetAddress?,
    val port: Int,
    val attributes: Map<String, String> = emptyMap()
) {
    /**
     * 便捷获取 IP 地址字符串
     */
    val ipAddress: String? get() = host?.hostAddress

    override fun toString(): String {
        return "AdbMdnsServiceInfo(name='$name', type=$type, ip=$ipAddress, port=$port)"
    }
}
