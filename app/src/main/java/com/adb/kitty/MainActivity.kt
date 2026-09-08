package com.adb.kitty

import android.*
import android.util.*
import android.content.pm.*
import android.graphics.*
import android.animation.*
import android.provider.*
import android.media.*
import android.app.PendingIntent
import android.annotation.SuppressLint

import android.os.*
import android.view.*
import android.widget.*
import android.webkit.*
import android.content.*
import android.content.res.Configuration
import android.hardware.usb.*

import android.net.*
import android.net.wifi.*
import android.net.nsd.*
import android.text.method.*

import androidx.core.view.*
import androidx.core.content.*
import androidx.core.net.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.window.layout.WindowMetricsCalculator
/*******************************
*        kotlinx 协程         *
*    suspend 都给我挂起     *
********************************/
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*

import kotlin.*
import kotlin.coroutines.*
import kotlin.math.*
import kotlin.system.*

import java.io.*
import java.nio.*
import java.security.*
import java.text.*
import java.net.*
import java.util.*
import java.util.zip.*
import java.time.*
import java.time.format.*
import javax.crypto.*
import javax.net.ssl.*
import okio.*
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.shell.*
import org.json.*

import androidx.annotation.*
import androidx.activity.*
import androidx.activity.compose.*
import androidx.activity.result.*
import androidx.activity.result.contract.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.*
import androidx.lifecycle.viewmodel.internal.*
import androidx.lifecycle.viewmodel.compose.*
import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.text.*
import androidx.compose.foundation.text.selection.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.viewinterop.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.ui.it.*
import com.adb.kitty.ui.it.help.*
import com.adb.kitty.ui.it.cpu.*
import com.adb.kitty.data.*
import com.adb.kitty.data.help.*
import com.adb.kitty.service.*
import com.adb.kitty.R

@Keep
class MainActivity : ComponentActivity() {
    companion object {
        private const val REQUEST_WIFI_PERMISSION_CODE = 1001
        private const val PREFS_NAME = "adb_kitty_prefs"
        private const val KEY_DEVICE_LIST = "connected_devices"
    }
    private val viewModel: MainActivityViewModel by viewModels()
    private val pviewModel: PerformanceViewModel by viewModels()
    private lateinit var usbManager: UsbManager
    private lateinit var keyManager: AdbKeyManager
    private val ACTION_USB_PERMISSION = "com.adb.kitty.USB_PERMISSION"

    private var usbConn: UsbDeviceConnection? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var readerJob: Job? = null
    private var usbForwarder: UsbPortForwarder? = null

    private var isUsbAttached = false
    private var isAdbAuthorized = false
    private var isFastbootMode = false
    private var isWifiEnabled: Boolean = false

    private val responseChannel = Channel<String>(Channel.UNLIMITED)
    
    private var showAppSigBottomSheet by mutableStateOf(false)
    private var selectedSigReport by mutableStateOf<String?>(null)
    
    // adb 与 fastboot 使用的路径
    private val flashFolder by lazy { File(getExternalFilesDir(null), "flash") }
    private fun ensureFlashDirExists() {
        if (!flashFolder.exists()) {
            flashFolder.mkdirs()
        }
    }

    // 日志文件输出专用路径
    private val logsFolder by lazy { File(externalCacheDir, "logs") }
    private fun ensureLogsDirExists() {
        if (!logsFolder.exists()) {
            logsFolder.mkdirs()
        }
    }
    
    private var pendingCsvContent: String? = null

    // 注册系统 SAF 存储选择器，用于导出 CSV
    private val createCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let { destUri ->
            pendingCsvContent?.let { csvData ->
                try {
                    contentResolver.openOutputStream(destUri)?.use { output ->
                        output.write(csvData.toByteArray())
                    }
                    Toast.makeText(this, "文件导出成功！", Toast.LENGTH_SHORT).show()
                    pviewModel.clearExportData()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this, "导出失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    val turbo by lazy { PerformanceTurbo(this) }
    
    var showDeviceListBottomSheet = mutableStateOf(false)
    var matchedDevicesList = mutableStateListOf<AdbDevice>()
    
    var qrCodeDialogContent by mutableStateOf<String?>(null)
    var qrDecodeResult by mutableStateOf<String?>(null)
    
    var adbService: AdbSessionService? = null
    private var isServiceBound = false
    private var isBindingRequested = false
    private var kadbInstance: Kadb?
        get() = if (isServiceBound) adbService?.globalKadbInstance else null
        set(value) { if (isServiceBound) adbService?.globalKadbInstance = value }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AdbSessionService.AdbBinder
            adbService = binder.getService()
            val cmdsService = binder.getService()
            isServiceBound = true
            appendLog("[系统] 前台物理守护进程并网成功。")
            viewModel.setAdbService(adbService)
            cmdsService.onCommandReceivedListener = { cmd ->
                cmdsServiceExec(cmd)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            adbService?.onCommandReceivedListener = null
            isServiceBound = false
            isBindingRequested = false
            adbService = null
            viewModel.setAdbService(null)
        }
    }
    
    private var currentShellJob: Job? = null
    
    fun reloadServiceAvatar() {
        adbService?.reloadAvatar()
    }
    
    fun cmdsServiceExec(cmd: String) {
        dispatchCommandRoute(cmd)
    }

    // 桥接方法：将 Activity 内的所有日志无缝灌入 ViewModel
    fun appendLog(msg: String) {
        runOnUiThread {
            viewModel.appendLog(msg)
        }
    }
    
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            appendLog("[权限] Android 13+ 通知权限授权成功，正在激活前台服务…")
            startAndBindAdbService()
        } else {
            handlePermissionDeniedSituation()
            startAndBindAdbService()
        }
    }
    
    private val requestNetworkPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val isWifiScanGranted = permissions[getWifiScanPermission()] ?: true
        val isLocalNetworkGranted = if (Build.VERSION.SDK_INT >= 37) {
            permissions["android.permission.ACCESS_LOCAL_NETWORK"] ?: false
        } else {
            true
        }
        if (isWifiScanGranted && isLocalNetworkGranted) {
            appendLog("[INFO] Wi-Fi 所需权限已授予，已具备激活无线链路条件")
        } else {
            appendLog("[警告] 权限被拒绝，无法自动扫描 Wi-Fi SSID")
        }
    }
    
    private val allFilesPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            appendLog("[INFO] Android 11+ 所有文件访问权限已授权")
        } else {
            appendLog("[警告] Android 11+ 所有文件访问权限未授权")
        }
    }

    private val legacyStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val isAllGranted = permissionsMap.values.all { it }
        if (isAllGranted) {
            appendLog("[INFO] Android 10 文件读写权限已授权")
        } else {
            appendLog("[警告] Android 10 文件读写权限未授权")
        }
    }
    
    private var pendingAudioBaseName: String = ""

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            appendLog("[系统] 已成功选取视频，正在分析轨道并提取音频...")
        
            lifecycleScope.launch {
                val resultUri = extractAudioToMusicDirectory(
                    context = this@MainActivity,
                    videoUri = uri,
                    baseFileName = pendingAudioBaseName,
                    onProgress = { progress ->
                        val percent = (progress * 100).toInt()
                    }
                )

                if (resultUri != null) {
                    appendLog("[成功] 音频提取完成！已安全保存至系统的【音乐(Music)/NekoExtractor】目录")
                } else {
                    appendLog("[错误] 音频提取失败！可能视频中不包含有效的音频流，或多媒体架构初始化异常。")
                }
            }
        } else {
            appendLog("[警告] 用户取消了视频选取。")
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        appendLog("[系统] USB 调试设备权限获取成功")
                        connectToInterface(device)
                    }
                } else {
                    appendLog("[警告] 用户拒绝了 USB 权限申请")
                }
            }
        }
    }

    private val usbStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    isUsbAttached = true
                    findHostDevice()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED,
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    isUsbAttached = false
                    isAdbAuthorized = false
                    isFastbootMode = false
                    readerJob?.cancel()
                    usbConn?.close()
                    appendLog("[警告] USB 设备已断开")
                }
            }
        }
    }

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                val wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                val oldState = isWifiEnabled
                when (wifiState) {
                    WifiManager.WIFI_STATE_ENABLED -> {
                        isWifiEnabled = true
                        appendLog("[INFO] ⏳ WLAN 已开启")
                        if (!oldState) handleWifiConnectionFlow()
                    }
                    WifiManager.WIFI_STATE_DISABLED -> {
                        isWifiEnabled = false
                        appendLog("[警告] ⏳ WLAN 已关闭")
                        handleWifiConnectionFlow()
                    }
                }
            }
        }
    }
    
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    appendLog("[电源] 🔌 充电器已插入")
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    appendLog("[电源] 🔋 充电器已拔出")
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                Color.Transparent.toArgb(), 
                Color.Transparent.toArgb()
            ),
            navigationBarStyle = SystemBarStyle.auto(
                Color.Transparent.toArgb(), 
                Color.Transparent.toArgb()
            )
        )
        setContent {
            val useDynamicColor by viewModel.useDynamicColor.collectAsStateWithLifecycle()
            var showIntentBottomSheet by remember { mutableStateOf(false) }
            var selectedSigReport by remember { mutableStateOf<String?>(null) }
            var showHelpBottomSheet by remember { mutableStateOf(false) }
            var selectedSchemeText by remember { mutableStateOf("") }

            val puiState by pviewModel.uiState.collectAsStateWithLifecycle()
            var showCpuBottomSheet by remember { mutableStateOf(false) }

            var showCpuHistoryBottomSheet by remember { mutableStateOf(false) }

            NekoTheme(dynamicColor = useDynamicColor) {
                CenterAlignedTopAppBarExample(
                    viewModel = viewModel,
                    activity = this@MainActivity,
                    onExecuteCommand = { cmd -> 
                        dispatchCommandRoute(cmd)
                    },
                    onIntentBottomSheet = { 
                        showIntentBottomSheet = true
                    },
                    onHelpBottomSheet = { 
                        showHelpBottomSheet = true
                    },
                    onCpuBottomSheet = { 
                        showCpuBottomSheet = true
                    },
                    onCpuHistoryBottomSheet = { 
                        showCpuHistoryBottomSheet = true
                    }
                )

                if (showDeviceListBottomSheet.value) {
                    DeviceSelectionBottomSheet(
                        wifiName = getCurrentWifiSsid(),
                        devices = matchedDevicesList,
                        onDeviceSelected = { selectedDevice ->
                            appendLog("[INFO] 用户从底栏选择了设备: ${selectedDevice.ip}:${selectedDevice.port}")
                            lifecycleScope.launch(Dispatchers.IO) {
                                handleLocalAdbConnect("adb connect ${selectedDevice.ip}:${selectedDevice.port}")
                            }
                            showDeviceListBottomSheet.value = false
                        },
                        onDismiss = { showDeviceListBottomSheet.value = false }
                    )
                }

                qrCodeDialogContent?.let { textToEncode ->
                    QrCodePopupDialog(
                        contentString = textToEncode,
                        onDismiss = { qrCodeDialogContent = null }
                    )
                }

                qrDecodeResult?.let { decodedText ->
                    QrDecodeResultDialog(
                        rawResult = decodedText,
                        onDismiss = { qrDecodeResult = null },
                        onExportToFile = { content ->
                            val savedName = saveTextToFlashFolder(this@MainActivity, flashFolder, content)
                            if (savedName != null) {
                                appendLog("[系统] 解码内容已成功全部输出至: flash/$savedName")
                            }
                            qrDecodeResult = null 
                        }
                    )
                }

                if (showIntentBottomSheet) {
                    NekoIntentBottomSheet(
                        onDismiss = { showIntentBottomSheet = false },
                        onCommandSubmit = { cmd ->
                            dispatchCommandRoute(cmd) 
                        }
                    )
                }

                if (showHelpBottomSheet) {
                    CommandHelpBottomSheet(
                        isVisible = showHelpBottomSheet,
                        onDismissRequest = { showHelpBottomSheet = false }
                    )
                }

                LaunchedEffect(showCpuBottomSheet) {
                    if (showCpuBottomSheet) {
                        pviewModel.initAndBind(this@MainActivity)
                    }
                }

                if (showCpuBottomSheet) {
                    val context = LocalContext.current
                    CompletePerformanceMonitorBottomSheet(
                        uiState = puiState,
                        onIntervalSelected = { pviewModel.setSampleInterval(it) },
                        onStartRecording = { pviewModel.startRecording() },
                        onStopRecording = { 
                            // 停止录制并自动在 getExternalFilesDir/cpu/ 目录下生成 CSV 文件
                            val savedFile = pviewModel.stopRecordingAndSave(context)
                            if (savedFile != null) {
                                appendLog("[系统] 性能日志已自动保存至: cpu/${savedFile.name}")
                            }
                        },
                        onExportCsv = { csvContent ->
                            pendingCsvContent = csvContent
                            val fileName = "perf_log_${System.currentTimeMillis()}.csv"
                            createCsvLauncher.launch(fileName)
                        },
                        onDismissRequest = { showCpuBottomSheet = false }
                    )
                }

                LaunchedEffect(showCpuHistoryBottomSheet) {
                    if (showCpuHistoryBottomSheet) {
                        pviewModel.refreshSavedFiles(this@MainActivity)
                    }
                }

                if (showCpuHistoryBottomSheet) {
                    val context = LocalContext.current
                    HistoryPerformanceBottomSheet(
                        uiState = puiState,
                        onSelectFile = { file -> pviewModel.loadHistoryFromFile(file) },
                        onDeleteFile = { file -> pviewModel.deleteHistoryFile(context, file) },
                        onBackToList = { pviewModel.clearSelectedHistory() },
                        onDismissRequest = { 
                            pviewModel.clearSelectedHistory()
                            showCpuHistoryBottomSheet = false 
                        }
                    )
                }

                if (showAppSigBottomSheet) {
                    val context = LocalContext.current
                    val appList = remember { getInstalledApps(context) }

                    AppListBottomSheet(
                        appList = appList,
                        isVisible = showAppSigBottomSheet,
                        onDismissRequest = { showAppSigBottomSheet = false },
                        onAppSelected = { selectedApp ->
                            showAppSigBottomSheet = false
                            appendLog("[系统] 正在解析 ${selectedApp.appName} [${selectedApp.packageName}] 的 APK 签名...")

                            lifecycleScope.launch(Dispatchers.IO) {
                                val report = NativeLibs.ApkSignature(selectedApp.apkPath)
                                val schemeText = NativeLibs.getSupportedSchemesText(selectedApp.apkPath)
                                withContext(Dispatchers.Main) {
                                    selectedSchemeText = schemeText
                                    selectedSigReport = report
                                    appendLog("[系统] 签名解析完成")
                                }
                            }
                        },
                        onStorageApkSelected = { uri ->
                            showAppSigBottomSheet = false
                            appendLog("[系统] 正在读取本地 APK 并解析签名...")

                            lifecycleScope.launch(Dispatchers.IO) {
                                val cacheFile = File(context.cacheDir, "target_file.apk")
                                val isCopySuccess = runCatching {
                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                        cacheFile.outputStream().use { output -> input.copyTo(output) }
                                    }
                                }.isSuccess

                                if (isCopySuccess) {
                                    val apkPath = cacheFile.absolutePath
                                    val report = NativeLibs.ApkSignature(apkPath)
                                    val schemeText = NativeLibs.getSupportedSchemesText(apkPath)

                                    withContext(Dispatchers.Main) {
                                        selectedSchemeText = schemeText
                                        selectedSigReport = report
                                        appendLog("[系统] 本地 APK 签名解析完成")
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        appendLog("[错误] 无法读取选中的本地文件")
                                    }
                                }
                            }
                        }
                    )
                }

                selectedSigReport?.let { report ->
                    SignatureResultBottomSheet(
                        reportText = report,
                        schemeText = selectedSchemeText,
                        onDismiss = { selectedSigReport = null }
                    )
                }
            }
        }
        
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        keyManager = AdbKeyManager(this)
        ensureFlashDirExists()
        tryToStartService()

        ContextCompat.registerReceiver(this, usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, usbStateReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }, ContextCompat.RECEIVER_EXPORTED)

        ContextCompat.registerReceiver(this, wifiReceiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED)

        val powerFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(
            this,
            powerReceiver,
            powerFilter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    fun stopAdbService() {
        val intent = Intent(this, AdbSessionService::class.java)
        stopService(intent)
    }

    private fun dispatchCommandRoute(cmdInput: String) {
        val cmd = cmdInput.trim()
        if (cmd.isEmpty()) return
    
        when {
            cmd.startsWith("neko ") -> {
                // 临时保留 neko 指令, 一般情况用不到
                val localCmd = cmd.removePrefix("neko ").trim()
                if (localCmd.isNotEmpty()) {
                    handleLocalShellPipeline(cmd)
                }
            }
            
            cmd == "neko-sig" || cmd == "apk-sig" -> {
                appendLog("[系统] 扩展指令 >> $cmd")
                appendLog("[系统] 正在读取安装应用列表...")
                showAppSigBottomSheet = true
            }
            
            cmd.startsWith("neko-intent ") -> {
                appendLog("[系统] 扩展指令 >> $cmd")
                val argsText = cmd.removePrefix("neko-intent ").trim()
                val parts = argsText.split("|")
                val content = parts[0].trim()
                val packageName = if (parts.size > 1) parts[1].trim() else ""

                if (content.isEmpty()) {
                    appendLog("[错误] 内容或链接不能为空！")
                    return
                }

                appendLog("[系统] 正在构建智能 Intent 执行管道...")
                executeSmartIntent(this, content, packageName)
            }
            
            cmd.startsWith("neko-audio") -> {
                appendLog("[系统] 扩展指令 >> $cmd")
                
                val nameArg = cmd.removePrefix("neko-audio").trim()
                pendingAudioBaseName = nameArg.ifEmpty {
                    "NekoAudio_${System.currentTimeMillis()}"
                }

                appendLog("[系统] 正在唤醒系统的媒体选择器…")
                
                pickVideoLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            }

            cmd.startsWith("download ") -> {
                appendLog("[系统] 扩展指令 >> $cmd")
                val urlArg = cmd.removePrefix("download ").trim()
                if (urlArg.isEmpty() || urlArg == "download") {
                    appendLog("[错误] download 指令缺少参数！用法: download <文件的URL地址>")
                } else {
                    executeDownload(urlArg)
                }
            }

            cmd.startsWith("encrypt ") -> handleCryptoCommand(cmd, isEncrypt = true)
            cmd.startsWith("decrypt ") -> handleCryptoCommand(cmd, isEncrypt = false)
            cmd.startsWith("qr-gen ")   -> handleQrGenCommand(cmd)
            cmd.startsWith("qr-decode ") -> handleQrDecodeCommand(cmd)

            cmd == "userkitty-log-export" -> exportLogToFlashFolder()
        
            cmd == "ip-test" -> {
                appendLog("[系统] 扩展指令 >> $cmd")
                startIpNetworkTest()
            }
            cmd == "usb-host" -> {
                appendLog("[系统] 扩展指令 >> $cmd")
                findHostDevice()
            }
            cmd == "query-apm" -> {
                appendLog("[系统] 扩展指令 >> $cmd")
                handleApmQuery()
            }

            else -> handlePhysicalFallback(cmd)
        }
    }
    
    private fun executeSmartIntent(activityContext: ComponentActivity, content: String, packageName: String) {
        try {
            val isUrl = content.startsWith("http://") || content.startsWith("https://")

            val intent = if (isUrl) {
                Intent(Intent.ACTION_VIEW, content.toUri()).apply {
                    if (packageName.isNotEmpty()) {
                        setPackage(packageName)
                    }
                }
            } else {
                Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, content)
                    if (packageName.isNotEmpty()) {
                        setPackage(packageName)
                    }
                }
            }

            if (packageName.isNotEmpty()) {
                activityContext.startActivity(intent)
                appendLog("[系统] Intent 指令已精准发送至: $packageName")
            } else {
                val chooserTitle = if (isUrl) "选择要打开的应用" else "Neko Intent 分享"
                val chooser = Intent.createChooser(intent, chooserTitle)
                activityContext.startActivity(chooser)
                appendLog("[系统] 已经成功唤起应用选择面板。")
            }

        } catch (e: Exception) {
            appendLog("[错误] 执行失败！请检查链接格式或确认目标应用已安装。")
        }
    }

    private fun handleCryptoCommand(cmd: String, isEncrypt: Boolean) {
        appendLog("[系统] 扩展指令 >> $cmd")
        val prefix = if (isEncrypt) "encrypt " else "decrypt "
        val args = cmd.removePrefix(prefix).trim().split(" ")
        if (args.size < 2) {
            appendLog("[错误] 用法: ${prefix.trim()} <文件名> <密码>")
            return
        }
        val fileName = args[0]
        val password = args[1]
        val targetFile = File(flashFolder, fileName)

        if (!targetFile.exists() || !targetFile.isFile) {
            appendLog("[错误] 未找到文件: flash/$fileName")
            return
        }

        if (isEncrypt) {
            val outputFile = File(flashFolder, "$fileName.enc")
            appendLog("[系统] 正在对 ${fileName} 执行 AES-256 加密...")
            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) { CryptoUtils.encryptFile(targetFile, outputFile, password) }
                if (success) appendLog("[系统] 加密成功！输出文件: flash/${outputFile.name}")
                else appendLog("[错误] 加密失败，请检查异常日志")
            }
        } else {
            val outName = if (fileName.endsWith(".enc")) fileName.removeSuffix(".enc") else "$fileName.dec"
            val outputFile = File(flashFolder, outName)
            appendLog("[系统] 正在解密文件: $fileName ...")
            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) { CryptoUtils.decryptFile(targetFile, outputFile, password) }
                if (success) appendLog("[系统] 解密成功！已还原为: flash/$outName")
                else appendLog("[错误] 解密失败！可能是密码错误或文件已被篡改！")
            }
        }
    }
    
    private fun handleQrGenCommand(cmd: String) {
        appendLog("[系统] 扩展指令 >> $cmd")
        val arg = cmd.removePrefix("qr-gen ").trim()
        if (arg.isEmpty()) {
            appendLog("[错误] qr-gen 指令缺少参数！用法: qr-gen <文本/文件名>")
            return
        }
        val fileInFlash = File(flashFolder, arg)
        val targetFile = if (fileInFlash.exists() && fileInFlash.isFile) fileInFlash else if (File(arg).exists() && File(arg).isFile) File(arg) else null

        if (targetFile != null) {
            appendLog("[系统] 匹配到本地文件: ${targetFile.absolutePath}")
            runCatching {
                if (targetFile.length() > 2000) {
                    appendLog("[警告] 文件过大，已自动降级为【生成文件名二维码】")
                    qrCodeDialogContent = if (targetFile.parentFile?.name == "flash") arg else targetFile.name
                } else {
                    val fileText = targetFile.readText(Charsets.UTF_8).trim()
                    if (fileText.isEmpty()) appendLog("[错误] 文件内容为空") 
                    else qrCodeDialogContent = fileText
                }
            }.onFailure {
                appendLog("[错误] 读取文件失败，转为对参数文本生成二维码")
                qrCodeDialogContent = arg
            }
        } else {
            if (arg.length > 2000) appendLog("[错误] 输入文本过长！") 
            else qrCodeDialogContent = arg
        }
    }

    private fun handleQrDecodeCommand(cmd: String) {
        appendLog("[系统] 扩展指令 >> $cmd")
        val arg = cmd.removePrefix("qr-decode ").trim()
        if (arg.isEmpty()) return
        if (arg == "--system") {
            openSystemImagePicker()
            return
        }
        val fileInFlash = File(flashFolder, arg)
        val targetFile = if (fileInFlash.exists() && fileInFlash.isFile) fileInFlash else if (File(arg).exists() && File(arg).isFile) File(arg) else null
    
        if (targetFile != null) {
            val result = QrCodeUtils.decodeQrCode(targetFile)
            if (result != null) { qrDecodeResult = result } 
            else appendLog("[错误] 二维码解析失败")
        } else {
            appendLog("[错误] 未找到指定图片文件: $arg")
        }
    }

    private fun handleApmQuery() {
        if (android.os.Build.VERSION.SDK_INT >= 36) {
            runCatching {
                val apm = getSystemService(android.security.advancedprotection.AdvancedProtectionManager::class.java)
                val isEnabled = apm?.isAdvancedProtectionEnabled ?: false
                appendLog("[系统] 高级保护模式 (AAPM) 状态: ${if (isEnabled) "【已开启 🛡️】" else "【已关闭 🔓】"}")
            }.onFailure { appendLog("[错误] 查询失败: ${it.message}") }
        } else {
            appendLog("[提示] 当前系统级别低于 API 36，不支持高级保护模式。")
        }
    }

    private fun handleLocalShellPipeline(cmd: String) {
        currentShellJob?.cancel()

        val rawCmd = cmd.trim()
        if (rawCmd.isEmpty()) return

        appendLog("[系统] Shell >> $rawCmd")

        var realLocalCmd = rawCmd
        var requestRoot = false

        if (realLocalCmd == "su -c sh" || realLocalCmd == "su") {
            realLocalCmd = "id"
            requestRoot = true
        } else if (realLocalCmd.startsWith("su -c ")) {
            realLocalCmd = realLocalCmd.removePrefix("su -c ").trim().removeSurrounding("\"", "\"")
            requestRoot = true
        } else if (realLocalCmd.startsWith("su ")) {
            realLocalCmd = realLocalCmd.removePrefix("su ").trim()
            requestRoot = true
        }

        appendLog(if (requestRoot) "[Root管道] 请求身份变更执行实时流..." else "[本地管道] 开始执行流式命令...")

        currentShellJob = lifecycleScope.launch {
            val service = adbService
            if (service != null && isServiceBound) {
                var pfd: ParcelFileDescriptor? = null

                try {
                    pfd = withContext(Dispatchers.IO) {
                        service.executeShellStream(this@MainActivity, realLocalCmd, requestRoot)
                    }

                    val logChannel = Channel<List<String>>(Channel.UNLIMITED)

                    coroutineScope {
                        launch(Dispatchers.IO) {
                            try {
                                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { inputStream ->
                                    BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8), 32768).use { reader ->
                                        val chunkBuffer = ArrayList<String>(128)
                                        var lastChunkTime = System.currentTimeMillis()

                                        while (isActive) {
                                            val line = try {
                                                reader.readLine()
                                            } catch (_: java.io.IOException) {
                                                null
                                            } ?: break

                                            chunkBuffer.add(line)
                                            val now = System.currentTimeMillis()

                                            if (chunkBuffer.size >= 100 || (now - lastChunkTime >= 8)) {
                                                logChannel.send(ArrayList(chunkBuffer))
                                                chunkBuffer.clear()
                                                lastChunkTime = now
                                            }
                                        }

                                        if (chunkBuffer.isNotEmpty()) {
                                            logChannel.send(ArrayList(chunkBuffer))
                                            chunkBuffer.clear()
                                        }
                                    }
                                }
                            } finally {
                                logChannel.close()
                            }
                        }

                        launch(Dispatchers.Main) {
                            val mainBuffer = ArrayList<String>(256)
                            var lastFlushTime = System.currentTimeMillis()

                            for (batch in logChannel) {
                                mainBuffer.addAll(batch)

                                val now = System.currentTimeMillis()
                                if (mainBuffer.size >= 200 || (now - lastFlushTime >= 8)) {
                                    mainBuffer.forEach { appendLog(it) }
                                    mainBuffer.clear()
                                    lastFlushTime = now
                                }
                            }

                            if (mainBuffer.isNotEmpty()) {
                                mainBuffer.forEach { appendLog(it) }
                                mainBuffer.clear()
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        appendLog("[错误] 执行异常: ${e.message}")
                    }
                } finally {
                    runCatching { pfd?.close() }
                }
            } else {
                appendLog("[错误] 前台守护进程服务未就绪！")
            }
        }
    }

    fun onUserClickStopCommand() {
        val job = currentShellJob

        if (job == null || !job.isActive) {
            appendLog("[系统] 当前没有正在执行的命令")
            return
        }

        job.cancel()
        currentShellJob = null

        lifecycleScope.launch(Dispatchers.IO + NonCancellable) {
            val service = adbService
            if (service != null && isServiceBound) {
                runCatching {
                    service.terminateCurrentCommand()
                    withContext(Dispatchers.Main) {
                        appendLog("[系统] 用户强制终止了当前命令")
                    }
                }.onFailure { e ->
                    withContext(Dispatchers.Main) {
                        appendLog("[错误] 终止命令失败: ${e.message}")
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    appendLog("[警告] 服务未连接，已取消前端日志读取")
                }
            }
        }
    }

    private fun handlePhysicalFallback(cmd: String) {
        if (isFastbootMode) {
            appendLog("[发送] FB >> $cmd")
    
            if (cmd == "usb-selinux") {
                appendLog("[系统] 正在尝试设置 SeLinux 为宽容模式, 该指令由 app 提供")
                FbSeLinuxCmd()
                return
            }
    
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { viewModel.runCommand(cmd) }
                    .onFailure { appendLog("[错误] ${it.message}") } 
            }
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                when {
                    cmd.startsWith("adb pair") -> handleLocalAdbPair(cmd)
                    cmd.startsWith("adb connect") -> handleLocalAdbConnect(cmd)

                    cmd.startsWith("adb shell") -> {
                        if (!isAdbAuthorized) {
                            appendLog("[发送] ADB >> $cmd")
                            appendLog("[错误] ADB 未授权，无法执行 adb shell")
                            return@launch
                        }
                        sendAdbShell(cmd)
                    }
                    
                    cmd.startsWith("adb push") -> {
                        if (!isAdbAuthorized) {
                            appendLog("[发送] ADB >> $cmd")
                            appendLog("[错误] ADB 未授权，无法执行 adb push")
                            return@launch
                        }
                        handleLocalAdbPush(cmd)
                    }
                    
                    cmd.startsWith("adb pull") -> {
                        if (!isAdbAuthorized) {
                            appendLog("[发送] ADB >> $cmd")
                            appendLog("[错误] ADB 未授权，无法执行 adb pull")
                            return@launch
                        }
                        handleLocalAdbPull(cmd)
                    }
                    
                    cmd.startsWith("adb install") -> {
                        if (!isAdbAuthorized) {
                            appendLog("[发送] ADB >> $cmd")
                            appendLog("[错误] ADB 未授权，无法执行 adb install")
                            return@launch
                        }
                        handleLocalAdbInstall(this@MainActivity, cmd)
                    }
                    
                    cmd.startsWith("adb uninstall") -> {
                        if (!isAdbAuthorized) {
                            appendLog("[发送] ADB >> $cmd")
                            appendLog("[错误] ADB 未授权，无法执行 adb uninstall")
                            return@launch
                        }
                        handleLocalAdbUninstall(cmd)
                    }

                    // 兜底：未加 adb 前缀的所有普通命令均走本地 Shell 管道（不受 adbd 状态限制）
                    else -> {
                        handleLocalShellPipeline(cmd)
                    }
                }
            }
        }
    }

    fun triggerStoragePermissionCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                appendLog("[INFO] Android 11+ 所有文件读写权限已就绪")
            } else {
                runCatching {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = "package:$packageName".toUri()
                    }
                    allFilesPermissionLauncher.launch(intent)
                }
            }
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val hasPermission = permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }

            if (hasPermission) {
                appendLog("[INFO] Android 10 文件读写权限已就绪")
            } else {
                legacyStorageLauncher.launch(permissions)
            }
        }
    }
    
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            appendLog("[系统] 已选择图片，开始解码...")
        
            val result = QrCodeUtils.decodeQrCodes(this, uri) 
        
            if (result != null) {
                appendLog("[系统] 二维码解码成功！")
                qrDecodeResult = result
            } else {
                appendLog("[错误] 二维码解析失败，请确保图片清晰且确实包含二维码")
            }
        } else {
            appendLog("[提示] 取消了系统图片选择。")
        }
    }

    private fun openSystemImagePicker() {
        try {
            pickImageLauncher.launch("image/*")
        } catch (e: Exception) {
            appendLog("[错误] 无法打开系统图片选择器: ${e.localizedMessage}")
        }
    }
    
    fun tryToStartService() {
        if (isBindingRequested) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                startAndBindAdbService()
            } else {
                appendLog("[权限] 正在申请 Android 13+ 前台服务通知权限")
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                appendLog("[系统] 检查通过：常驻通知总线完好，正在激活前台服务")
                startAndBindAdbService()
            } else {
                handlePermissionDeniedSituation()
                startAndBindAdbService()
            }
        }
    }
    
    private fun handlePermissionDeniedSituation() {
        appendLog("[错误] ❌ 通知权限被拦截/拒绝！")
        appendLog("[提示] ⚠️ 前台服务将尝试在没有通知权限的情况下静默启动")
    }
    
    private fun startAndBindAdbService() {
        if (isBindingRequested) return
        isBindingRequested = true
        val intent = Intent(this, AdbSessionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun executeDownload(urlStr: String) {
        val uri = urlStr.toUri()
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            appendLog("[错误] 下载失败！该指令仅支持 http:// 或 https:// 的网络地址")
            return
        }

        val serviceInstance = adbService
        if (serviceInstance != null && isServiceBound) {
            serviceInstance.executeDownloadFromService(urlStr, flashFolder) { logText ->
                appendLog(logText)
            }
        } else {
            appendLog("[错误] 核心前台进程未并网或已断开，拒绝执行网络下载")
        }
    }

    fun FbSeLinuxCmd() {
        if (!isFastbootMode) {
             appendLog("[警告] 该命令只能在 Fastboot 模式使用")
           return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val cmds = listOf(
                  "oem set-gpu-preemption 0 androidboot.selinux=permissive",
                  "continue"
            )
            for (cmd in cmds) {
               // 1. 先把要发的命令打印出来
               withContext(Dispatchers.Main) { 
                  appendLog("[发送] FB >> $cmd") 
               }
               // 2. 发送原始指令 (调用临时执行方法)
               viewModel.runCommand(cmd)
               // 3. 等待设备响应（如果有）
                delay(500) 
            }
        }
    }
    
    fun findHostDevice() {
        val devices = usbManager.deviceList
        if (devices.isEmpty()) {
            appendLog("[INFO] 未发现 USB 设备")
            return
        }

        for (device in devices.values) {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                appendLog("设备: ${device.productName ?: "unknown"}")
                appendLog("制造商: ${device.manufacturerName ?: "unknown"}")
                appendLog("版本号: ${device.version}")
                // 在遍历 interface 的循环内
                appendLog("接口名称: ${intf.name ?: "unknown"}")
                // USB设备信息
                appendLog("VID: ${device.vendorId} | PID: ${device.productId}")
                appendLog("检查接口 $i: Class=${intf.interfaceClass}, Subclass=${intf.interfaceSubclass}, Protocol=${intf.interfaceProtocol}")
                
                // 遍历端点 (Endpoint)
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    
                    // 解析端点方向：最高位为 1 代表 IN (设备到手机)，0 代表 OUT (手机到设备)
                    val isInput = (ep.address and 0x80) != 0
                    val direction = if (isInput) "IN (设备->手机)" else "OUT (手机->设备)"
                    
                    // 解析端点编号：低 4 位代表编号
                    val epNumber = ep.address and 0x0F
                    
                    appendLog("端点 $j: 地址=${ep.address} (方向: $direction, 编号: $epNumber), 最大包大小=${ep.maxPacketSize}")
                }
                
                appendLog("--- 通过USB连接输出 ---")
                if (intf.interfaceClass == 255 && intf.interfaceSubclass == 66) {
                    isFastbootMode = (intf.interfaceProtocol == 3)
                    isUsbAttached = true
                    
                    val modeName = if (isFastbootMode) "Fastboot" else "ADB"
                    // 匹配要求：同行显示 VID/PID 十进制
                    appendLog("--- 检测到 $modeName 兼容设备 ---")

                    if (!usbManager.hasPermission(device)) {
                        // --- 修复 Android 14 崩溃的关键点 ---
                        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                           PendingIntent.FLAG_MUTABLE 
                        } else {
                            0
                        }
                        // 必须明确 setPackage，将隐式 Intent 变为显式 Intent
                        val intent = Intent(ACTION_USB_PERMISSION).apply {
                            setPackage(packageName) 
                        }
                        val pi = PendingIntent.getBroadcast(this, 0, intent, flags)
                        usbManager.requestPermission(device, pi)
                        
                    } else {
                        appendLog("[Serial] 硬件序列号: ${device.serialNumber ?: "unknown"}")
                        connectToInterface(device)
                    }
                    return
                }
            }
        }
        appendLog("发现设备但无 ADB/Fastboot 接口")
    }
    
    private fun connectToInterface(device: UsbDevice) {
        val protocolTarget = if (isFastbootMode) 3 else 1
        val intf = (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == 255 && it.interfaceSubclass == 66 && it.interfaceProtocol == protocolTarget } ?: return

        val conn = usbManager.openDevice(device) ?: return
        conn.claimInterface(intf, true)
    
        for (j in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(j)
            if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
        }
        usbConn = conn
    
        val serialNumber = runCatching { device.serialNumber }.getOrNull() ?: "unknown"
        val deviceKey = "USB_$serialNumber"
    
        if (isFastbootMode) {
            setupFastboot()
            appendLog("[系统] Fastboot 物理信道就绪 | 序列号: $serialNumber")
        } else {
            isAdbAuthorized = true
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val currentService = adbService ?: return@launch
            
                    if (currentService.getConnectedDeviceIds().contains(deviceKey)) {
                        withContext(Dispatchers.Main) { 
                            appendLog("[USB] 设备已在通道中，直接复用当前连接，防止无限重连循环。") 
                        }
                        return@launch
                    }

                    usbForwarder?.stop()
                    usbForwarder = UsbPortForwarder(conn, epIn!!, epOut!!)
                    val localVirtualPort = usbForwarder!!.startBridge()

                    withContext(Dispatchers.Main) {
                        appendLog("[Auth] 正在向环回端口 [$localVirtualPort] 发起握手与撞门机制...")
                    }

                    val instance = Kadb.create(host = "127.0.0.1", port = localVirtualPort)
                    val isConnected = runCatching {
                        // 强行撞门 adbd 成功就是成功，失败就是失败
                        instance.shell("echo 1") 
                        // 如果没有抛出 Auth 异常且成功返回，说明通道建立成功
                        true
                    }.getOrElse { false }

                    withContext(Dispatchers.Main) {
                        val activeService = adbService
                        if (isConnected && activeService != null) {
                            activeService.registerUsbDevice(serialNumber, instance)
                            activeService.currentDeviceId = deviceKey
                            appendLog(">>> 👍 ADB 有线授权成功，物理总线全面并网！[$serialNumber] <<<")
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        appendLog("[Error] KADB 物理有线握手崩溃: ${e.message}")
                    }
                }
            }
        }
    }
    
    private fun setupFastboot() {
        viewModel.initFastboot(
            usbConn = usbConn!!, 
            epOut = epOut!!, 
            epIn = epIn!!,
            responseChannel = responseChannel,
            flashFolder = flashFolder
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.fastbootManager?.logFlow?.collect { msg ->
                    appendLog(msg) 
                }
            }
        }
    }
    
    private fun executeAutoWifiConnect() {
        val currentWifi = getCurrentWifiSsid()
        val allHistory = getAllSavedDevices()
        val matchedDevices = allHistory.filter { it.wifiSsid == currentWifi }
        when {
            matchedDevices.isEmpty() -> {
                appendLog("[系统] 💡 当前 WiFi [$currentWifi] 无历史记录，等待手动输入")
            }
            matchedDevices.size == 1 -> {
                val target = matchedDevices.first()
                appendLog("[系统] 📡 侦测到 WiFi [$currentWifi] 唯一历史设备，正在无感回连...")
                lifecycleScope.launch(Dispatchers.IO) {
                    handleLocalAdbConnect("adb connect ${target.ip}:${target.port}")
                }
            }
            else -> {
                matchedDevicesList.clear()
                matchedDevicesList.addAll(matchedDevices)
                showDeviceListBottomSheet.value = true
            }
        }
    }
    
    private fun exportLogToFlashFolder() {
        if (viewModel.isLogEmpty) {
            appendLog("[提示] 当前控制台日志空空如也")
            return
        }

        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        val timeStamp = LocalDateTime.now().format(formatter)
        val fileName = "Log_$timeStamp.txt"
        val targetFile = File(logsFolder, fileName)

        if (targetFile.parentFile?.exists() == false) {
            targetFile.parentFile?.mkdirs()
        }

        lifecycleScope.launch {
            val isSuccess = viewModel.exportFullLogToFile(targetFile)

            if (isSuccess) {
                appendLog("[系统] 🎉 日志已成功安全写入文件：${targetFile.absolutePath}")
            } else {
                appendLog("[错误] ❌ 写入文件时发生异常，请检查磁盘权限或空间是否充足。")
            }
        }
    }

    private fun handleLocalAdbInstall(context: Context, command: String) {
        appendLog("安装 >> $command")
        val trimmedCmd = command.trim()
        if (!trimmedCmd.startsWith("adb install", ignoreCase = true)) {
            appendLog("[错误] 请使用正规格式: adb install [本地路径/文件名]")
            return
        }
        
        val pathInput = trimmedCmd.substring("adb install".length).trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")

        if (pathInput.isEmpty()) {
            appendLog("[错误] 找不到输入路径")
            return
        }

        val file = if (pathInput.startsWith("/")) File(pathInput) else File(flashFolder, pathInput)

        if (!file.exists()) {
            appendLog("[错误] 找不到文件或路径: ${file.absolutePath}")
            return
        }

        val ext = file.extension.lowercase()
        val isCompressedBundle = ext == "apks" || ext == "xapk"
        val isMultiple = file.isDirectory || isCompressedBundle

        lifecycleScope.launch(Dispatchers.IO) {
            var tempExtractDir: File? = null
            
            try {
                val kadb = kadbInstance ?: throw IllegalStateException("数据通道未建立，请先并网设备")
            
                if (isMultiple) {
                    withContext(Dispatchers.Main) { appendLog("[Install] 检测到多文件/多组件安装模式 (Split APKs)...") }
                    
                    val apkList = mutableListOf<File>()

                    if (file.isDirectory) {
                        val files = file.listFiles { _, name -> name.lowercase().endsWith(".apk") }
                        if (files != null) apkList.addAll(files)
                    } else if (isCompressedBundle) {
                        withContext(Dispatchers.Main) { appendLog("[Install] 正在对 [${file.name}] 容器进行物理破壳与流提取...") }
                        
                        tempExtractDir = File(context.cacheDir, "kadb_extracted_${System.currentTimeMillis()}")
                        if (!tempExtractDir.mkdirs()) throw java.io.IOException("无法创建临时解压释放区")

                        ZipFile(file).use { zip ->
                            val entries = zip.entries()
                            while (entries.hasMoreElements()) {
                                val entry = entries.nextElement()
                                if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                                    val pureFileName = File(entry.name).name
                                    val targetFile = File(tempExtractDir, pureFileName)
                                    
                                    zip.getInputStream(entry).use { input ->
                                        targetFile.outputStream().use { output -> input.copyTo(output) }
                                    }
                                    apkList.add(targetFile)
                                }
                            }
                        }
                    }

                    if (apkList.isEmpty()) {
                        withContext(Dispatchers.Main) { appendLog("[错误] 目标路径下或容器内未提取到任何有效的 .apk 安装元组件") }
                        return@launch
                    }

                    withContext(Dispatchers.Main) { appendLog("[Install] 物理集群总线传输中，共计 ${apkList.size} 个组件...") }
                    
                    kadb.installMultiple(apkList)
                    
                    withContext(Dispatchers.Main) { appendLog("[成功] 👍 多组件联装全量部署成功！") }
                } else {
                    withContext(Dispatchers.Main) { appendLog("[Install] 正在传输独立架构包: ${file.name}") }
                    kadb.install(file)
                    withContext(Dispatchers.Main) { appendLog("[成功] 👍 独立包安装完成: ${file.name}") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    appendLog("[安装失败] 核心熔断原因: ${e.message}") 
                }
            } finally {
                tempExtractDir?.let {
                    if (it.exists()) {
                        it.deleteRecursively()
                    }
                }
            }
        }
    }

    private fun handleLocalAdbUninstall(command: String) {
        appendLog("卸载 >> $command")
        val parts = command.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (parts.size < 3) {
            appendLog("[错误] 请使用: adb uninstall [包名]")
            return
        }

        val packageName = parts[2]
        appendLog("[Uninstall] 正在尝试卸载: $packageName")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val kadb = kadbInstance ?: throw IllegalStateException("数据通道未建立")
            
                // 🌟 直接调用 Kadb 内置的 uninstall
                kadb.uninstall(packageName)
            
                withContext(Dispatchers.Main) { 
                    appendLog("[成功] 已卸载: $packageName") 
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    appendLog("[卸载失败] 无法完成: ${e.message}") 
                }
            }
        }
    }
    
    private suspend fun sendAdbShell(command: String) {
        withContext(Dispatchers.Main) {
            isAdbAuthorized = true
            appendLog("ADB >> $command")
        }
        // 1. 如果有旧任务正在运行，先停止它
        if (currentShellJob?.isActive == true) {
            currentShellJob?.cancel()
            appendLog("[提示] 已自动停止了上一个任务…")
        }

        // 2. 启动新任务并保存 Job
        currentShellJob = lifecycleScope.launch(Dispatchers.IO) {
            val cleanCmd = command.removePrefix("adb shell ").trim()
            val shortDumpsysList = listOf(
                "dumpsys battery",
                "dumpsys thermal",
                "dumpsys diskstats",
                "dumpsys user",
                "dumpsys statusbar",
                "dumpsys hardware_properties"
            )
            val isLongRunning = when {
                shortDumpsysList.any { cleanCmd.contains(it) } -> false
                cleanCmd.contains("logcat") && (cleanCmd.contains("-d") || cleanCmd.contains("-c")) -> false
                cleanCmd.startsWith("top") && !cleanCmd.contains("-n") -> true
                cleanCmd.startsWith("ping") && !cleanCmd.contains("-c") -> true
            
                cleanCmd.contains("logcat") -> true
                cleanCmd.contains("dumpsys") -> true
                cleanCmd.contains("screenrecord") -> true
            
                else -> false
            }
        
            try {
                val kadb = kadbInstance ?: throw IllegalStateException("通道连接未就绪")
            
                if (isLongRunning) {
                    handleStreamingCommand(kadb, cleanCmd)
                } else {
                    handleBufferedCommand(kadb, cleanCmd, 30_000L)
                }
            } catch (e: CancellationException) {
                // 协程被取消时会走到这里
                withContext(Dispatchers.Main) { appendLog("[系统] 任务已手动停止") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    appendLog("[Shell 异常] ${e.message}")
                    if (e is java.io.IOException || e.message?.contains("closed") == true) {
                        kadbInstance = null
                        isAdbAuthorized = false
                        appendLog("连接已断开")
                    }
                }
            }
        }
    }
    
    fun stopCurrentCommand() {
        if (currentShellJob?.isActive == true) {
            currentShellJob?.cancel()
            appendLog("[系统] 正在停止...")
        } else {
            appendLog("[系统] 当前没有运行中的任务")
        }
    }
    
    private suspend fun handleBufferedCommand(kadb: Kadb, command: String, timeout: Long) {
        val response = withContext(Dispatchers.IO) {
            withTimeout(timeout) {
                kadb.shell(command)
            }
        }
        withContext(Dispatchers.Main) {
            if (response.allOutput.isNotBlank()) appendLog(response.allOutput.trim())
            else appendLog("[系统] 执行完成，无输出")
        }
    }
    
    private suspend fun handleStreamingCommand(kadb: Kadb, command: String) {
        withContext(Dispatchers.IO) {
            // kadb.openShell() 返回的就是 AdbShellStream
            kadb.openShell(command).use { shellStream ->
            
                // 简单的批处理缓冲，避免高频刷新 UI
                val outputBuffer = StringBuilder()
                var lastUpdate = System.currentTimeMillis()

                try {
                    while (true) {
                        // 直接调用库自带的 read() 方法，它是阻塞的，非常适合协程
                        val packet = shellStream.read()
                    
                        val content = when (packet) {
                            is AdbShellPacket.StdOut -> String(packet.payload)
                            is AdbShellPacket.StdError -> "[Error] " + String(packet.payload)
                            is AdbShellPacket.Exit -> {
                                // 收到 Exit 包，任务结束，跳出循环
                                withContext(Dispatchers.Main) { 
                                    appendLog("[系统] 命令执行结束，退出码: ${packet.payload[0].toUByte()}") 
                                }
                                break
                            }
                        }

                        // 实时追加到缓冲
                        outputBuffer.append(content)

                        // 性能优化：每 500ms 或数据块积累足够多时才刷新 UI
                        if (System.currentTimeMillis() - lastUpdate > 500) {
                            val snapshot = outputBuffer.toString()
                            outputBuffer.clear()
                            lastUpdate = System.currentTimeMillis()
                        
                            withContext(Dispatchers.Main) {
                                appendLog(snapshot)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 捕获协程取消或其他异常
                    withContext(Dispatchers.Main) {
                        if (e is CancellationException) {
                            appendLog("[系统] 用户已手动终止任务")
                        } else {
                            appendLog("[Shell 异常] $e")
                        }
                    }
                }
            }
        }
    }
    
    private fun handleLocalAdbPair(command: String) {
        appendLog("[配对] 执行 >> $command")
        val parts = command.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (parts.size < 4) {
            appendLog("[错误] 请使用: adb pair [IP:配对端口] [配对码]")
            return
        }

        val target = parts[2] 
        val pairingCode = parts[3] 
        val hostPort = target.split(":")
        if (hostPort.size != 2) {
            appendLog("[错误] 格式不正确，应为 IP:端口")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { appendLog("[配对] 正在向远端电视注入 TLS 配对验证...") }
                
                Kadb.pair(host = hostPort[0], port = hostPort[1].toInt(), pairingCode = pairingCode)
                
                withContext(Dispatchers.Main) { 
                    appendLog("[成功] 🎉 配对凭证握手存盘成功！")
                    appendLog("[提示] ⚠️ 请查看电视上的【无线调试端口】，输入 adb connect [IP:端口] 唤醒数据总线。")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("[配对失败] 异常: ${e.message}") }
            }
        }
    }
    
    suspend fun handleLocalAdbConnect(command: String) {
        withContext(Dispatchers.Main) {
            appendLog("[无线] 执行 >> $command")
        }

        val parts = command.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (parts.size < 3) {
            withContext(Dispatchers.Main) { appendLog("[错误] 请使用: adb connect [IP:无线调试端口]") }
            return
        }
        val target = parts[2]
        val hostPort = target.split(":")
        if (hostPort.size != 2) {
            withContext(Dispatchers.Main) { appendLog("[错误] IP与端口格式错误") }
            return
        }
        val ip = hostPort[0]
        val port = hostPort[1].toInt()
        
        val deviceKey = "WIFI_$ip:$port" 

        val currentService = adbService ?: return

        if (currentService.getConnectedDeviceIds().contains(deviceKey)) {
            currentService.unregisterDevice(deviceKey) 
        }

        try {
            withContext(Dispatchers.Main) { appendLog("[无线] 正在唤醒远端网络数据通道...") }
        
            val instance = withContext(Dispatchers.IO) {
                Kadb.create(host = ip, port = port)
            }
        
            withContext(Dispatchers.Main) { appendLog("[无线] 正在向网络通道发射探路信号...") }
        
            val response = withContext(Dispatchers.IO) {
                instance.shell("echo 1")
            }

            if (response.exitCode == 0 && response.allOutput.trim() == "1") {
                withContext(Dispatchers.Main) {
                    isAdbAuthorized = true
                    val activeService = adbService
                    if (activeService != null) {
                        activeService.registerWifiDevice("$ip:$port", instance)
                        activeService.currentDeviceId = deviceKey 
                        appendLog(">>> 👍 无线设备并网成功！已自动切换为主控目标。 <<<")
                    }
                    saveConnectedDevice(ip, port)
                }
            } else {
                runCatching { instance.close() }
                withContext(Dispatchers.Main) { appendLog("[警告] 远端响应握手信号失败，退出通道") }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                appendLog("[连接失败] 远端网络拒绝建立链路: ${e.message}")
            }
        }
    }
    
    private fun handleLocalAdbPush(command: String) {
        appendLog("推送 >> $command")
        val parts = command.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (parts.size < 4) {
            appendLog("[错误] 请使用: adb push [本地文件名] [远端路径]")
            return
        }

        val localInput = parts[2]
        val remotePath = parts[3]
        val localFile = if (localInput.startsWith("/")) File(localInput) else File(flashFolder, localInput)

        if (!localFile.exists()) {
            appendLog("[错误] 找不到本地物理文件: ${localFile.absolutePath}")
            return
        }

        val finalRemotePath = if (remotePath.endsWith("/")) remotePath + localFile.name else remotePath
        appendLog("[Sync] 正在安全推送: ${localFile.name} -> $finalRemotePath")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val kadb = kadbInstance ?: throw IllegalStateException("数据通道未建立")
                val totalBytes = localFile.length()
                val startTime = System.currentTimeMillis()
                var lastUpdateTime = startTime

                val baseSource = localFile.source()
                var bytesTransferred = 0L
            
                val progressSource = object : okio.ForwardingSource(baseSource) {
                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                        val bytesRead = super.read(sink, byteCount)
                        if (bytesRead > 0) {
                            bytesTransferred += bytesRead
                            val currentTime = System.currentTimeMillis()
                        
                            if (currentTime - lastUpdateTime >= 300 || bytesTransferred == totalBytes) {
                                val durationMs = currentTime - startTime
                                val speedStr = calculateSpeed(bytesTransferred, durationMs)
                                val progress = if (totalBytes > 0) (bytesTransferred * 100 / totalBytes).toInt() else 0
                            
                                lifecycleScope.launch(Dispatchers.Main) {
                                    appendLog("[实时] 进度: $progress% | 已传: ${bytesTransferred / 1024 / 1024}MB | 速度: $speedStr | 耗时: ${durationMs / 1000.0}s")
                                }
                                lastUpdateTime = currentTime
                            }
                        }
                        return bytesRead
                    }
                }

                val syncStream = kadb.openSync()
                syncStream.use { stream ->
                    stream.send(
                        source = progressSource, 
                        remotePath = finalRemotePath, 
                        mode = 438, 
                        lastModifiedMs = localFile.lastModified()
                    )
                }

                val totalDurationMs = System.currentTimeMillis() - startTime
                withContext(Dispatchers.Main) {
                    appendLog("[成功] 文件已被推入远端: $finalRemotePath")
                    appendLog("[总体性能] 总耗时: ${totalDurationMs / 1000.0}s | 平均速度: ${calculateSpeed(totalBytes, totalDurationMs)}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("[Push 失败] 传输崩塌: ${e.message}") }
            }
        }
    }

    private fun handleLocalAdbPull(command: String) {
        appendLog("拉取 >> $command")
        val parts = command.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (parts.size < 3) {
            appendLog("[错误] 请使用: adb pull [远端路径] (可选本地落地名)")
            return
        }

        val remotePath = parts[2]
        val localInput = if (parts.size >= 4) parts[3] else remotePath.substringAfterLast("/")
        val localFile = if (localInput.startsWith("/")) File(localInput) else File(flashFolder, localInput)

        appendLog("[Sync] 正在拉取远端数据: $remotePath -> ${localFile.absolutePath}")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val kadb = kadbInstance ?: throw IllegalStateException("数据通道未建立")
                val startTime = System.currentTimeMillis()
                var lastUpdateTime = startTime

                val baseSink = localFile.sink()
                var bytesTransferred = 0L

                val progressSink = object : okio.ForwardingSink(baseSink) {
                    override fun write(source: okio.Buffer, byteCount: Long) {
                        super.write(source, byteCount)
                        bytesTransferred += byteCount
                        val currentTime = System.currentTimeMillis()
                    
                        if (currentTime - lastUpdateTime >= 300) {
                            val durationMs = currentTime - startTime
                            val speedStr = calculateSpeed(bytesTransferred, durationMs)
                        
                            lifecycleScope.launch(Dispatchers.Main) {
                                appendLog("[实时] 已下载: ${bytesTransferred / 1024 / 1024}MB | 速度: $speedStr | 耗时: ${durationMs / 1000.0}s")
                            }
                            lastUpdateTime = currentTime
                        }
                    }
                }

                val syncStream = kadb.openSync()
                syncStream.use { stream ->
                    stream.recv(sink = progressSink, remotePath = remotePath)
                }

                val totalDurationMs = System.currentTimeMillis() - startTime
                withContext(Dispatchers.Main) {
                    appendLog("[成功] 数据已沉淀至本地: ${localFile.absolutePath}")
                    appendLog("[总体性能] 总耗时: ${totalDurationMs / 1000.0}s | 平均速度: ${calculateSpeed(bytesTransferred, totalDurationMs)} | 总大小: ${bytesTransferred / 1024 / 1024}MB")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("[Pull 失败] 提取中止: ${e.message}") }
            }
        }
    }

    private fun calculateSpeed(bytes: Long, durationMs: Long): String {
        if (durationMs <= 0 || bytes <= 0) return "0 KB/s"
    
        // 秒数 = 毫秒 / 1000
        val seconds = durationMs / 1000.0
        // 每秒传输的字节数
        val bytesPerSecond = bytes / seconds
    
        return when {
            // 如果达到 MB/s 级别 (大于等于 1024 * 1024 字节)
            bytesPerSecond >= 1048576 -> {
                val mbps = bytesPerSecond / 1048576.0
                String.format(java.util.Locale.US, "%.2f MB/s", mbps)
            }
            // 如果是 KB/s 级别
            bytesPerSecond >= 1024 -> {
                val kbps = bytesPerSecond / 1024.0
                String.format(java.util.Locale.US, "%.2f KB/s", kbps)
            }
            // 极小文本下的 B/s 级别
            else -> {
                String.format(java.util.Locale.US, "%.0f B/s", bytesPerSecond)
            }
        }
    }
    
    private fun saveConnectedDevice(ip: String, port: Int) {
        val currentWifi = getCurrentWifiSsid()
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val deviceList = getAllSavedDevices().toMutableList()
        
        deviceList.removeAll { it.ip == ip && it.wifiSsid == currentWifi }
        deviceList.add(0, AdbDevice(ip, port, currentWifi, System.currentTimeMillis()))
        
        val trimmedList = if (deviceList.size > 10) deviceList.subList(0, 10) else deviceList
        val jsonArray = JSONArray()
        for (dev in trimmedList) {
            val obj = JSONObject().apply {
                put("ip", dev.ip)
                put("port", dev.port)
                put("wifiSsid", dev.wifiSsid)
                put("lastConnectedTime", dev.lastConnectedTime)
            }
            jsonArray.put(obj)
        }
        prefs.edit {
            putString(KEY_DEVICE_LIST, jsonArray.toString())
        }
    }

    private fun getAllSavedDevices(): List<AdbDevice> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_DEVICE_LIST, null) ?: return emptyList()
        val list = mutableListOf<AdbDevice>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    AdbDevice(
                        ip = obj.getString("ip"),
                        port = obj.getInt("port"),
                        wifiSsid = obj.getString("wifiSsid"),
                        lastConnectedTime = obj.getLong("lastConnectedTime")
                    )
                )
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    private fun getCurrentWifiSsid(): String {
        try {
            val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val activeNetwork = connectivityManager?.activeNetwork
                val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)

                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val wifiInfo = capabilities.transportInfo as? WifiInfo
                    if (wifiInfo != null) {
                        val ssid = wifiInfo.ssid.removeSurrounding("\"")
                        if (isValidSsid(ssid)) {
                            return ssid
                        }
                    }
                }
            }

            @Suppress("DEPRECATION")
            val wifiManager = applicationContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val info = wifiManager?.connectionInfo

            if (info != null) {
                val ssid = info.ssid.removeSurrounding("\"")
                if (isValidSsid(ssid)) {
                    return ssid
                }
            }
        } catch (e: Exception) {
            Log.e("adbKitty", "获取无线SSID受限", e)
        }
        return "DEFAULT_WIFI"
    }

    private fun isValidSsid(ssid: String?): Boolean {
        if (ssid.isNullOrEmpty()) return false
        return !ssid.equals("<unknown ssid>", ignoreCase = true)
    }

    fun handleWifiConnectionFlow() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        isWifiEnabled = wifiManager.isWifiEnabled
        appendLog("[提示] 🚀 初始 WLAN 状态: isWifiEnabled = $isWifiEnabled")
        if (checkAndRequestWifiPermission()) {
            if (isWifiEnabled) executeAutoWifiConnect()
            else appendLog("[警告] 📡 自动回连已跳过：手机 WLAN 开关当前未开启。")
        }
    }

    private fun getWifiScanPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
    }
    
    private fun checkAndRequestWifiPermission(): Boolean {
        val permissionsToRequest = mutableListOf<String>()
        val wifiPermission = getWifiScanPermission()
        if (ContextCompat.checkSelfPermission(this, wifiPermission) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(wifiPermission)
        }

        if (Build.VERSION.SDK_INT >= 37) {
            val localNetPermission = "android.permission.ACCESS_LOCAL_NETWORK"
            if (ContextCompat.checkSelfPermission(this, localNetPermission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(localNetPermission)
            }
        }

        return if (permissionsToRequest.isNotEmpty()) {
            requestNetworkPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
            false
        } else {
            true
        }
    }

    fun startIpNetworkTest() {
        // 合并为一个统一的 IO 协程流，确保控制台输出的时序绝对工整不乱序
        lifecycleScope.launch(Dispatchers.Main) {
            appendLog("[网络探针] 正在唤醒底层网络数据透视...")

            val ipManager = IpManager()
            
            // 1. 抓取本地物理与虚拟网卡快照
            val localIp = withContext(Dispatchers.IO) { ipManager.getAllLocalIpAddresses() }
            appendLog("[本地 Wi-Fi 网卡] IPv4: ${localIp.wifiIpv4 ?: "未连接"}")
            appendLog("[本地 Wi-Fi 网卡] IPv6: ${localIp.wifiIpv6 ?: "无IPv6"}")
            appendLog("[本地移动网卡] IPv4: ${localIp.mobileIpv4 ?: "未开启"}")
            appendLog("[本地移动网卡] IPv6: ${localIp.mobileIpv6 ?: "无IPv6"}")
            appendLog("[本地 VPN 网卡] IPv4: ${localIp.vpnIpv4 ?: "未创建"}")
            
            // 2. 串行测试全球 IPv4 出口（多节点测绘防御单点崩溃）
            appendLog("[探针] 正在向全球 IPv4 节点发射探测信标...")
            val vpnOuterIpv4 = fetchIpFromWeb("https://api.ipify.org")
            appendLog("[外网出口] 测试 IPv4 (api.ipify.org) -> ${vpnOuterIpv4 ?: "连接失败(可能无v4网络或代理断开)"}")
            
            val vpnOuterIpv41 = fetchIpFromWeb("https://v4.ident.me")
            appendLog("[外网出口] 测试 IPv4 (v4.ident.me) -> ${vpnOuterIpv41 ?: "连接失败(可能无v4网络或代理断开)"}")
            
            val vpnOuterIpv42 = fetchIpFromWeb("https://ipv4.icanhazip.com")
            appendLog("[外网出口] 测试 IPv4 (ipv4.icanhazip.com) -> ${vpnOuterIpv42 ?: "连接失败(可能无v4网络或代理断开)"}")
            
            val vpnOuterIpv43 = fetchIpFromWeb("https://myip.dnsomatic.com")
            appendLog("[外网出口] 测试 IPv4 (myip.dnsomatic.com) -> ${vpnOuterIpv43 ?: "连接失败(可能无v4网络或代理断开)"}")
            
            val vpnOuterIpv44 = fetchIpFromWeb("https://api-ipv4.ip.sb/ip")
            appendLog("[外网出口] 测试 IPv4 (api-ipv4.ip.sb/ip) -> ${vpnOuterIpv44 ?: "连接失败(可能无v4网络或代理断开)"}")

            // 3. 串行测试全球 IPv6 出口
            appendLog("[探针] 正在向全球 IPv6 节点发射探测信标...")
            val vpnOuterIpv6 = fetchIpFromWeb("https://api6.ipify.org")
            appendLog("[外网出口] 测试 IPv6 (api6.ipify.org) -> ${vpnOuterIpv6 ?: "连接失败(可能代理不支持v6或网络无v6)"}")
            
            val vpnOuterIpv61 = fetchIpFromWeb("https://v6.ident.me")
            appendLog("[外网出口] 测试 IPv6 (v6.ident.me) -> ${vpnOuterIpv61 ?: "连接失败(可能代理不支持v6或网络无v6)"}")
            
            val vpnOuterIpv62 = fetchIpFromWeb("https://ipv6.icanhazip.com")
            appendLog("[外网出口] 测试 IPv6 (ipv6.icanhazip.com) -> ${vpnOuterIpv62 ?: "连接失败(可能代理不支持v6或网络无v6)"}")
            
            val vpnOuterIpv63 = fetchIpFromWeb("https://api-ipv6.ip.sb/ip")
            appendLog("[外网出口] 测试 IPv6 (api-ipv6.ip.sb/ip) -> ${vpnOuterIpv63 ?: "连接失败(可能代理不支持v6或网络无v6)"}")
            
            // 4. 双通道直连骨干网测试
            appendLog("[探针] 正在评测全球骨干网连通度...")
            val isV4Ok = verifyGoogleOutbound("https://ipv4.google.com/generate_204")
            val isV6Ok = verifyGoogleOutbound("https://ipv6.google.com/generate_204")
            appendLog("[Google通道] 物理/VPN IPv4 直连状态: ${if(isV4Ok) "🟢 畅通" else "🔴 阻塞"}")
            appendLog("[Google通道] 物理/VPN IPv6 直连状态: ${if(isV6Ok) "🟢 畅通" else "🔴 阻塞"}")
            
            // 5. 抓取本地虚拟 VPN 网卡底层的 IPv6 状态
            val vpnIpManager = VpnIpManager()
            val localVpnIpv6 = withContext(Dispatchers.IO) { vpnIpManager.getLocalVpnIpv6(applicationContext) }
            if (localVpnIpv6 != null) {
                appendLog("[本地 VPN 网卡] 成功抓取本地 VPN IPv6 地址: $localVpnIpv6")
            } else {
                appendLog("[本地 VPN 网卡] 提示: 未检测到本地 VPN 的 IPv6 地址 (VPN未开启，或该VPN软件底层未分配IPv6虚拟网卡)")
            }
            
            appendLog("[系统] === 全网环境深度检测结束 ===")
        }
    }
    
    private suspend fun verifyGoogleOutbound(urlString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            return@withContext conn.responseCode == 204
        } catch (e: Exception) {
            return@withContext false
        }
    }
    
    private suspend fun fetchIpFromWeb(urlString: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                    reader.readLine()?.trim()
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    override fun onDestroy() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        stopAdbService()
        currentShellJob?.cancel()
        viewModel.setAdbService(null)
        super.onDestroy()
        readerJob?.cancel()
        usbConn?.close()
        unregisterReceiver(usbPermissionReceiver)
        unregisterReceiver(usbStateReceiver)
        unregisterReceiver(wifiReceiver)
        unregisterReceiver(powerReceiver)
        usbForwarder?.stop()
        runCatching { kadbInstance?.close() }
    }
}
