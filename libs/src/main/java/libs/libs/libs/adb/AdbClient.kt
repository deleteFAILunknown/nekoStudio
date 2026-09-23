package libs.libs.libs.adb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import libs.libs.libs.adb.abb.AdbAbbClient
import libs.libs.libs.adb.abb.AbbInstallOptions
import libs.libs.libs.adb.connect.AdbConnection
import libs.libs.libs.adb.connect.AdbConnectionState
import libs.libs.libs.adb.key.AdbKeyManager
import libs.libs.libs.adb.mdns.AdbMdnsManager
import libs.libs.libs.adb.pair.AdbPairingManager
import libs.libs.libs.adb.root.AdbRootClient
import libs.libs.libs.adb.shell.AdbShellClient
import libs.libs.libs.adb.shell.ShellCommandResult
import libs.libs.libs.adb.shell.ShellStreamChunk
import libs.libs.libs.adb.sync.AdbSyncClientV2
import libs.libs.libs.adb.sync.FileStatV2
import libs.libs.libs.adb.usb.accessory.AdbUsbAccessoryManager
import libs.libs.libs.adb.usb.host.AdbUsbHostConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.OptIn
import java.io.File

/**
 * 统一 ADB 客户端门面 (Facade)
 * 整合 Connection、Pair、Shell、ABB、Sync、Root 以及 USB Host/Accessory 模块
 */
@OptIn(ExperimentalSerializationApi::class)
public class AdbClient(
    public val keyManager: AdbKeyManager,
    public val connection: AdbConnection = AdbConnection(keyManager)
) {
    // 1. 核心交互子模块 (持有当前 connection)
    public val shell: AdbShellClient by lazy { AdbShellClient(connection) }
    public val abb: AdbAbbClient by lazy { AdbAbbClient(connection) }
    public val sync: AdbSyncClientV2 by lazy { AdbSyncClientV2(connection) }
    public val rootClient: AdbRootClient by lazy { AdbRootClient(connection) }

    // 2. 配对与 mDNS 搜索子模块
    public val pairingManager: AdbPairingManager by lazy { AdbPairingManager(keyManager) }

    public fun createMdnsManager(context: Context): AdbMdnsManager = AdbMdnsManager(context)

    // 3. USB 扩展模块（按需动态构建）
    public fun createUsbHostConnection(context: Context, device: UsbDevice): AdbUsbHostConnection {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return AdbUsbHostConnection(usbManager, device)
    }

    public fun createUsbHostConnection(usbManager: UsbManager, device: UsbDevice): AdbUsbHostConnection {
        return AdbUsbHostConnection(usbManager, device)
    }

    public fun createUsbAccessoryManager(context: Context): AdbUsbAccessoryManager {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return AdbUsbAccessoryManager(usbManager)
    }

    public fun createUsbAccessoryManager(usbManager: UsbManager): AdbUsbAccessoryManager {
        return AdbUsbAccessoryManager(usbManager)
    }

    // 连接状态与 Feature 观察
    public val state: StateFlow<AdbConnectionState> get() = connection.state
    public val features: Set<String> get() = connection.features
    public fun hasFeature(feature: String): Boolean = connection.hasFeature(feature)

    // =========================================================================
    // 连接与配对 API (直接对接 mdns & pair 模块)
    // =========================================================================

    /**
     * 无线配对 (调用 pair/AdbPairingManager)
     */
    public suspend fun pair(
        host: String,
        port: Int,
        pairingCode: String
    ): Result<Boolean> = runCatching {
        // 如果 pairingManager.pair() 返回 Boolean，使用 runCatching 可以自动将其包裹为 Result.success(Boolean)
        // 若抛出异常则自动捕获并返回 Result.failure(exception)
        pairingManager.pair(host, port, pairingCode)
    }

    /**
     * 连接 TCP 无线/网络设备 (调用 connect/AdbConnection)
     */
    public suspend fun connect(
        host: String,
        port: Int = 5555,
        systemIdentity: String = "host::host_model=NekoStudio;mobile_model=Android;",
        timeoutMs: Int = 10000
    ) {
        connection.connect(host, port, systemIdentity, timeoutMs)
    }

    public fun disconnect() {
        connection.disconnect()
    }

    // =========================================================================
    // 提权与重启 API (直接对接 root 模块)
    // =========================================================================

    public suspend fun getProp(property: String): String {
        return shell.execV2("getprop $property").stdout.trim()
    }

    /**
     * 请求 adbd 以 root 身份重启
     */
    public suspend fun root(): ShellCommandResult {
        val output = rootClient.requestRoot()
        val isSuccess = output.contains("restarting adbd as root", ignoreCase = true) ||
                        output.contains("already running as root", ignoreCase = true)
        
        return ShellCommandResult(
            exitCode = if (isSuccess) 0 else 1,
            stdout = output,
            stderr = if (isSuccess) "" else output
        )
    }

    /**
     * 请求 adbd 恢复为普通权限重启
     */
    public suspend fun unroot(): ShellCommandResult {
        val output = rootClient.requestUnroot()
        val isSuccess = output.contains("restarting adbd as native", ignoreCase = true) ||
                        output.contains("restarting adbd as non-root", ignoreCase = true) ||
                        output.contains("restarting adbd as shell", ignoreCase = true)
        
        return ShellCommandResult(
            exitCode = if (isSuccess) 0 else 1,
            stdout = output,
            stderr = if (isSuccess) "" else output
        )
    }

    public suspend fun reboot(target: String = ""): Boolean {
        val dest = if (target.isBlank()) "reboot:" else "reboot:$target"
        val stream = connection.openStream(dest)
        val success = stream != null
        stream?.close()
        return success
    }

    // =========================================================================
    // 应用安装与传输 API (对接 abb & sync 模块)
    // =========================================================================

    /**
     * 安装 APK（优先走 ABB，不支持则降级走 Sync + Shell pm install）
     */
    public suspend fun installApk(
        apkFile: File,
        options: AbbInstallOptions = AbbInstallOptions(),
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): Result<Unit> {
        require(apkFile.exists()) { "APK file non-existent: ${apkFile.absolutePath}" }

        return if (hasFeature("abb_exec") || hasFeature("abb")) {
            apkFile.inputStream().use { stream ->
                abb.installApk(stream, apkFile.length(), options, onProgress)
            }
        } else {
            runCatching {
                val tempPath = "/data/local/tmp/temp_${System.currentTimeMillis()}.apk"
                apkFile.inputStream().use { sync.push(it, tempPath, apkFile.length(), onProgress) }
                val result = shell.execV2("pm install ${options.toArgs().joinToString(" ")} $tempPath")
                shell.execV2("rm -f $tempPath")
                check(result.isSuccess && result.stdout.contains("Success")) {
                    "Install failed: ${result.stdout} ${result.stderr}"
                }
            }
        }
    }

    public suspend fun pushFile(
        localFile: File,
        remotePath: String,
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = runCatching {
        localFile.inputStream().use { inputStream ->
            sync.push(inputStream, remotePath, localFile.length(), onProgress)
        }
    }

    public suspend fun pullFile(
        remotePath: String,
        localFile: File,
        onProgress: ((read: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = runCatching {
        localFile.outputStream().use { outputStream ->
            sync.pull(remotePath, outputStream, onProgress)
        }
    }

    public suspend fun stat(remotePath: String): FileStatV2 = sync.stat(remotePath)

    public suspend fun listFiles(remotePath: String): List<FileStatV2> = sync.listV2(remotePath)

    // =========================================================================
    // Shell 与日志流 API (对接 shell 模块)
    // =========================================================================

    public fun streamLogcat(args: String = "-v time"): Flow<ShellStreamChunk> {
        return shell.execV2Stream("logcat $args")
    }
}
