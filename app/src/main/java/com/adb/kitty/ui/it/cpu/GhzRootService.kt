package com.adb.kitty.ui.it.cpu

import android.content.Context
import android.content.Intent
import android.view.Display
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import androidx.annotation.Keep
import com.topjohnwu.superuser.ipc.RootService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

@Keep
class GhzRootService : RootService() {

    private val displayManager by lazy {
        getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    }

    override fun onBind(intent: Intent): IBinder {
        return object : ICpuBinder.Stub() {

            override fun getCpuCurrentFreqs(): FloatArray {
                val freqs = ArrayList<Float>()
                var index = 0
                while (true) {
                    val cpuDir = File("/sys/devices/system/cpu/cpu$index")
                    if (!cpuDir.exists()) break

                    val curFile = File(cpuDir, "cpufreq/cpuinfo_cur_freq")
                    val khz = curFile.readTextOrZero()

                    freqs.add(khz / 1_000_000f) // kHz -> GHz
                    index++
                }
                return freqs.toFloatArray()
            }

            override fun getCpuCoreLimits(core: Int): FloatArray {
                val cpuDir = File("/sys/devices/system/cpu/cpu$core/cpufreq")
                if (!cpuDir.exists()) return floatArrayOf(0f, 0f, 0f)

                val minKhz = File(cpuDir, "cpuinfo_min_freq").readTextOrZero()
                val maxKhz = File(cpuDir, "cpuinfo_max_freq").readTextOrZero()
                val curKhz = File(cpuDir, "cpuinfo_cur_freq").readTextOrZero()

                return floatArrayOf(
                    minKhz / 1_000_000f,
                    maxKhz / 1_000_000f,
                    curKhz / 1_000_000f
                )
            }

            override fun getGpuMetrics(): FloatArray {
                val curHz = File("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq").readTextOrZero()
                val minHz = File("/sys/class/kgsl/kgsl-3d0/devfreq/min_freq").readTextOrZero()
                val maxHz = File("/sys/class/kgsl/kgsl-3d0/devfreq/max_freq").readTextOrZero()
                val load = File("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage").readTextOrZero()

                return floatArrayOf(
                    curHz / 1_000_000_000f, // Hz -> GHz
                    minHz / 1_000_000_000f,
                    maxHz / 1_000_000_000f,
                    load
                )
            }

            override fun getSystemMetrics(): FloatArray {
                val batPaths = arrayOf(
                    "/sys/class/power_supply/battery/temp",
                    "/sys/class/power_supply/bms/temp"
                )
                var batRaw = 0f
                for (path in batPaths) {
                    val file = File(path)
                    if (file.exists()) {
                        batRaw = file.readTextOrZero()
                        if (batRaw > 0f) break
                    }
                }
                val temp = when {
                    batRaw > 1000f -> batRaw / 1000f
                    batRaw > 100f -> batRaw / 10f
                    else -> batRaw
                }

                return floatArrayOf(temp)
            }

            private fun File.readTextOrZero(): Float {
                return try {
                    if (exists()) readText().trim().replace("%", "").toFloatOrNull() ?: 0f else 0f
                } catch (e: Exception) {
                    0f
                }
            }
            
            override fun getNetworkStats(): LongArray {
                var cellRxB = 0L
                var cellTxB = 0L
                var cellRxP = 0L
                var cellTxP = 0L
                var cellErrDropRx = 0L
                var cellErrDropTx = 0L
                var wlanRxB = 0L
                var wlanTxB = 0L
                var wlanRxP = 0L
                var wlanTxP = 0L
                var wlanErrDropRx = 0L
                var wlanErrDropTx = 0L

                try {
                    File("/proc/net/dev").forEachLine { line ->
                        val trimmed = line.trim()
                        if (!trimmed.contains(":")) return@forEachLine

                        val parts = trimmed.split(":", limit = 2)
                        if (parts.size != 2) return@forEachLine

                        val iface = parts[0].trim().lowercase()

                        if (iface == "lo" || iface.startsWith("rmnet_ipa")) return@forEachLine

                        val stats = parts[1].trim().split("\\s+".toRegex())
                        if (stats.size >= 12) {
                            val rxB = stats[0].toLongOrNull() ?: 0L
                            val rxP = stats[1].toLongOrNull() ?: 0L
                            val rxE = stats[2].toLongOrNull() ?: 0L
                            val rxD = stats[3].toLongOrNull() ?: 0L
                            val txB = stats[8].toLongOrNull() ?: 0L
                            val txP = stats[9].toLongOrNull() ?: 0L
                            val txE = stats[10].toLongOrNull() ?: 0L
                            val txD = stats[11].toLongOrNull() ?: 0L

                            if (iface.startsWith("wlan") || iface.startsWith("ap") || 
                            iface.startsWith("p2p") || iface.startsWith("swlan")) {
                                wlanRxB += rxB; wlanTxB += txB; wlanRxP += rxP; wlanTxP += txP
                                wlanErrDropRx += (rxE + rxD); wlanErrDropTx += (txE + txD)
                            }

                            else if (iface.startsWith("rmnet") || iface.startsWith("ccmni") ||
                                iface.startsWith("pdp") || iface.startsWith("wwan") ||
                                iface.startsWith("v4-") || iface.startsWith("seth") || 
                                iface.startsWith("pnd") || iface.startsWith("clat") ||
                                iface.startsWith("usb")) {
                                cellRxB += rxB; cellTxB += txB; cellRxP += rxP; cellTxP += txP
                                cellErrDropRx += (rxE + rxD); cellErrDropTx += (txE + txD)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                return longArrayOf(
                    cellRxB, cellTxB, cellRxP, cellTxP, cellErrDropRx, cellErrDropTx,
                    wlanRxB, wlanTxB, wlanRxP, wlanTxP, wlanErrDropRx, wlanErrDropTx
                )
            }
            
            override fun getMeasuredFps(): Float {
                return readHardwareFps()
            }
            
            override fun getDiskStats(): LongArray {
                var readSectors = 0L
                var writeSectors = 0L

                // 1. 读取 /proc/diskstats 累加主存储芯片扇区数
                try {
                    val diskstatsFile = File("/proc/diskstats")
                    if (diskstatsFile.exists()) {
                        diskstatsFile.forEachLine { line ->
                            val parts = line.trim().split("\\s+".toRegex())
                            if (parts.size >= 14) {
                                val devName = parts[2]
                                // 匹配 UFS(sda/sdb...), eMMC(mmcblk0), NVMe(nvme0n1) 等主块设备
                                if (devName.matches(Regex("^(sd[a-z]|mmcblk[0-9]|nvme[0-9]n[0-9])$"))) {
                                    readSectors += parts[5].toLongOrNull() ?: 0L
                                    writeSectors += parts[9].toLongOrNull() ?: 0L
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // 2. 获取 /data 分区存储空间大小
                var totalBytes = 0L
                var availBytes = 0L
                try {
                    val stat = StatFs(Environment.getDataDirectory().path)
                    totalBytes = stat.blockCountLong * stat.blockSizeLong
                    availBytes = stat.availableBlocksLong * stat.blockSizeLong
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                return longArrayOf(readSectors, writeSectors, totalBytes, availBytes)
            }
            
            override fun getBatteryMetrics(): Bundle {
                return BatterySysfsReader.readMetrics()
            }

            override fun getCurrentResolution(): String {
                val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return ""
                val currentMode = display.mode
                val curW = maxOf(currentMode.physicalWidth, currentMode.physicalHeight)
                val curH = minOf(currentMode.physicalWidth, currentMode.physicalHeight)
                return "${curW}×${curH}"
            }

            override fun getSupportedDisplayModes(): List<String> {
                val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptyList()
                return display.supportedModes.map { mode ->
                    val w = maxOf(mode.physicalWidth, mode.physicalHeight)
                    val h = minOf(mode.physicalWidth, mode.physicalHeight)
                    val hz = mode.refreshRate.toInt()
                    "${w}×${h} @ ${hz}Hz"
                }.distinct()
            }

            override fun getActiveRefreshRate(): Float {
                val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
                return display?.refreshRate ?: display?.mode?.refreshRate ?: 60f
            }
        }
    }
    
    private fun readHardwareFps(): Float {
        val nodePaths = arrayOf(
            "/sys/devices/platform/soc/ae00000.qcom,mdss_mdp/drm/card0/sde-crtc-0/measured_fps",
            "/sys/class/drm/card0/sde-crtc-0/measured_fps"
        )

        for (path in nodePaths) {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                try {
                    val content = file.readText().trim()
                    // 解析格式如 "fps: 51.4 duration:500000 frame_count:26"
                    val matchResult = Regex("""fps:\s*([0-9.]+)""").find(content)
                    if (matchResult != null) {
                        return matchResult.groupValues[1].toFloatOrNull() ?: 0f
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return 0f
    }
}

object BatterySysfsReader {

    private val TEMP_PATHS = arrayOf(
        "/sys/class/power_supply/battery/temp",
        "/sys/class/power_supply/bms/temp",
        "/sys/class/power_supply/google_battery/temp"
    )

    private val CAPACITY_PATHS = arrayOf(
        "/sys/class/power_supply/battery/capacity",
        "/sys/class/power_supply/bms/capacity"
    )

    private val CURRENT_PATHS = arrayOf(
        "/sys/class/power_supply/battery/current_now",
        "/sys/class/power_supply/bms/current_now",
        "/sys/class/power_supply/usb/current_now"
    )

    private val VOLTAGE_PATHS = arrayOf(
        "/sys/class/power_supply/battery/voltage_now",
        "/sys/class/power_supply/bms/voltage_now",
        "/sys/class/power_supply/usb/voltage_now"
    )

    private val STATUS_PATHS = arrayOf(
        "/sys/class/power_supply/battery/status",
        "/sys/class/power_supply/bms/status"
    )

    // 新增：充电模式（Fast/Taper/Slow/Trickle 等）
    private val CHARGE_TYPE_PATHS = arrayOf(
        "/sys/class/power_supply/battery/charge_type",
        "/sys/class/power_supply/usb/charge_type",
        "/sys/class/power_supply/main/charge_type"
    )

    // 新增：电池健康度文本（Good/Overheat/Dead 等）
    private val HEALTH_PATHS = arrayOf(
        "/sys/class/power_supply/battery/health",
        "/sys/class/power_supply/bms/health"
    )

    // 新增：电池循环次数
    private val CYCLE_COUNT_PATHS = arrayOf(
        "/sys/class/power_supply/battery/cycle_count",
        "/sys/class/power_supply/bms/cycle_count",
        "/sys/class/power_supply/battery/cycle_counter"
    )

    // 新增：当前满充容量与设计容量（用于计算电池真实健康度 SoH）
    private val CHARGE_FULL_PATHS = arrayOf(
        "/sys/class/power_supply/battery/charge_full",
        "/sys/class/power_supply/bms/charge_full"
    )

    private val CHARGE_FULL_DESIGN_PATHS = arrayOf(
        "/sys/class/power_supply/battery/charge_full_design",
        "/sys/class/power_supply/bms/charge_full_design"
    )

    // 路径缓存，避免高频轮询（如 100ms 采样）时重复查找不存在的文件节点导致多余 IO 开销
    private val resolvedPathCache = ConcurrentHashMap<Array<String>, String>()

    fun readMetrics(): Bundle {
        val bundle = Bundle()

        // 1. 读取温度 (°C)
        val rawTemp = readFloat(TEMP_PATHS)
        val tempSec = when {
            rawTemp > 1000f -> rawTemp / 1000f
            rawTemp > 100f -> rawTemp / 10f
            else -> rawTemp
        }
        bundle.putFloat("battery_temp", tempSec)

        // 2. 读取电量百分比 (%)
        val capacity = readFloat(CAPACITY_PATHS).toInt()
        bundle.putInt("battery_level", capacity)

        // 3. 读取电流 (mA) - 按照 Sysfs 节点规范：正数代表放电，负数代表充电
        val rawCurrent = readFloat(CURRENT_PATHS)
        val currentMa = if (abs(rawCurrent) > 10000f) {
            rawCurrent / 1000f
        } else {
            rawCurrent
        }
        bundle.putFloat("battery_current_ma", currentMa)

        // 4. 读取电压 (mV)
        val rawVoltage = readFloat(VOLTAGE_PATHS)
        val voltageMv = if (rawVoltage > 1000000f) {
            rawVoltage / 1000f
        } else {
            rawVoltage
        }
        bundle.putFloat("battery_voltage_mv", voltageMv)

        // 5. 实时功耗计算 (W) -> P = (V * |I|) / 1,000,000
        // 无论充放电（正负号），功耗计算均取绝对值
        val powerWatts = (voltageMv * abs(currentMa)) / 1_000_000f
        bundle.putFloat("battery_power_w", powerWatts)

        // 6. 充电状态 (Discharging, Charging, Full 等)
        val status = readString(STATUS_PATHS)
        bundle.putString("battery_status", status)

        // 7. 充电模式 (Fast 快充, Taper 恒压, Trickle 涓流 等)
        val chargeType = readString(CHARGE_TYPE_PATHS)
        bundle.putString("battery_charge_type", chargeType)

        // 8. 硬件层健康状态 (Good, Overheat 等)
        val health = readString(HEALTH_PATHS)
        bundle.putString("battery_health", health)

        // 9. 电池充放电循环次数
        val cycleCount = readFloat(CYCLE_COUNT_PATHS).toInt()
        bundle.putInt("battery_cycle_count", cycleCount)

        // 10. 实际满充容量 vs 设计容量 (mAh) & SoH 电池健康度估算
        val rawFull = readFloat(CHARGE_FULL_PATHS)
        val rawFullDesign = readFloat(CHARGE_FULL_DESIGN_PATHS)

        val fullMah = if (rawFull > 10000f) rawFull / 1000f else rawFull
        val fullDesignMah = if (rawFullDesign > 10000f) rawFullDesign / 1000f else rawFullDesign

        bundle.putFloat("battery_charge_full_mah", fullMah)
        bundle.putFloat("battery_charge_full_design_mah", fullDesignMah)

        val sohPercent = if (fullDesignMah > 0f && fullMah > 0f) {
            (fullMah / fullDesignMah * 100f).coerceIn(0f, 100f)
        } else {
            0f
        }
        bundle.putFloat("battery_soh_percent", sohPercent)

        return bundle
    }

    private fun readFloat(paths: Array<String>): Float {
        val text = readTextWithCache(paths) ?: return 0f
        return text.toFloatOrNull() ?: 0f
    }

    private fun readString(paths: Array<String>): String {
        return readTextWithCache(paths) ?: "Unknown"
    }

    /**
     * 命中成功节点后进行路径缓存，防止 100ms 高频轮询时频繁执行 exists() 产生磁盘 IO 瓶颈
     */
    private fun readTextWithCache(paths: Array<String>): String? {
        val cachedPath = resolvedPathCache[paths]
        if (cachedPath != null) {
            return tryReadPath(cachedPath)
        }

        for (path in paths) {
            val content = tryReadPath(path)
            if (content != null) {
                resolvedPathCache[paths] = path
                return content
            }
        }
        return null
    }

    private fun tryReadPath(path: String): String? {
        val file = File(path)
        if (file.exists()) {
            try {
                val text = file.readText().trim()
                if (text.isNotEmpty()) return text
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * 清理缓存（可在插拔充电器或重启服务时调用）
     */
    fun clearCache() {
        resolvedPathCache.clear()
    }
}
