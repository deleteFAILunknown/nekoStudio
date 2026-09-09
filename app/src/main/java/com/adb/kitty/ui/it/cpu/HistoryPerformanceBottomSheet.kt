package com.adb.kitty.ui.it.cpu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryPerformanceBottomSheet(
    uiState: PerformanceUiState,
    onSelectFile: (File) -> Unit,
    onDeleteFile: (File) -> Unit,
    onBackToList: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden
    )

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            // 顶部导航标题
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.selectedHistory != null) {
                    TextButton(onClick = onBackToList) {
                        Text("← 返回列表", fontSize = 12.sp)
                    }
                    Text(
                        text = "📈 历史数据图表回放",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.width(48.dp))
                } else {
                    Text(
                        text = "📁 CPU/GPU 历史录制日志",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${uiState.historyFiles.size} 个文件",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }

            // 展示内容区域
            if (uiState.selectedHistory != null) {
                HistoryGraphView(history = uiState.selectedHistory)
            } else {
                HistoryFileList(
                    files = uiState.historyFiles,
                    onSelectFile = onSelectFile,
                    onDeleteFile = onDeleteFile
                )
            }
        }
    }
}

// 1. 历史文件列表视图
@Composable
private fun HistoryFileList(
    files: List<File>,
    onSelectFile: (File) -> Unit,
    onDeleteFile: (File) -> Unit
) {
    if (files.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("暂无录制历史，请先在实时监控面板中点击录制", fontSize = 12.sp, color = Color.Gray)
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(files) { file ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectFile(file) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = file.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "大小: ${file.length() / 1024} KB",
                                fontSize = 10.sp,
                                color = Color.Gray
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { onSelectFile(file) },
                                modifier = Modifier.height(30.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                            ) {
                                Text("查看图表", fontSize = 10.sp)
                            }

                            IconButton(
                                onClick = { onDeleteFile(file) },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Text("🗑", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryGraphView(history: HistoryRecording) {
    val samples = history.samples
    if (samples.isEmpty()) {
        Text("文件内容为空或格式不匹配", color = Color.Red, fontSize = 12.sp)
        return
    }

    val maxFps = samples.maxOfOrNull { it.fps } ?: 0f
    val maxRefreshRate = samples.maxOfOrNull { it.refreshRate } ?: 0f

    val maxTemp = samples.maxOfOrNull { it.batteryTemp } ?: 0f
    val maxCurrent = samples.maxOfOrNull { it.batteryCurrentMa } ?: 0f
    val maxVolt = (samples.maxOfOrNull { it.batteryVoltageMv } ?: 0f) / 1000f
    val maxPower = samples.maxOfOrNull { it.batteryPowerW } ?: 0f
    val lastBatteryLevel = samples.lastOrNull()?.batteryLevel ?: 0
    val lastStatus = samples.lastOrNull()?.batteryStatus ?: "Unknown"
    val lastChargeType = samples.lastOrNull()?.batteryChargeType ?: "Unknown"
    val lastHealth = samples.lastOrNull()?.batteryHealth ?: "Unknown"
    val lastCycleCount = samples.lastOrNull()?.batteryCycleCount ?: 0
    val lastFullMah = samples.lastOrNull()?.batteryFullMah ?: 0f
    val lastDesignMah = samples.lastOrNull()?.batteryFullDesignMah ?: 0f
    val lastSoh = samples.lastOrNull()?.batterySohPercent ?: 0f

    val maxCpuCount = samples.maxOfOrNull { it.cpuFreqsGhz.size } ?: 0

    val lastCellTotal = samples.lastOrNull()?.cellTotalMb ?: 0f
    val lastWlanTotal = samples.lastOrNull()?.wlanTotalMb ?: 0f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SummaryItem(
                    label = "时长",
                    value = "${history.durationSeconds} s"
                )
                SummaryItem(
                    label = "最高帧率",
                    value = String.format(Locale.US, "%.2f FPS", maxFps),
                    valueColor = Color(0xFF4CAF50)
                )
                SummaryItem(
                    label = "最高刷新率",
                    value = String.format(Locale.US, "%.2f Hz", maxRefreshRate),
                    valueColor = Color(0xFF00BCD4)
                )
                SummaryItem(
                    label = "电池电量",
                    value = "$lastBatteryLevel%",
                    valueColor = Color(0xFFFF5722)
                )
                SummaryItem(
                    label = "电池最高温度",
                    value = "${String.format(Locale.US, "%.1f", maxTemp)}°C",
                    valueColor = Color(0xFFFF5722)
                )
                SummaryItem(
                    label = "最高放电电流",
                    value = String.format(Locale.US, "%.0f mA", maxCurrent),
                    valueColor = Color(0xFFFF9800)
                )
                SummaryItem(
                    label = "最高功耗",
                    value = String.format(Locale.US, "%.2f W", maxPower),
                    valueColor = Color(0xFFE91E63)
                )
                SummaryItem(
                    label = "最高电压",
                    value = String.format(Locale.US, "%.2fV", maxVolt),
                    valueColor = Color(0xFFFFC107)
                )
                SummaryItem(
                    label = "电池SoH健康度",
                    value = if (lastSoh > 0f) String.format(Locale.US, "%.1f%%", lastSoh) else "Unknown",
                    valueColor = Color(0xFF4CAF50)
                )
                SummaryItem(
                    label = "循环次数",
                    value = if (lastCycleCount > 0) "$lastCycleCount 次" else "未知",
                    valueColor = Color(0xFF00BCD4)
                )
                SummaryItem(
                    label = "电池状态",
                    value = lastStatus
                )
                SummaryItem(
                    label = "充电类型",
                    value = lastChargeType
                )
                SummaryItem(
                    label = "健康状况",
                    value = lastHealth
                )
                SummaryItem(
                    label = "满电/设计容量",
                    value = String.format(Locale.US, "%.0f / %.0f mAh", lastFullMah, lastDesignMah)
                )
                SummaryItem(
                    label = "WLAN流量",
                    value = formatMb(lastWlanTotal),
                    valueColor = Color(0xFF00BCD4)
                )
                SummaryItem(
                    label = "蜂窝流量",
                    value = formatMb(lastCellTotal),
                    valueColor = Color(0xFF00BCD4)
                )
            }
        }

        // 2. FPS 历史趋势图
        HistoryChartCard(
            title = "帧率波动 (FPS)",
            data = samples.map { it.fps },
            maxVal = samples.maxOfOrNull { it.refreshRate }?.coerceAtLeast(60f) ?: 60f,
            lineColor = Color(0xFF4CAF50),
            unit = "FPS"
        )

        // 2.1 屏幕刷新率历史趋势图
        val refreshRateList = samples.map { it.refreshRate }
        val maxRefreshRate = refreshRateList.maxOrNull() ?: 60f

        HistoryChartCard(
            title = "屏幕刷新率 (Hz)",
            limitText = String.format(Locale.US, "档位区间: %.0f Hz - %.0f Hz", refreshRateList.minOrNull() ?: 0f, maxRefreshRate),
            data = refreshRateList,
            maxVal = maxRefreshRate,
            lineColor = Color(0xFF00BCD4),
            unit = "Hz",
            valueFormat = "%.0f"
        )

        val wlanTotalStr = formatMb(lastWlanTotal)
        val cellTotalStr = formatMb(lastCellTotal)

        // WLAN 上传
        val wlanTx = autoScaleKbpsList(samples.map { it.wlanTxSpeedKbps })
        HistoryChartCard(
            title = "WLAN 上传网速 (${wlanTx.unit})",
            limitText = String.format(Locale.US, "录制总流量: %s | 丢包率: %.2f%%", wlanTotalStr, samples.lastOrNull()?.wlanLossRate ?: 0f),
            data = wlanTx.data,
            maxVal = wlanTx.maxVal,
            lineColor = Color(0xFF0288D1),
            unit = wlanTx.unit,
            valueFormat = wlanTx.valueFormat
        )

        // WLAN 下载
        val wlanRx = autoScaleKbpsList(samples.map { it.wlanRxSpeedKbps })
        HistoryChartCard(
            title = "WLAN 下载网速 (${wlanRx.unit})",
            limitText = String.format(Locale.US, "录制总流量: %s | 丢包率: %.2f%%", wlanTotalStr, samples.lastOrNull()?.wlanLossRate ?: 0f),
            data = wlanRx.data,
            maxVal = wlanRx.maxVal,
            lineColor = Color(0xFF00BCD4),
            unit = wlanRx.unit,
            valueFormat = wlanRx.valueFormat
        )

        // 蜂窝上传
        val cellTx = autoScaleKbpsList(samples.map { it.cellTxSpeedKbps })
        HistoryChartCard(
            title = "蜂窝网络上传网速 (${cellTx.unit})",
            limitText = String.format(Locale.US, "录制总流量: %s | 丢包率: %.2f%%", cellTotalStr, samples.lastOrNull()?.cellLossRate ?: 0f),
            data = cellTx.data,
            maxVal = cellTx.maxVal,
            lineColor = Color(0xFFC2185B),
            unit = cellTx.unit,
            valueFormat = cellTx.valueFormat
        )

        // 蜂窝下载
        val cellRx = autoScaleKbpsList(samples.map { it.cellRxSpeedKbps })
        HistoryChartCard(
            title = "蜂窝网络下载网速 (${cellRx.unit})",
            limitText = String.format(Locale.US, "录制总流量: %s | 丢包率: %.2f%%", cellTotalStr, samples.lastOrNull()?.cellLossRate ?: 0f),
            data = cellRx.data,
            maxVal = cellRx.maxVal,
            lineColor = Color(0xFFE91E63),
            unit = cellRx.unit,
            valueFormat = cellRx.valueFormat
        )

        // RAM 可用内存
        val ramAvailList = samples.map { it.ramAvailGb }
        val ramMin = ramAvailList.minOrNull() ?: 0f
        val ramMax = ramAvailList.maxOrNull() ?: 0f
        val ramTotal = samples.lastOrNull()?.ramTotalGb ?: 1f
        val ramDeltaMax = (ramMax - ramMin).coerceAtLeast(0.3f)

        HistoryChartCard(
            title = "RAM 可用内存 (GB)",
            limitText = String.format(Locale.US, "RAM 总量: %.3f GB", ramTotal),
            data = ramAvailList.map { it - ramMin },
            maxVal = ramDeltaMax,
            lineColor = Color(0xFF2196F3),
            unit = "GB",
            valueFormat = "%.3f",
            valueOffset = ramMin
        )

        // ZRAM 可用内存
        val zramAvailList = samples.map { it.zramAvailGb }
        val zramMin = zramAvailList.minOrNull() ?: 0f
        val zramMax = zramAvailList.maxOrNull() ?: 0f
        val zramTotal = samples.lastOrNull()?.zramTotalGb ?: 1f
        val zramDeltaMax = (zramMax - zramMin).coerceAtLeast(0.3f)

        HistoryChartCard(
            title = "ZRAM 可用内存 (GB)",
            limitText = String.format(Locale.US, "ZRAM 总量: %.3f GB", zramTotal),
            data = zramAvailList.map { it - zramMin },
            maxVal = zramDeltaMax,
            lineColor = Color(0xFF00BCD4),
            unit = "GB",
            valueFormat = "%.3f",
            valueOffset = zramMin
        )

        // ROM 读取速率
        val romReadList = samples.map { it.romReadSpeedMb }
        HistoryChartCard(
            title = "ROM 读取速度 (MB/s)",
            data = romReadList,
            maxVal = (romReadList.maxOrNull() ?: 10f).coerceAtLeast(5f),
            lineColor = Color(0xFF3F51B5),
            unit = "MB/s",
            valueFormat = "%.2f"
        )

        // ROM 写入速率
        val romWriteList = samples.map { it.romWriteSpeedMb }
        HistoryChartCard(
            title = "ROM 写入速度 (MB/s)",
            data = romWriteList,
            maxVal = (romWriteList.maxOrNull() ?: 10f).coerceAtLeast(5f),
            lineColor = Color(0xFF673AB7),
            unit = "MB/s",
            valueFormat = "%.2f"
        )

        // 3. 电池温度
        val tempTypeList = samples.map { it.batteryTemp }
        val tempMin = tempTypeList.minOrNull() ?: 0f
        val tempMax = tempTypeList.maxOrNull() ?: 0f
        val tempDeltaMax = (tempMax - tempMin).coerceAtLeast(1.0f)

        HistoryChartCard(
            title = "电池温度 (°C)",
            data = tempTypeList.map { it - tempMin },
            maxVal = tempDeltaMax,
            lineColor = Color(0xFFFF5722),
            unit = "°C",
            valueFormat = "%.1f",
            valueOffset = tempMin
        )

        val batteryLevelList = samples.map { it.batteryLevel.toFloat() }
        val levelMin = batteryLevelList.minOrNull() ?: 0f
        val levelMax = batteryLevelList.maxOrNull() ?: 0f

        HistoryChartCard(
            title = "电池电量 (%)",
            limitText = String.format(Locale.US, "变化区间: %.0f%% - %.0f%% | 最终电量: %d%%", levelMin, levelMax, lastBatteryLevel),
            data = batteryLevelList,
            maxVal = 100f,
            lineColor = Color(0xFFFF5722),
            unit = "%",
            valueFormat = "%.0f"
        )

        // 3.1 电池电压 (V)
        val voltageList = samples.map { it.batteryVoltageMv / 1000f }
        val voltMin = voltageList.minOrNull() ?: 0f
        val voltMax = voltageList.maxOrNull() ?: 0f
        val voltDeltaMax = (voltMax - voltMin).coerceAtLeast(0.1f)

        HistoryChartCard(
            title = "电池电压 (V)",
            limitText = String.format(Locale.US, "范围: %.2f V - %.2f V", voltMin, voltMax),
            data = voltageList.map { it - voltMin },
            maxVal = voltDeltaMax,
            lineColor = Color(0xFFFBBC02),
            unit = "V",
            valueFormat = "%.2f",
            valueOffset = voltMin
        )

        // 3.2 放电电流 (mA)
        val currentList = samples.map { it.batteryCurrentMa }
        val maxCurrent = (currentList.maxOrNull() ?: 1000f).coerceAtLeast(500f)
        HistoryChartCard(
            title = "放电/充电电流 (mA)",
            limitText = "电池电量: $lastBatteryLevel% | 状态: $lastStatus",
            data = currentList,
            maxVal = maxCurrent,
            lineColor = Color(0xFFFF9800),
            unit = "mA",
            valueFormat = "%.0f"
        )

        // 3.3 新增：电池实时功耗/功率 (W)
        val powerList = samples.map { it.batteryPowerW }
        val maxPower = (powerList.maxOrNull() ?: 5f).coerceAtLeast(1f)
        val batCapLimitStr = if (lastFullMah > 0f) {
            String.format(
                Locale.US,
                "SoH: %.1f%% | 循环: %d次 | 容量: %.0f/%.0f mAh",
                lastSoh, lastCycleCount, lastFullMah, lastDesignMah
            )
        } else null

        HistoryChartCard(
            title = "电池实时功率/功耗 (W)",
            limitText = batCapLimitStr,
            data = powerList,
            maxVal = maxPower,
            lineColor = Color(0xFFE91E63),
            unit = "W",
            valueFormat = "%.2f"
        )

        // 4. GPU 负载率
        val gpuLoadList = samples.map { it.gpuLoadPercent }
        val gpuMin = samples.mapNotNull { if (it.gpuMinFreqGhz > 0) it.gpuMinFreqGhz else null }.firstOrNull() ?: 0f
        val gpuMax = samples.mapNotNull { if (it.gpuMaxFreqGhz > 0) it.gpuMaxFreqGhz else null }.firstOrNull() ?: 0f
        val gpuLimitStr = if (gpuMax > 0f) String.format(Locale.US, "Limit: %.3f - %.3f GHz", gpuMin, gpuMax) else null

        HistoryChartCard(
            title = "GPU 负载率 (%)",
            limitText = gpuLimitStr,
            data = gpuLoadList,
            maxVal = 100f,
            lineColor = Color(0xFF9C27B0),
            unit = "%"
        )

        // GPU 运行频率 (GHz)
        val gpuFreqList = samples.map { it.gpuFreqGhz }
        val maxGpuFreq = (gpuFreqList.maxOrNull() ?: 1f).coerceAtLeast(0.5f)
        HistoryChartCard(
            title = "GPU 运行频率 (GHz)",
            limitText = gpuLimitStr,
            data = gpuFreqList,
            maxVal = maxGpuFreq,
            lineColor = Color(0xFFAB47BC),
            unit = "GHz",
            valueFormat = "%.3f"
        )

        // 5. CPU 核心频率
        Text("CPU 核心频率轨迹 (GHz)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (coreIndex in 0 until maxCpuCount) {
            val coreFreqs = samples.map { it.cpuFreqsGhz.getOrNull(coreIndex) ?: 0f }
            val coreLimits = samples.mapNotNull { it.cpuHwLimitsGhz.getOrNull(coreIndex) }.firstOrNull()

            val hwLimitStr = if (coreLimits != null && coreLimits.second > 0f) {
                String.format(Locale.US, "HW: %.3f - %.3f GHz", coreLimits.first, coreLimits.second)
            } else null

            val coreColor = CoreColors.getOrElse(coreIndex) { Color.Gray }
            val maxFreq = coreFreqs.maxOrNull()?.coerceAtLeast(1f) ?: 3f

            HistoryChartCard(
                title = "CPU Core $coreIndex",
                limitText = hwLimitStr,
                data = coreFreqs,
                maxVal = maxFreq,
                lineColor = coreColor,
                unit = "GHz",
                valueFormat = "%.3f"
            )
        }
    }
}

data class AutoScaledSpeedData(
    val data: List<Float>,
    val maxVal: Float,
    val unit: String,
    val valueFormat: String
)

fun formatKbps(kbps: Float): String {
    return when {
        kbps >= 1024f * 1024f -> String.format(Locale.US, "%.2f GB/s", kbps / (1024f * 1024f))
        kbps >= 1024f -> String.format(Locale.US, "%.2f MB/s", kbps / 1024f)
        else -> String.format(Locale.US, "%.0f KB/s", kbps)
    }
}

fun autoScaleKbpsList(kbpsList: List<Float>): AutoScaledSpeedData {
    val maxKbps = kbpsList.maxOrNull() ?: 0f
    return when {
        // 大于等于 1 GB/s (1,048,576 KB/s)
        maxKbps >= 1024f * 1024f -> AutoScaledSpeedData(
            data = kbpsList.map { it / (1024f * 1024f) },
            maxVal = (maxKbps / (1024f * 1024f)).coerceAtLeast(0.1f),
            unit = "GB/s",
            valueFormat = "%.2f"
        )
        // 大于等于 1 MB/s (1,024 KB/s)
        maxKbps >= 1024f -> AutoScaledSpeedData(
            data = kbpsList.map { it / 1024f },
            maxVal = (maxKbps / 1024f).coerceAtLeast(0.5f),
            unit = "MB/s",
            valueFormat = "%.2f"
        )
        // 保持 KB/s
        else -> AutoScaledSpeedData(
            data = kbpsList,
            maxVal = maxKbps.coerceAtLeast(50f),
            unit = "KB/s",
            valueFormat = "%.0f"
        )
    }
}

@Composable
private fun SummaryItem(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label, 
            fontSize = 10.sp, 
            color = Color.Gray,
            maxLines = 1
        )
        Text(
            text = value,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = valueColor,
            maxLines = 1
        )
    }
}

@Composable
private fun HistoryChartCard(
    title: String,
    limitText: String? = null,
    data: List<Float>,
    maxVal: Float,
    lineColor: Color,
    unit: String,
    valueFormat: String = "%.2f",
    valueOffset: Float = 0f
) {
    val minVal = (if (data.isNotEmpty()) data.minOrNull() ?: 0f else 0f) + valueOffset
    val maxValReal = (if (data.isNotEmpty()) data.maxOrNull() ?: 0f else 0f) + valueOffset
    val avgVal = (if (data.isNotEmpty()) data.average().toFloat() else 0f) + valueOffset

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    if (!limitText.isNullOrEmpty()) {
                        Text(
                            text = limitText,
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = String.format(Locale.US, "最高: $valueFormat %s", maxValReal, unit),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = lineColor
                    )
                    Text(
                        text = String.format(Locale.US, "平均: $valueFormat %s", avgVal, unit),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = lineColor
                    )
                    Text(
                        text = String.format(Locale.US, "最低: $valueFormat %s", minVal, unit),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = lineColor
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            MetricLineChart(
                data = data,
                maxVal = maxVal,
                lineColor = lineColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            )
        }
    }
}
