package libs.libs.libs.adb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kotlinx.coroutines.flow.Flow
import libs.libs.libs.adb.discovery.AdbMdnsDiscoverer
import libs.libs.libs.adb.pairing.AdbPairingClient
import libs.libs.libs.adb.protocol.AdbCrypto
import libs.libs.libs.adb.sync.AdbFileEntry
import libs.libs.libs.adb.services.AdbServices
import libs.libs.libs.adb.services.AdbShellResult
import libs.libs.libs.adb.session.AdbConnection
import libs.libs.libs.adb.transport.AdbTransport
import libs.libs.libs.adb.transport.SocketTransport
import libs.libs.libs.adb.transport.TlsTransport
import libs.libs.libs.adb.transport.UsbAdbDetector
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream

public class Adb(
    public val connection: AdbConnection,
    public val services: AdbServices = AdbServices(connection)
) : Closeable {

    // ================= Shell & Exec 服务门面 =================

    /**
     * 响应式 PTY Shell 交互流 (包含增量 UTF-8 解码防乱码)
     */
    public fun shell(command: String): Flow<String> {
        return services.shell.exec(command)
    }

    /**
     * 执行 Shell 命令并获取 stdout 输出 (优先 Shell V2 协议，解析失败自动降级 V1)
     */
    public suspend fun shellExec(command: String): String {
        return services.shell.execWithResult(command).stdout
    }

    /**
     * 推荐：带 exitCode 和 stderr 的高级 Shell 命令执行
     */
    public suspend fun shellResult(command: String): AdbShellResult {
        return services.shell.execWithResult(command)
    }

    /**
     * 无损/二进制安全 Shell 执行（无 PTY \r\n 转义，适合大文本提取/数据流）
     */
    public suspend fun exec(command: String): String {
        return services.exec.execString(command)
    }

    /**
     * 执行 Android 系统 cmd 服务命令（如 "package list packages"）
     */
    public suspend fun cmd(command: String): String {
        return services.exec.cmd(command)
    }

    // ================= Sync 文件传输门面 =================

    /**
     * 推送本地 File 到设备端 (支持单个文件或文件夹递归推送)
     */
    public suspend fun push(
        localFile: File,
        remotePath: String,
        mode: Int = 33188,
        progress: ((transferred: Long, total: Long) -> Unit)? = null
    ): Boolean {
        return services.sync.push(localFile, remotePath, mode, progress)
    }

    /**
     * 从 InputStream 推送数据流到设备端远程文件 (适合 ContentResolver / Uri 读取)
     */
    public suspend fun push(
        inputStream: InputStream,
        remotePath: String,
        mode: Int = 33188,
        totalSize: Long = -1L,
        progress: ((transferred: Long, total: Long) -> Unit)? = null
    ): Boolean {
        return services.sync.push(inputStream, remotePath, mode, totalSize, progress = progress)
    }

    /**
     * 从设备端拉取文件/目录保存到本地 File
     */
    public suspend fun pull(
        remotePath: String,
        localFile: File,
        progress: ((transferred: Long, total: Long) -> Unit)? = null
    ): Boolean {
        return services.sync.pull(remotePath, localFile, progress)
    }

    /**
     * 从设备端拉取远程文件数据流写入 OutputStream (无临时文件)
     */
    public suspend fun pull(
        remotePath: String,
        outputStream: OutputStream,
        progress: ((transferred: Long, total: Long) -> Unit)? = null
    ): Boolean {
        return services.sync.pull(remotePath, outputStream, progress)
    }

    /**
     * 列出远程目录文件列表
     */
    public suspend fun list(remotePath: String): List<AdbFileEntry> {
        return services.sync.list(remotePath)
    }

    /**
     * 查询远程文件或目录状态
     */
    public suspend fun stat(remotePath: String): AdbFileEntry {
        return services.sync.stat(remotePath)
    }

    // ================= ABB 应用极速安装门面 =================

    /**
     * 安装单包 .apk 文件
     */
    public suspend fun installApp(apkFile: File): String {
        return services.abb.installApp(apkFile)
    }

    /**
     * 安装 .apks (ZIP 格式拆分包)
     * 自动提取压缩包内的所有 .apk 并使用同一 Session 极速写入
     */
    public suspend fun installApks(apksFile: File): String {
        return services.abb.installApks(apksFile)
    }

    /**
     * 安装本地离散的多个拆分 APK 文件 (Multiple Split APKs)
     */
    public suspend fun installSplitApps(apkFiles: List<File>): String {
        return services.abb.installSplitApps(apkFiles)
    }

    /**
     * 执行底层 ABB 命令（参数使用 \u0000 严格分割，优先使用 abb_exec 通道）
     */
    public suspend fun executeAbb(
        args: List<String>,
        inputStream: InputStream? = null
    ): String {
        return services.abb.executeAbb(args, inputStream)
    }

    // ================= 设备状态与控制门面 =================

    /**
     * 重启设备
     * @param target reboot 目标: "" (正常重启), "bootloader", "recovery", "sideload", "download"
     */
    public suspend fun reboot(target: String = ""): Boolean {
        return services.control.reboot(target)
    }

    /**
     * 重挂载 /system, /vendor 等分区为可读写 (Remount)
     */
    public suspend fun remount(): String {
        return services.control.remount()
    }

    /**
     * 切换 adbd 运行在 Root 模式
     */
    public suspend fun root(): String {
        return services.control.root()
    }

    /**
     * 恢复 adbd 为普通 Shell 用户模式 (Unroot)
     */
    public suspend fun unroot(): String {
        return services.control.unroot()
    }

    /**
     * 查询 System Property 属性 (如 "ro.build.version.sdk")
     */
    public suspend fun getProperty(key: String): String {
        return services.control.getProperty(key)
    }

    // ================= 连接控制 =================

    public fun disconnect() {
        connection.close()
    }

    override fun close() {
        disconnect()
    }

    public companion object {

        public var defaultCrypto: AdbCrypto? = null

        public fun initCrypto(context: Context): AdbCrypto {
            val crypto = AdbCrypto.loadOrGenerate(context)
            defaultCrypto = crypto
            return crypto
        }

        private fun getOrCreateCrypto(context: Context): AdbCrypto {
            return defaultCrypto ?: initCrypto(context)
        }

        public suspend fun connectSocket(
            context: Context,
            host: String,
            port: Int = 5555,
            timeoutMs: Int = 10000
        ): Adb {
            val crypto = getOrCreateCrypto(context)
            val transport = SocketTransport(host, port, timeoutMs)
            transport.connect()
            val connection = AdbConnection(transport, crypto)
            connection.connect()
            return Adb(connection)
        }

        public suspend fun connectTlsSocket(
            context: Context,
            host: String,
            port: Int,
            timeoutMs: Int = 10000
        ): Adb {
            val crypto = getOrCreateCrypto(context)
            val transport = TlsTransport(host, port, crypto, timeoutMs)
            transport.connect()
            val connection = AdbConnection(transport, crypto)
            connection.connect()
            return Adb(connection)
        }

        public suspend fun connectUsb(
            context: Context,
            manager: UsbManager,
            device: UsbDevice,
            timeoutMs: Int = 5000
        ): Adb {
            val crypto = getOrCreateCrypto(context)
            val transport = UsbAdbDetector.createTransport(manager, device, timeoutMs)
                ?: throw IllegalStateException("未检测到合法 ADB USB 接口或权限不足")
            val connection = AdbConnection(transport, crypto)
            connection.connect()
            return Adb(connection)
        }

        public suspend fun connectTransport(
            transport: AdbTransport,
            crypto: AdbCrypto
        ): Adb {
            val connection = AdbConnection(transport, crypto)
            connection.connect()
            return Adb(connection)
        }

        public fun discoverDevices(context: Context): Flow<AdbMdnsDiscoverer.DiscoveredService> {
            return AdbMdnsDiscoverer(context).discoverServices()
        }

        public suspend fun pair(
            context: Context,
            host: String,
            port: Int,
            pairingCode: String
        ): Boolean {
            val crypto = getOrCreateCrypto(context)
            val client = AdbPairingClient(host, port, crypto)
            return client.pair(pairingCode)
        }
    }
}
