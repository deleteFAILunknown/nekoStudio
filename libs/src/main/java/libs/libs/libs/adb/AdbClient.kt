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
import libs.libs.libs.adb.pair.AdbPairingListener
import libs.libs.libs.adb.pair.AdbPairingManager
import libs.libs.libs.adb.root.AdbRootClient
import libs.libs.libs.adb.shell.AdbShellClient
import libs.libs.libs.adb.shell.ShellCommandResult
import libs.libs.libs.adb.shell.ShellStreamChunk
import libs.libs.libs.adb.sync.AdbSyncClientV2
import libs.libs.libs.adb.sync.FileStatV2
import libs.libs.libs.adb.sync.SyncFlags
import libs.libs.libs.adb.usb.accessory.AdbUsbAccessoryManager
import libs.libs.libs.adb.usb.host.AdbUsbHostConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.OptIn
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * 统一 ADB 客户端门面 (Facade)
 * 整合 Connection、Pair (SPAKE2)、Shell、ABB、Sync(V2)、Root 以及 USB Host/Accessory 模块
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

    // 连接与配对 API (对接 mdns & pair SPAKE2 模块)

    /**
     * 无线配对 (基于 Android 11+ SPAKE2 / SPAKE2+ 握手协议)
     *
     * @param host 目标设备 IP 地址
     * @param port 设置页面展示的配对端口
     * @param pairingCode 设置页面展示的 6 位数字配对码
     * @param listener 配对回调监听器（可选）
     * @return 返回配对成功的 Result<String>，包含公钥文本
     */
    public suspend fun pair(
        host: String,
        port: Int,
        pairingCode: String,
        listener: AdbPairingListener? = null
    ): Result<String> {
        ensureKeyLoaded()
        return pairingManager.pairWithResult(host, port, pairingCode)
    }

    /**
     * 便捷方法：仅返回配对成功/失败状态的配对方法
     */
    public suspend fun pairSimple(
        host: String,
        port: Int,
        pairingCode: String,
        listener: AdbPairingListener? = null
    ): Result<Boolean> = runCatching {
        ensureKeyLoaded()
        pairingManager.pair(host, port, pairingCode, listener)
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
        ensureKeyLoaded()
        connection.connect(host, port, systemIdentity, timeoutMs)
    }

    public fun disconnect() {
        connection.disconnect()
    }

    /**
     * 检查并确保 RSA 密钥已被加载或自动生成
     */
    private fun ensureKeyLoaded() {
        if (!keyManager.isLoaded) {
            keyManager.generateKeyPair()
        }
    }

    // 提权与重启 API (直接对接 root 模块)

    public suspend fun getProp(property: String): String {
        return shell.execV2("getprop $property").stdout.trim()
    }

    /**
     * 请求 adbd 以 root 身份重启
     */
    public suspend fun root(): ShellCommandResult {
        val result = rootClient.requestRoot()
        return ShellCommandResult(
            exitCode = if (result.isSuccessful) 0 else 1,
            stdout = result.rawMessage,
            stderr = if (result.isSuccessful) "" else result.rawMessage
        )
    }

    /**
     * 请求 adbd 恢复为普通权限重启
     */
    public suspend fun unroot(): ShellCommandResult {
        val result = rootClient.requestUnroot()
        return ShellCommandResult(
            exitCode = if (result.isSuccessful) 0 else 1,
            stdout = result.rawMessage,
            stderr = if (result.isSuccessful) "" else result.rawMessage
        )
    }

    public suspend fun reboot(target: String = ""): Boolean {
        val dest = if (target.isBlank()) "reboot:" else "reboot:$target"
        val stream = connection.openStream(dest)
        val success = stream != null
        stream?.close()
        return success
    }

    // 应用安装与传输 API (对接 abb & sync 模块)

    /**
     * 安装单体 APK（优先走 ABB 极速流，不支持则降级走 Sync V2 + Shell pm install）
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
                apkFile.inputStream().use { sync.pushV2(it, tempPath, apkFile.length(), onProgress = onProgress) }
                val result = shell.execV2("pm install ${options.toArgs().joinToString(" ")} $tempPath")
                shell.execV2("rm -f $tempPath")
                check(result.isSuccess && result.stdout.contains("Success")) {
                    "Install failed: ${result.stdout} ${result.stderr}"
                }
            }
        }
    }

    /**
     * 安装 APKS 应用套件（优先走 ABB 零磁盘极速流，不支持则降级走 Sync V2 + pm install-create/write/commit 会话）
     */
    public suspend fun installApks(
        apksFile: File,
        options: AbbInstallOptions = AbbInstallOptions(),
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): Result<Unit> {
        require(apksFile.exists()) { "APKS file non-existent: ${apksFile.absolutePath}" }

        return if (hasFeature("abb_exec") || hasFeature("abb")) {
            abb.installApks(apksFile, options, onProgress)
        } else {
            runCatching {
                ZipFile(apksFile).use { zip ->
                    val apkEntries = zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                        .toList()

                    check(apkEntries.isNotEmpty()) { "No .apk files found in ${apksFile.name}" }

                    val totalBytes = apkEntries.sumOf { it.size }
                    val createResult = shell.execV2("pm install-create -S $totalBytes ${options.toArgs().joinToString(" ")}")
                    check(createResult.isSuccess) { "Failed to create install session: ${createResult.stderr}" }

                    val sessionId = Regex("""\[(\d+)]""").find(createResult.stdout)?.groupValues?.get(1)
                        ?: throw IllegalStateException("Failed to parse session ID from: ${createResult.stdout}")

                    var globalWritten = 0L

                    try {
                        apkEntries.forEachIndexed { index, entry ->
                            val splitName = entry.name.substringAfterLast('/')
                            val tempPath = "/data/local/tmp/temp_split_${index}_${System.currentTimeMillis()}.apk"

                            zip.getInputStream(entry).use { inputStream ->
                                sync.pushV2(
                                    inputStream = inputStream,
                                    remotePath = tempPath,
                                    totalSize = entry.size,
                                    onProgress = { read, _ ->
                                        onProgress?.invoke(globalWritten + read, totalBytes)
                                    }
                                )
                            }
                            globalWritten += entry.size

                            val writeResult = shell.execV2("pm install-write -S ${entry.size} $sessionId $splitName $tempPath")
                            shell.execV2("rm -f $tempPath")
                            check(writeResult.isSuccess) { "Failed to write split $splitName: ${writeResult.stderr}" }
                        }

                        val commitResult = shell.execV2("pm install-commit $sessionId")
                        check(commitResult.isSuccess && commitResult.stdout.contains("Success")) {
                            "Failed to commit session $sessionId: ${commitResult.stdout}${commitResult.stderr}"
                        }
                    } catch (e: Exception) {
                        shell.execV2("pm install-abandon $sessionId")
                        throw e
                    }
                }
            }
        }
    }

    /**
     * 流式安装 Split APKs 套件（底层透传给 AdbAbbClient）
     */
    public suspend fun installSplitApks(
        apks: Map<String, Pair<InputStream, Long>>,
        options: AbbInstallOptions = AbbInstallOptions()
    ): Result<Unit> {
        return abb.installSplitApks(apks, options)
    }

    /**
     * 推送文件到远程设备 (优先使用 Sync V2 SND2，不支持时自动退回 Sync V1 SEND)
     */
    public suspend fun pushFile(
        localFile: File,
        remotePath: String,
        flags: Int = SyncFlags.FLAG_NONE,
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = runCatching {
        localFile.inputStream().use { inputStream ->
            sync.pushV2(
                inputStream = inputStream,
                remotePath = remotePath,
                totalSize = localFile.length(),
                flags = flags,
                onProgress = onProgress
            )
        }
    }

    /**
     * 从远程设备拉取文件 (优先使用 Sync V2 RCV2，不支持时自动退回 Sync V1 RECV)
     */
    public suspend fun pullFile(
        remotePath: String,
        localFile: File,
        flags: Int = SyncFlags.FLAG_NONE,
        onProgress: ((read: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = runCatching {
        localFile.outputStream().use { outputStream ->
            sync.pullV2(
                remotePath = remotePath,
                outputStream = outputStream,
                flags = flags,
                onProgress = onProgress
            )
        }
    }

    /**
     * 获取文件完整属性 (优先使用 Sync V2 STA2，不支持时自动由 V1 STAT 转换补全)
     */
    public suspend fun stat(remotePath: String): FileStatV2 = sync.statV2(remotePath)

    /**
     * 列出目录文件列表 (优先使用 Sync V2 LST2，不支持时自动由 V1 LIST 转换补全)
     */
    public suspend fun listFiles(remotePath: String): List<FileStatV2> = sync.listV2(remotePath)

    // Shell 与日志流 API (对接 shell 模块)

    public fun streamLogcat(args: String = "-v time"): Flow<ShellStreamChunk> {
        return shell.execV2Stream("logcat $args")
    }
}
