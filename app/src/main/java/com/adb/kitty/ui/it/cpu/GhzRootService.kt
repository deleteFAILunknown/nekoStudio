package com.adb.kitty.ui.it.cpu

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import androidx.annotation.Keep
import com.topjohnwu.superuser.ipc.RootService
import java.io.File

@Keep
class GhzRootService : RootService() {

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
        "/sys/class/power_supply/bms/temp"
    )

    private val CAPACITY_PATHS = arrayOf(
        "/sys/class/power_supply/battery/capacity",
        "/sys/class/power_supply/bms/capacity"
    )

    private val CURRENT_PATHS = arrayOf(
        "/sys/class/power_supply/battery/current_now",
        "/sys/class/power_supply/bms/current_now"
    )

    private val VOLTAGE_PATHS = arrayOf(
        "/sys/class/power_supply/battery/voltage_now",
        "/sys/class/power_supply/bms/voltage_now"
    )

    private val STATUS_PATHS = arrayOf(
        "/sys/class/power_supply/battery/status",
        "/sys/class/power_supply/bms/status"
    )

    fun readMetrics(): Bundle {
        val bundle = Bundle()

        // 1. 读取温度 (°C)
        var rawTemp = readFirstAvailableFloat(TEMP_PATHS)
        val tempSec = when {
            rawTemp > 1000f -> rawTemp / 1000f
            rawTemp > 100f -> rawTemp / 10f
            else -> rawTemp
        }
        bundle.putFloat("battery_temp", tempSec)

        // 2. 读取电量百分比 (%)
        val capacity = readFirstAvailableFloat(CAPACITY_PATHS).toInt()
        bundle.putInt("battery_level", capacity)

        // 3. 读取电流 (mA) - 保持原始正负号输出（负数代表放电，正数代表充电）
        var rawCurrent = readFirstAvailableFloat(CURRENT_PATHS)
        val currentMa = if (abs(rawCurrent) > 10000f) {
            rawCurrent / 1000f
        } else {
            rawCurrent
        }
        bundle.putFloat("battery_current_ma", currentMa)

        // 4. 读取电压 (mV)
        var rawVoltage = readFirstAvailableFloat(VOLTAGE_PATHS)
        val voltageMv = if (rawVoltage > 1000000f) {
            rawVoltage / 1000f
        } else {
            rawVoltage
        }
        bundle.putFloat("battery_voltage_mv", voltageMv)

        // 5. 读取充电状态（直接信任节点返回的 "Discharging", "Charging", "Full" 等字符串）
        val status = readFirstAvailableString(STATUS_PATHS)
        bundle.putString("battery_status", status)

        return bundle
    }

    private fun readFirstAvailableFloat(paths: Array<String>): Float {
        for (path in paths) {
            val file = File(path)
            if (file.exists()) {
                try {
                    val text = file.readText().trim()
                    val value = text.toFloatOrNull()
                    if (value != null) return value
                } catch (_: Exception) {
                }
            }
        }
        return 0f
    }

    private fun readFirstAvailableString(paths: Array<String>): String {
        for (path in paths) {
            val file = File(path)
            if (file.exists()) {
                try {
                    val text = file.readText().trim()
                    if (text.isNotEmpty()) return text
                } catch (_: Exception) {
                }
            }
        }
        return "Unknown"
    }
}
