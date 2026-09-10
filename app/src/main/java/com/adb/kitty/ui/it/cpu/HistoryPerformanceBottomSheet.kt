package com.adb.kitty.ui.it.cpu

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.Locale
import kotlin.math.abs

data class DynamicCpuCoreModel(
    val coreIndex: Int,
    val freqs: List<Float>,
    val minVal: Float,
    val maxValReal: Float,
    val avgVal: Float,
    val hwLimitStr: String?,
    val maxChartVal: Float
)

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

    val summaryData = remember(samples) {
        val maxFps = samples.maxOfOrNull { it.fps } ?: 0f
        val maxRefreshRate = samples.maxOfOrNull { it.refreshRate } ?: 0f
        val maxTemp = samples.maxOfOrNull { it.batteryTemp } ?: 0f
        val maxCurrent = samples.maxOfOrNull { it.batteryCurrentMa } ?: 0f
        val maxVolt = (samples.maxOfOrNull { it.batteryVoltageMv } ?: 0f) / 1000f
        val maxPower = samples.maxOfOrNull { it.batteryPowerW } ?: 0f
        val lastSample = samples.lastOrNull()

        val lastBatteryLevel = lastSample?.batteryLevel ?: 0
        val lastStatus = lastSample?.batteryStatus ?: "Unknown"
        val lastChargeType = lastSample?.batteryChargeType ?: "Unknown"
        val lastHealth = lastSample?.batteryHealth ?: "Unknown"
        val lastCycleCount = lastSample?.batteryCycleCount ?: 0
        val lastFullMah = lastSample?.batteryFullMah ?: 0f
        val lastDesignMah = lastSample?.batteryFullDesignMah ?: 0f
        val lastSoh = lastSample?.batterySohPercent ?: 0f
        val lastCellTotal = lastSample?.cellTotalMb ?: 0f
        val lastWlanTotal = lastSample?.wlanTotalMb ?: 0f

        SummaryUiModel(
            maxFps = maxFps,
            maxRefreshRate = maxRefreshRate,
            maxTemp = maxTemp,
            maxCurrent = maxCurrent,
            maxVolt = maxVolt,
            maxPower = maxPower,
            lastBatteryLevel = lastBatteryLevel,
            lastStatus = lastStatus,
            lastChargeType = lastChargeType,
            lastHealth = lastHealth,
            lastCycleCount = lastCycleCount,
            lastFullMah = lastFullMah,
            lastDesignMah = lastDesignMah,
            lastSoh = lastSoh,
            lastCellTotal = lastCellTotal,
            lastWlanTotal = lastWlanTotal
        )
    }

    val cpuCoreModels = remember(samples) {
        val coreCount = samples.maxOfOrNull { it.cpuFreqsGhz.size } ?: 0
        (0 until coreCount).map { coreIndex ->
            val freqs = samples.map { it.cpuFreqsGhz.getOrNull(coreIndex) ?: 0f }
            val limits = samples.mapNotNull { it.cpuHwLimitsGhz.getOrNull(coreIndex) }.firstOrNull()

            val minVal = freqs.minOrNull() ?: 0f
            val maxValReal = freqs.maxOrNull() ?: 0f
            val avgVal = if (freqs.isNotEmpty()) freqs.average().toFloat() else 0f

            val hwLimitStr = if (limits != null && limits.second > 0f) {
                String.format(Locale.US, "HW: %.3f - %.3f GHz", limits.first, limits.second)
            } else null

            DynamicCpuCoreModel(
                coreIndex = coreIndex,
                freqs = freqs,
                minVal = minVal,
                maxValReal = maxValReal,
                avgVal = avgVal,
                hwLimitStr = hwLimitStr,
                maxChartVal = maxValReal.coerceAtLeast(1f)
            )
        }
    }

    val fpsList = remember(samples) { samples.map { it.fps } }
    val maxFpsLimit = remember(samples) { samples.maxOfOrNull { it.refreshRate }?.coerceAtLeast(60f) ?: 60f }

    val refreshRateList = remember(samples) { samples.map { it.refreshRate } }
    val maxRefreshRate = remember(refreshRateList) { refreshRateList.maxOrNull() ?: 60f }

    val wlanTotalStr = remember(summaryData.lastWlanTotal) { formatMb(summaryData.lastWlanTotal) }
    val cellTotalStr = remember(summaryData.lastCellTotal) { formatMb(summaryData.lastCellTotal) }

    val wlanTx = remember(samples) { autoScaleKbpsList(samples.map { it.wlanTxSpeedKbps }) }
    val wlanRx = remember(samples) { autoScaleKbpsList(samples.map { it.wlanRxSpeedKbps }) }
    val cellTx = remember(samples) { autoScaleKbpsList(samples.map { it.cellTxSpeedKbps }) }
    val cellRx = remember(samples) { autoScaleKbpsList(samples.map { it.cellRxSpeedKbps }) }

    val ramData = remember(samples) {
        val list = samples.map { it.ramAvailGb }
        val min = list.minOrNull() ?: 0f
        val max = list.maxOrNull() ?: 0f
        val total = samples.lastOrNull()?.ramTotalGb ?: 1f
        Triple(list.map { it - min }, (max - min).coerceAtLeast(0.3f), min to total)
    }

    val zramData = remember(samples) {
        val list = samples.map { it.zramAvailGb }
        val min = list.minOrNull() ?: 0f
        val max = list.maxOrNull() ?: 0f
        val total = samples.lastOrNull()?.zramTotalGb ?: 1f
        Triple(list.map { it - min }, (max - min).coerceAtLeast(0.3f), min to total)
    }

    val romReadList = remember(samples) { samples.map { it.romReadSpeedMb } }
    val romWriteList = remember(samples) { samples.map { it.romWriteSpeedMb } }

    val tempData = remember(samples) {
        val list = samples.map { it.batteryTemp }
        val min = list.minOrNull() ?: 0f
        val max = list.maxOrNull() ?: 0f
        Pair(list.map { it - min }, (max - min).coerceAtLeast(1.0f) to min)
    }

    val batteryLevelList = remember(samples) { samples.map { it.batteryLevel.toFloat() } }
    val voltageData = remember(samples) {
        val list = samples.map { it.batteryVoltageMv / 1000f }
        val min = list.minOrNull() ?: 0f
        val max = list.maxOrNull() ?: 0f
        Triple(list.map { it - min }, (max - min).coerceAtLeast(0.1f), min to max)
    }

    val currentList = remember(samples) { samples.map { it.batteryCurrentMa } }
    val powerList = remember(samples) { samples.map { it.batteryPowerW } }

    val gpuLoadList = remember(samples) { samples.map { it.gpuLoadPercent } }
    val gpuFreqList = remember(samples) { samples.map { it.gpuFreqGhz } }
    val gpuMin = remember(samples) { samples.mapNotNull { if (it.gpuMinFreqGhz > 0) it.gpuMinFreqGhz else null }.firstOrNull() ?: 0f }
    val gpuMax = remember(samples) { samples.mapNotNull { if (it.gpuMaxFreqGhz > 0) it.gpuMaxFreqGhz else null }.firstOrNull() ?: 0f }
    val gpuLimitStr = remember(gpuMin, gpuMax) { if (gpuMax > 0f) String.format(Locale.US, "Limit: %.3f - %.3f GHz", gpuMin, gpuMax) else null }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
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
                        value = String.format(Locale.US, "%.2f FPS", summaryData.maxFps),
                        valueColor = Color(0xFF4CAF50)
                    )
                    SummaryItem(
                        label = "最高刷新率",
                        value = String.format(Locale.US, "%.2f Hz", summaryData.maxRefreshRate),
                        valueColor = Color(0xFF00BCD4)
                    )
                    SummaryItem(
                        label = "电池电量",
                        value = "${summaryData.lastBatteryLevel}%",
                        valueColor = Color(0xFFFF5722)
                    )
                    SummaryItem(
                        label = "电池最高温度",
                        value = "${String.format(Locale.US, "%.1f", summaryData.maxTemp)}°C",
                        valueColor = Color(0xFFFF5722)
                    )
                    SummaryItem(
                        label = "最高放电电流",
                        value = String.format(Locale.US, "%.0f mA", summaryData.maxCurrent),
                        valueColor = Color(0xFFFF9800)
                    )
                    SummaryItem(
                        label = "最高功耗",
                        value = String.format(Locale.US, "%.2f W", summaryData.maxPower),
                        valueColor = Color(0xFFE91E63)
                    )
                    SummaryItem(
                        label = "最高电压",
                        value = String.format(Locale.US, "%.2fV", summaryData.maxVolt),
                        valueColor = Color(0xFFFFC107)
                    )
                    SummaryItem(
                        label = "电池SoH健康度",
                        value = if (summaryData.lastSoh > 0f) String.format(Locale.US, "%.1f%%", summaryData.lastSoh) else "Unknown",
                        valueColor = Color(0xFF4CAF50)
                    )
                    SummaryItem(
                        label = "循环次数",
                        value = if (summaryData.lastCycleCount > 0) "${summaryData.lastCycleCount} 次" else "未知",
                        valueColor = Color(0xFF00BCD4)
                    )
                    SummaryItem(
                        label = "电池状态",
                        value = summaryData.lastStatus
                    )
                    SummaryItem(
                        label = "充电类型",
                        value = summaryData.lastChargeType
                    )
                    SummaryItem(
                        label = "健康状况",
                        value = summaryData.lastHealth
                    )
                    SummaryItem(
                        label = "满电/设计容量",
                        value = String.format(Locale.US, "%.0f / %.0f mAh", summaryData.lastFullMah, summaryData.lastDesignMah)
                    )
                    SummaryItem(
                        label = "WLAN流量",
                        value = wlanTotalStr,
                        valueColor = Color(0xFF00BCD4)
                    )
                    SummaryItem(
                        label = "蜂窝流量",
                        value = cellTotalStr,
                        valueColor = Color(0xFF00BCD4)
                    )
                }
            }
        }

        item {
            HistoryChartCard(
                title = "帧率波动 (FPS)",
                data = fpsList,
                maxVal = maxFpsLimit,
                lineColor = Color(0xFF4CAF50),
                unit = "FPS"
            )
        }

        item {
            HistoryChartCard(
                title = "屏幕刷新率 (Hz)",
                limitText = String.format(Locale.US, "档位区间: %.0f Hz - %.0f Hz", refreshRateList.minOrNull() ?: 0f, maxRefreshRate),
                data = refreshRateList,
                maxVal = maxRefreshRate,
                lineColor = Color(0xFF00BCD4),
                unit = "Hz",
                valueFormat = "%.0f"
            )
        }

        item {
            HistoryChartCard(
                title = "WLAN 上传网速 (${wlanTx.unit})",
                limitText = String.format(Locale.US, "录制总流量: %s | 丢包率: %.2f%%", wlanTotalStr, samples.lastOrNull()?.wlanLossRate ?: 0f),
                data = wlanTx.data,
                maxVal = wlanTx.maxVal,
                lineColor = Color(0xFF0288D1),
                unit = wlanTx.unit,
                valueFormat = wlanTx.valueFormat
            )
        }

        item {
            HistoryChartCard(
                title = "WLAN 下载网速 (${wlanRx.unit})",
                limitText = String.format(Locale.US, "录制总流量: %s | 丢包率: %.2f%%", wlanTotalStr, samples.lastOrNull()?.wlanLossRate ?: 0f),
                data = wlanRx.data,
                maxVal = wlanRx.maxVal,
                lineColor = Color(0xFF00BCD4),
                unit = wlanRx.unit,
                valueFormat = wlanRx.valueFormat
            )
        }

        item {
            HistoryChartCard(
                title = "蜂窝网络上传网速 (${cellTx.unit})",
                limitText = String.format(Locale.US, "录制总流量: %s | 丢包率: %.2f%%", cellTotalStr, samples.lastOrNull()?.cellLossRate ?: 0f),
                data = cellTx.data,
                maxVal = cellTx.maxVal,
                lineColor = Color(0xFFC2185B),
                unit = cellTx.unit,
                valueFormat = cellTx.valueFormat
            )
        }

        item {
            HistoryChartCard(
                title = "蜂窝网络下载网速 (${cellRx.unit})",
                limitText = String.format(Locale.US, "录制总流量: %s | 丢包率: %.2f%%", cellTotalStr, samples.lastOrNull()?.cellLossRate ?: 0f),
                data = cellRx.data,
                maxVal = cellRx.maxVal,
                lineColor = Color(0xFFE91E63),
                unit = cellRx.unit,
                valueFormat = cellRx.valueFormat
            )
        }

        item {
            HistoryChartCard(
                title = "RAM 可用内存 (GB)",
                limitText = String.format(Locale.US, "RAM 总量: %.3f GB", ramData.third.second),
                data = ramData.first,
                maxVal = ramData.second,
                lineColor = Color(0xFF2196F3),
                unit = "GB",
                valueFormat = "%.3f",
                valueOffset = ramData.third.first
            )
        }

        item {
            HistoryChartCard(
                title = "ZRAM 可用内存 (GB)",
                limitText = String.format(Locale.US, "ZRAM 总量: %.3f GB", zramData.third.second),
                data = zramData.first,
                maxVal = zramData.second,
                lineColor = Color(0xFF00BCD4),
                unit = "GB",
                valueFormat = "%.3f",
                valueOffset = zramData.third.first
            )
        }

        item {
            HistoryChartCard(
                title = "ROM 读取速度 (MB/s)",
                data = romReadList,
                maxVal = (romReadList.maxOrNull() ?: 10f).coerceAtLeast(5f),
                lineColor = Color(0xFF3F51B5),
                unit = "MB/s",
                valueFormat = "%.2f"
            )
        }

        item {
            HistoryChartCard(
                title = "ROM 写入速度 (MB/s)",
                data = romWriteList,
                maxVal = (romWriteList.maxOrNull() ?: 10f).coerceAtLeast(5f),
                lineColor = Color(0xFF673AB7),
                unit = "MB/s",
                valueFormat = "%.2f"
            )
        }

        item {
            HistoryChartCard(
                title = "电池温度 (°C)",
                data = tempData.first,
                maxVal = tempData.second.first,
                lineColor = Color(0xFFFF5722),
                unit = "°C",
                valueFormat = "%.1f",
                valueOffset = tempData.second.second
            )
        }

        item {
            HistoryChartCard(
                title = "电池电量 (%)",
                limitText = String.format(
                    Locale.US, "变化区间: %.0f%% - %.0f%% | 最终电量: %d%%",
                    batteryLevelList.minOrNull() ?: 0f, batteryLevelList.maxOrNull() ?: 0f, summaryData.lastBatteryLevel
                ),
                data = batteryLevelList,
                maxVal = 100f,
                lineColor = Color(0xFFFF5722),
                unit = "%",
                valueFormat = "%.0f"
            )
        }

        item {
            HistoryChartCard(
                title = "电池电压 (V)",
                limitText = String.format(Locale.US, "范围: %.2f V - %.2f V", voltageData.third.first, voltageData.third.second),
                data = voltageData.first,
                maxVal = voltageData.second,
                lineColor = Color(0xFFFBBC02),
                unit = "V",
                valueFormat = "%.2f",
                valueOffset = voltageData.third.first
            )
        }

        item {
            BiDirectionalCurrentCard(currentData = currentList)
        }

        item {
            val batCapLimitStr = if (summaryData.lastFullMah > 0f) {
                String.format(
                    Locale.US, "SoH: %.1f%% | 循环: %d次 | 容量: %.0f/%.0f mAh",
                    summaryData.lastSoh, summaryData.lastCycleCount, summaryData.lastFullMah, summaryData.lastDesignMah
                )
            } else null

            HistoryChartCard(
                title = "电池实时功率/功耗 (W)",
                limitText = batCapLimitStr,
                data = powerList,
                maxVal = (powerList.maxOrNull() ?: 5f).coerceAtLeast(1f),
                lineColor = Color(0xFFE91E63),
                unit = "W",
                valueFormat = "%.2f"
            )
        }

        item {
            HistoryChartCard(
                title = "GPU 负载率 (%)",
                limitText = gpuLimitStr,
                data = gpuLoadList,
                maxVal = 100f,
                lineColor = Color(0xFF9C27B0),
                unit = "%"
            )
        }

        item {
            HistoryChartCard(
                title = "GPU 运行频率 (GHz)",
                limitText = gpuLimitStr,
                data = gpuFreqList,
                maxVal = (gpuFreqList.maxOrNull() ?: 1f).coerceAtLeast(0.5f),
                lineColor = Color(0xFFAB47BC),
                unit = "GHz",
                valueFormat = "%.3f"
            )
        }

        // CPU 标题栏
        item {
            Text(
                text = "CPU 核心频率轨迹 (GHz)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(
            items = cpuCoreModels,
            key = { it.coreIndex }
        ) { coreModel ->
            val coreColor = CoreColors.getOrElse(coreModel.coreIndex) { Color.Gray }

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
                            Text("CPU 核心 ${coreModel.coreIndex}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            if (!coreModel.hwLimitStr.isNullOrEmpty()) {
                                Text(
                                    text = coreModel.hwLimitStr,
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = String.format(Locale.US, "最高: %.3f GHz", coreModel.maxValReal),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = coreColor
                            )
                            Text(
                                text = String.format(Locale.US, "平均: %.3f GHz", coreModel.avgVal),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = coreColor
                            )
                            Text(
                                text = String.format(Locale.US, "最低: %.3f GHz", coreModel.minVal),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = coreColor
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))

                    FastMetricLineChart(
                        data = coreModel.freqs,
                        maxVal = coreModel.maxChartVal,
                        lineColor = coreColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                    )
                }
            }
        }
    }
}

private data class SummaryUiModel(
    val maxFps: Float,
    val maxRefreshRate: Float,
    val maxTemp: Float,
    val maxCurrent: Float,
    val maxVolt: Float,
    val maxPower: Float,
    val lastBatteryLevel: Int,
    val lastStatus: String,
    val lastChargeType: String,
    val lastHealth: String,
    val lastCycleCount: Int,
    val lastFullMah: Float,
    val lastDesignMah: Float,
    val lastSoh: Float,
    val lastCellTotal: Float,
    val lastWlanTotal: Float
)

@Composable
fun FastMetricLineChart(
    data: List<Float>,
    maxVal: Float,
    lineColor: Color,
    modifier: Modifier = Modifier,
    unit: String = "",
    valueFormat: String = "%.2f",
    valueOffset: Float = 0f,
    pointSpacing: Dp = 3.dp
) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val scrollState = rememberScrollState()

    BoxWithConstraints(modifier = modifier) {
        val visibleWidth = maxWidth
        val contentWidth = maxOf(visibleWidth, pointSpacing * (data.size - 1).coerceAtLeast(1))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(scrollState)
        ) {
            Spacer(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(contentWidth)
                    // 1. 手势检测：触控/长按/拖拽时捕获选中的采样点索引
                    .pointerInput(data) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (data.size >= 2) {
                                val stepX = size.width.toFloat() / (data.size - 1)
                                selectedIndex = (down.position.x / stepX)
                                    .roundToInt()
                                    .coerceIn(0, data.indices.last)
                            }
                            do {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.firstOrNull()
                                if (pointer != null && pointer.pressed) {
                                    if (data.size >= 2) {
                                        val stepX = size.width.toFloat() / (data.size - 1)
                                        selectedIndex = (pointer.position.x / stepX)
                                            .roundToInt()
                                            .coerceIn(0, data.indices.last)
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            selectedIndex = null // 松开手指后清理选择框
                        }
                    }
                    // 2. 绘制折线与触摸高亮线/圆点
                    .drawWithCache {
                        val path = Path()
                        val effectiveMax = if (maxVal <= 0f) 1f else maxVal
                        val stepX = if (data.size >= 2) size.width / (data.size - 1) else 0f

                        if (data.size >= 2) {
                            data.forEachIndexed { i, value ->
                                val x = i * stepX
                                val y = size.height - ((value / effectiveMax).coerceIn(0f, 1f) * size.height)
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                        }
                        val strokePx = 1.5.dp.toPx()

                        onDrawBehind {
                            if (data.size >= 2) {
                                drawPath(
                                    path = path,
                                    color = lineColor,
                                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                                )
                            }

                            // 绘制手指选中时的指示竖线与实心圆点
                            selectedIndex?.let { index ->
                                if (index in data.indices) {
                                    val rawVal = data[index]
                                    val x = index * stepX
                                    val y = size.height - ((rawVal / effectiveMax).coerceIn(0f, 1f) * size.height)

                                    // 绘制竖向虚线准星
                                    drawLine(
                                        color = lineColor.copy(alpha = 0.7f),
                                        start = Offset(x, 0f),
                                        end = Offset(x, size.height),
                                        strokeWidth = 1.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                                    )

                                    // 绘制突出数据锚点
                                    drawCircle(
                                        color = Color.White,
                                        radius = 5.dp.toPx(),
                                        center = Offset(x, y)
                                    )
                                    drawCircle(
                                        color = lineColor,
                                        radius = 3.5.dp.toPx(),
                                        center = Offset(x, y)
                                    )
                                }
                            }
                        }
                    }
            )

            // 3. 在图表上方跟随手指触控点悬浮展示精确数值卡片
            selectedIndex?.let { index ->
                if (index in data.indices && data.size >= 2) {
                    val realVal = data[index] + valueOffset
                    val formattedVal = String.format(Locale.US, valueFormat, realVal)
                    val textStr = if (unit.isNotEmpty()) "$formattedVal $unit" else formattedVal

                    val stepX = contentWidth / (data.size - 1)
                    val xDp = stepX * index

                    Surface(
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(6.dp),
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .offset(
                                x = (xDp - 35.dp).coerceAtLeast(0.dp),
                                y = 2.dp
                            )
                    ) {
                        Text(
                            text = "#$index: $textStr",
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
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
    val minVal = remember(data, valueOffset) {
        (if (data.isNotEmpty()) data.minOrNull() ?: 0f else 0f) + valueOffset
    }
    val maxValReal = remember(data, valueOffset) {
        (if (data.isNotEmpty()) data.maxOrNull() ?: 0f else 0f) + valueOffset
    }
    val avgVal = remember(data, valueOffset) {
        (if (data.isNotEmpty()) data.average().toFloat() else 0f) + valueOffset
    }

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
            FastMetricLineChart(
                data = data,
                maxVal = maxVal,
                lineColor = lineColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
            )
        }
    }
}

@Composable
fun BiDirectionalCurrentCard(
    currentData: List<Float>
) {
    val chargeList = remember(currentData) { currentData.filter { it < 0f }.map { abs(it) } }
    val maxCharge = remember(chargeList) { chargeList.maxOrNull() ?: 0f }
    val avgCharge = remember(chargeList) { if (chargeList.isNotEmpty()) chargeList.average().toFloat() else 0f }
    val minCharge = remember(chargeList) { chargeList.minOrNull() ?: 0f }

    val dischargeList = remember(currentData) { currentData.filter { it > 0f } }
    val maxDischarge = remember(dischargeList) { dischargeList.maxOrNull() ?: 0f }
    val avgDischarge = remember(dischargeList) { if (dischargeList.isNotEmpty()) dischargeList.average().toFloat() else 0f }
    val minDischarge = remember(dischargeList) { dischargeList.minOrNull() ?: 0f }

    val maxAbs = remember(currentData) { currentData.maxOfOrNull { abs(it) }?.coerceAtLeast(500f) ?: 1000f }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "🔋 电池电流趋势",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = String.format(Locale.US, "最高  充: %.0f mA | 放: %.0f mA", maxCharge, maxDischarge),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format(Locale.US, "平均  充: %.0f mA | 放: %.0f mA", avgCharge, avgDischarge),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format(Locale.US, "最低  充: %.0f mA | 放: %.0f mA", minCharge, minDischarge),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            BiDirectionalMetricChart(
                data = currentData,
                maxAbs = maxAbs,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("▲ 充电 (-mA)", fontSize = 10.sp, color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                Text("0 mA 基准线", fontSize = 10.sp, color = Color.Gray)
                Text("▼ 放电 (+mA)", fontSize = 10.sp, color = Color(0xFFFF5722), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun BiDirectionalMetricChart(
    data: List<Float>,
    maxAbs: Float,
    modifier: Modifier = Modifier,
    chargeColor: Color = Color(0xFF4CAF50),
    dischargeColor: Color = Color(0xFFFF5722),
    pointSpacing: Dp = 3.dp
) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val scrollState = rememberScrollState()

    BoxWithConstraints(modifier = modifier) {
        val visibleWidth = maxWidth
        val contentWidth = maxOf(visibleWidth, pointSpacing * (data.size - 1).coerceAtLeast(1))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(scrollState)
        ) {
            Spacer(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(contentWidth)
                    .pointerInput(data) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (data.size >= 2) {
                                val stepX = size.width.toFloat() / (data.size - 1)
                                selectedIndex = (down.position.x / stepX).roundToInt().coerceIn(0, data.indices.last)
                            }
                            do {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.firstOrNull()
                                if (pointer != null && pointer.pressed) {
                                    if (data.size >= 2) {
                                        val stepX = size.width.toFloat() / (data.size - 1)
                                        selectedIndex = (pointer.position.x / stepX).roundToInt().coerceIn(0, data.indices.last)
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            selectedIndex = null
                        }
                    }
                    .drawWithCache {
                        val chargePath = Path()
                        val dischargePath = Path()

                        val stepX = if (data.size >= 2) size.width / (data.size - 1) else 0f
                        val zeroY = size.height / 2f

                        if (data.size >= 2) {
                            var prevPoint = Offset(
                                0f,
                                zeroY - ((-data[0] / maxAbs).coerceIn(-1f, 1f) * zeroY)
                            )
                            var prevVal = data[0]

                            for (i in 1 until data.size) {
                                val valMa = data[i]
                                val x = i * stepX
                                val normalized = (-valMa / maxAbs).coerceIn(-1f, 1f)
                                val currPoint = Offset(x, zeroY - (normalized * zeroY))

                                if ((prevVal <= 0f && valMa <= 0f) || (prevVal >= 0f && valMa >= 0f)) {
                                    val targetPath = if (prevVal <= 0f) chargePath else dischargePath
                                    targetPath.moveTo(prevPoint.x, prevPoint.y)
                                    targetPath.lineTo(currPoint.x, currPoint.y)
                                } else {
                                    val t = (zeroY - prevPoint.y) / (currPoint.y - prevPoint.y)
                                    val crossPoint = Offset(prevPoint.x + t * (currPoint.x - prevPoint.x), zeroY)

                                    val path1 = if (prevVal <= 0f) chargePath else dischargePath
                                    path1.moveTo(prevPoint.x, prevPoint.y)
                                    path1.lineTo(crossPoint.x, crossPoint.y)

                                    val path2 = if (valMa <= 0f) chargePath else dischargePath
                                    path2.moveTo(crossPoint.x, crossPoint.y)
                                    path2.lineTo(currPoint.x, currPoint.y)
                                }

                                prevPoint = currPoint
                                prevVal = valMa
                            }
                        }

                        val strokePx = 2.dp.toPx()

                        onDrawBehind {
                            drawPath(
                                path = chargePath,
                                color = chargeColor,
                                style = Stroke(width = strokePx, cap = StrokeCap.Round)
                            )
                            drawPath(
                                path = dischargePath,
                                color = dischargeColor,
                                style = Stroke(width = strokePx, cap = StrokeCap.Round)
                            )

                            selectedIndex?.let { index ->
                                if (index in data.indices) {
                                    val valMa = data[index]
                                    val x = index * stepX
                                    val normalized = (-valMa / maxAbs).coerceIn(-1f, 1f)
                                    val y = zeroY - (normalized * zeroY)
                                    val pointColor = if (valMa <= 0f) chargeColor else dischargeColor

                                    drawLine(
                                        color = pointColor.copy(alpha = 0.7f),
                                        start = Offset(x, 0f),
                                        end = Offset(x, size.height),
                                        strokeWidth = 1.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                                    )

                                    drawCircle(
                                        color = Color.White,
                                        radius = 5.dp.toPx(),
                                        center = Offset(x, y)
                                    )
                                    drawCircle(
                                        color = pointColor,
                                        radius = 3.5.dp.toPx(),
                                        center = Offset(x, y)
                                    )
                                }
                            }
                        }
                    }
            )

            selectedIndex?.let { index ->
                if (index in data.indices && data.size >= 2) {
                    val currentMa = data[index]
                    val textStr = if (currentMa < 0f) {
                        String.format(Locale.US, "%.0f mA (充电)", abs(currentMa))
                    } else {
                        String.format(Locale.US, "%.0f mA (放电)", currentMa)
                    }

                    val stepX = contentWidth / (data.size - 1)
                    val xDp = stepX * index

                    Surface(
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(6.dp),
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .offset(
                                x = (xDp - 35.dp).coerceAtLeast(0.dp),
                                y = 2.dp
                            )
                    ) {
                        Text(
                            text = "#$index: $textStr",
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
