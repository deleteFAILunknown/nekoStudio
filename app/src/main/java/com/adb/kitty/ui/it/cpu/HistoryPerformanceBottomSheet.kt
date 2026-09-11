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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Path as NativePath
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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

    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current

    val paddingPx = with(density) { 32.dp.toPx() }
    val chartWidthPx = (windowInfo.containerSize.width.toFloat() - paddingPx).coerceAtLeast(1f)
    val chartHeightPx = with(density) { 100.dp.toPx() }

    val precomputedGraphState by produceState<PrecomputedGraphState?>(initialValue = null, history, chartWidthPx) {
        value = withContext(Dispatchers.Default) {
            val lastSample = samples.lastOrNull()
            val summary = SummaryUiModel(
                maxFps = samples.maxOfOrNull { it.fps } ?: 0f,
                maxRefreshRate = samples.maxOfOrNull { it.refreshRate } ?: 0f,
                maxTemp = samples.maxOfOrNull { it.batteryTemp } ?: 0f,
                maxCurrent = samples.maxOfOrNull { it.batteryCurrentMa } ?: 0f,
                maxVolt = (samples.maxOfOrNull { it.batteryVoltageMv } ?: 0f) / 1000f,
                maxPower = samples.maxOfOrNull { it.batteryPowerW } ?: 0f,
                lastBatteryLevel = lastSample?.batteryLevel ?: 0,
                lastStatus = lastSample?.batteryStatus ?: "Unknown",
                lastChargeType = lastSample?.batteryChargeType ?: "Unknown",
                lastHealth = lastSample?.batteryHealth ?: "Unknown",
                lastCycleCount = lastSample?.batteryCycleCount ?: 0,
                lastFullMah = lastSample?.batteryFullMah ?: 0f,
                lastDesignMah = lastSample?.batteryFullDesignMah ?: 0f,
                lastSoh = lastSample?.batterySohPercent ?: 0f,
                lastCellTotal = lastSample?.cellTotalMb ?: 0f,
                lastWlanTotal = lastSample?.wlanTotalMb ?: 0f
            )

            val wlanTotalStr = formatMb(summary.lastWlanTotal)
            val cellTotalStr = formatMb(summary.lastCellTotal)

            // 1. FPS & 刷新率
            val fpsList = samples.map { it.fps }
            val maxFpsLimit = samples.maxOfOrNull { it.refreshRate }?.coerceAtLeast(60f) ?: 60f
            val fpsChart = ChartPrecompiler.precomputeLine(fpsList, maxFpsLimit, chartWidthPx, chartHeightPx)

            val refreshList = samples.map { it.refreshRate }
            val maxRefresh = refreshList.maxOrNull() ?: 60f
            val minRefresh = refreshList.minOrNull() ?: 0f
            val refreshChart = ChartPrecompiler.precomputeLine(refreshList, maxRefresh, chartWidthPx, chartHeightPx)

            // 2. 网络速率缩放与构建
            val wlanTxData = autoScaleKbpsList(samples.map { it.wlanTxSpeedKbps })
            val wlanTxChart = ChartPrecompiler.precomputeLine(wlanTxData.data, wlanTxData.maxVal, chartWidthPx, chartHeightPx)

            val wlanRxData = autoScaleKbpsList(samples.map { it.wlanRxSpeedKbps })
            val wlanRxChart = ChartPrecompiler.precomputeLine(wlanRxData.data, wlanRxData.maxVal, chartWidthPx, chartHeightPx)

            val cellTxData = autoScaleKbpsList(samples.map { it.cellTxSpeedKbps })
            val cellTxChart = ChartPrecompiler.precomputeLine(cellTxData.data, cellTxData.maxVal, chartWidthPx, chartHeightPx)

            val cellRxData = autoScaleKbpsList(samples.map { it.cellRxSpeedKbps })
            val cellRxChart = ChartPrecompiler.precomputeLine(cellRxData.data, cellRxData.maxVal, chartWidthPx, chartHeightPx)

            // 3. 内存与存储
            val ramList = samples.map { it.ramAvailGb }
            val ramMin = ramList.minOrNull() ?: 0f
            val ramMax = ramList.maxOrNull() ?: 0f
            val ramTotal = lastSample?.ramTotalGb ?: 1f
            val ramChart = ChartPrecompiler.precomputeLine(ramList.map { it - ramMin }, (ramMax - ramMin).coerceAtLeast(0.3f), chartWidthPx, chartHeightPx, valueOffset = ramMin)

            val zramList = samples.map { it.zramAvailGb }
            val zramMin = zramList.minOrNull() ?: 0f
            val zramMax = zramList.maxOrNull() ?: 0f
            val zramTotal = lastSample?.zramTotalGb ?: 1f
            val zramChart = ChartPrecompiler.precomputeLine(zramList.map { it - zramMin }, (zramMax - zramMin).coerceAtLeast(0.3f), chartWidthPx, chartHeightPx, valueOffset = zramMin)

            val romReadList = samples.map { it.romReadSpeedMb }
            val romReadChart = ChartPrecompiler.precomputeLine(romReadList, (romReadList.maxOrNull() ?: 10f).coerceAtLeast(5f), chartWidthPx, chartHeightPx)

            val romWriteList = samples.map { it.romWriteSpeedMb }
            val romWriteChart = ChartPrecompiler.precomputeLine(romWriteList, (romWriteList.maxOrNull() ?: 10f).coerceAtLeast(5f), chartWidthPx, chartHeightPx)

            // 4. 电池参数
            val tempList = samples.map { it.batteryTemp }
            val tempMin = tempList.minOrNull() ?: 0f
            val tempMax = tempList.maxOrNull() ?: 0f
            val tempChart = ChartPrecompiler.precomputeLine(tempList.map { it - tempMin }, (tempMax - tempMin).coerceAtLeast(1.0f), chartWidthPx, chartHeightPx, valueOffset = tempMin)

            val batLevelList = samples.map { it.batteryLevel.toFloat() }
            val batLevelChart = ChartPrecompiler.precomputeLine(batLevelList, 100f, chartWidthPx, chartHeightPx)

            val voltList = samples.map { it.batteryVoltageMv / 1000f }
            val voltMin = voltList.minOrNull() ?: 0f
            val voltMax = voltList.maxOrNull() ?: 0f
            val voltageChart = ChartPrecompiler.precomputeLine(voltList.map { it - voltMin }, (voltMax - voltMin).coerceAtLeast(0.1f), chartWidthPx, chartHeightPx, valueOffset = voltMin)

            val currentList = samples.map { it.batteryCurrentMa }
            val maxAbsCurrent = currentList.maxOfOrNull { abs(it) }?.coerceAtLeast(500f) ?: 1000f
            val currentChart = ChartPrecompiler.precomputeBiDirectional(currentList, maxAbsCurrent, chartWidthPx, chartHeightPx)

            val powerList = samples.map { it.batteryPowerW }
            val powerChart = ChartPrecompiler.precomputeLine(powerList, (powerList.maxOrNull() ?: 5f).coerceAtLeast(1f), chartWidthPx, chartHeightPx)

            // 5. GPU 核心
            val gpuLoadList = samples.map { it.gpuLoadPercent }
            val gpuLoadChart = ChartPrecompiler.precomputeLine(gpuLoadList, 100f, chartWidthPx, chartHeightPx)

            val gpuFreqList = samples.map { it.gpuFreqGhz }
            val gpuFreqChart = ChartPrecompiler.precomputeLine(gpuFreqList, (gpuFreqList.maxOrNull() ?: 1f).coerceAtLeast(0.5f), chartWidthPx, chartHeightPx)

            val gpuMin = samples.mapNotNull { if (it.gpuMinFreqGhz > 0) it.gpuMinFreqGhz else null }.firstOrNull() ?: 0f
            val gpuMax = samples.mapNotNull { if (it.gpuMaxFreqGhz > 0) it.gpuMaxFreqGhz else null }.firstOrNull() ?: 0f
            val gpuLimitStr = if (gpuMax > 0f) String.format(Locale.US, "Limit: %.3f - %.3f GHz", gpuMin, gpuMax) else null

            // 6. CPU 核心（动态多核）
            val coreCount = samples.maxOfOrNull { it.cpuFreqsGhz.size } ?: 0
            val cpuCharts = (0 until coreCount).map { coreIndex ->
                val freqs = samples.map { it.cpuFreqsGhz.getOrNull(coreIndex) ?: 0f }
                val maxValReal = freqs.maxOrNull() ?: 0f
                val hwLimits = samples.mapNotNull { it.cpuHwLimitsGhz.getOrNull(coreIndex) }.firstOrNull()
                val hwLimitStr = if (hwLimits != null && hwLimits.second > 0f) {
                    String.format(Locale.US, "HW: %.3f - %.3f GHz", hwLimits.first, hwLimits.second)
                } else null

                val chartData = ChartPrecompiler.precomputeLine(
                    data = freqs,
                    maxVal = maxValReal.coerceAtLeast(1f),
                    widthPx = chartWidthPx,
                    heightPx = chartHeightPx
                )
                Triple(coreIndex, hwLimitStr, chartData)
            }

            PrecomputedGraphState(
                summaryData = summary,
                fpsChart = fpsChart,
                refreshChart = refreshChart,
                wlanTxChart = wlanTxChart,
                wlanTxUnit = wlanTxData.unit,
                wlanTxFormat = wlanTxData.valueFormat,
                wlanRxChart = wlanRxChart,
                wlanRxUnit = wlanRxData.unit,
                wlanRxFormat = wlanRxData.valueFormat,
                cellTxChart = cellTxChart,
                cellTxUnit = cellTxData.unit,
                cellTxFormat = cellTxData.valueFormat,
                cellRxChart = cellRxChart,
                cellRxUnit = cellRxData.unit,
                cellRxFormat = cellRxData.valueFormat,
                ramChart = ramChart,
                ramMin = ramMin,
                ramTotal = ramTotal,
                zramChart = zramChart,
                zramMin = zramMin,
                zramTotal = zramTotal,
                romReadChart = romReadChart,
                romWriteChart = romWriteChart,
                tempChart = tempChart,
                tempMin = tempMin,
                batteryLevelChart = batLevelChart,
                voltageChart = voltageChart,
                voltageMin = voltMin,
                voltageMax = voltMax,
                currentChart = currentChart,
                powerChart = powerChart,
                gpuLoadChart = gpuLoadChart,
                gpuFreqChart = gpuFreqChart,
                gpuLimitStr = gpuLimitStr,
                cpuCharts = cpuCharts,
                wlanTotalStr = wlanTotalStr,
                cellTotalStr = cellTotalStr,
                wlanLossRate = lastSample?.wlanLossRate ?: 0f,
                cellLossRate = lastSample?.cellLossRate ?: 0f,
                refreshRateMin = minRefresh,
                refreshRateMax = maxRefresh
            )
        }
    }

    if (precomputedGraphState == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text("正在进行后台高阶预编译 Path，请稍候...", fontSize = 12.sp, color = Color.Gray)
            }
        }
        return
    }

    val graphState = precomputedGraphState!!
    val summaryData = graphState.summaryData

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "summary_card") {
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
                        label = "总数据点",
                        value = "${history.durationSeconds}"
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
                        value = String.format(Locale.US, "%.2fV",
                        summaryData.maxVolt),
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
                        value = graphState.wlanTotalStr,
                        valueColor = Color(0xFF00BCD4)
                    )
                    SummaryItem(
                        label = "蜂窝流量",
                        value = graphState.cellTotalStr,
                        valueColor = Color(0xFF00BCD4)
                    )
                }
            }
        }

        item(key = "chart_fps") {
            HistoryPrecomputedCard(
                title = "帧率波动 (FPS)",
                chartData = graphState.fpsChart,
                lineColor = Color(0xFF4CAF50),
                unit = "FPS"
            )
        }

        item(key = "chart_refresh") {
            HistoryPrecomputedCard(
                title = "屏幕刷新率 (Hz)",
                limitText = String.format(Locale.US, "档位区间: %.0f Hz - %.0f Hz", graphState.refreshRateMin, graphState.refreshRateMax),
                chartData = graphState.refreshChart,
                lineColor = Color(0xFF00BCD4),
                unit = "Hz",
                valueFormat = "%.0f"
            )
        }

        item(key = "chart_wlan_tx") {
            HistoryPrecomputedCard(
                title = "WLAN 上传网速 (${graphState.wlanTxUnit})",
                limitText = String.format(Locale.US, "录制总流量: %s | 丢包率: %.2f%%", graphState.wlanTotalStr, graphState.wlanLossRate),
                chartData = graphState.wlanTxChart,
                lineColor = Color(0xFF0288D1),
                unit = graphState.wlanTxUnit,
                valueFormat = graphState.wlanTxFormat
            )
        }

        item(key = "chart_wlan_rx") {
            HistoryPrecomputedCard(
                title = "WLAN 下载网速 (${graphState.wlanRxUnit})",
                limitText = String.format(Locale.US, "录制总流量: %s | 丢包率: %.2f%%", graphState.wlanTotalStr, graphState.wlanLossRate),
                chartData = graphState.wlanRxChart,
                lineColor = Color(0xFF00BCD4),
                unit = graphState.wlanRxUnit,
                valueFormat = graphState.wlanRxFormat
            )
        }

        item(key = "chart_cell_tx") {
            HistoryPrecomputedCard(
                title = "蜂窝网络上传网速 (${graphState.cellTxUnit})",
                limitText = String.format(Locale.US, "录制总流量: %s | 丢包率: %.2f%%", graphState.cellTotalStr, graphState.cellLossRate),
                chartData = graphState.cellTxChart,
                lineColor = Color(0xFFC2185B),
                unit = graphState.cellTxUnit,
                valueFormat = graphState.cellTxFormat
            )
        }

        item(key = "chart_cell_rx") {
            HistoryPrecomputedCard(
                title = "蜂窝网络下载网速 (${graphState.cellRxUnit})",
                limitText = String.format(Locale.US, "录制总流量: %s | 丢包率: %.2f%%", graphState.cellTotalStr, graphState.cellLossRate),
                chartData = graphState.cellRxChart,
                lineColor = Color(0xFFE91E63),
                unit = graphState.cellRxUnit,
                valueFormat = graphState.cellRxFormat
            )
        }

        item(key = "chart_ram") {
            HistoryPrecomputedCard(
                title = "RAM 可用内存 (GB)",
                limitText = String.format(Locale.US, "RAM 总量: %.3f GB", graphState.ramTotal),
                chartData = graphState.ramChart,
                lineColor = Color(0xFF2196F3),
                unit = "GB",
                valueFormat = "%.3f",
                valueOffset = graphState.ramMin
            )
        }

        item(key = "chart_zram") {
            HistoryPrecomputedCard(
                title = "ZRAM 可用内存 (GB)",
                limitText = String.format(Locale.US, "ZRAM 总量: %.3f GB", graphState.zramTotal),
                chartData = graphState.zramChart,
                lineColor = Color(0xFF00BCD4),
                unit = "GB",
                valueFormat = "%.3f",
                valueOffset = graphState.zramMin
            )
        }

        item(key = "chart_rom_read") {
            HistoryPrecomputedCard(
                title = "ROM 读取速度 (MB/s)",
                chartData = graphState.romReadChart,
                lineColor = Color(0xFF3F51B5),
                unit = "MB/s",
                valueFormat = "%.2f"
            )
        }

        item(key = "chart_rom_write") {
            HistoryPrecomputedCard(
                title = "ROM 写入速度 (MB/s)",
                chartData = graphState.romWriteChart,
                lineColor = Color(0xFF673AB7),
                unit = "MB/s",
                valueFormat = "%.2f"
            )
        }

        item(key = "chart_temp") {
            HistoryPrecomputedCard(
                title = "电池温度 (°C)",
                chartData = graphState.tempChart,
                lineColor = Color(0xFFFF5722),
                unit = "°C",
                valueFormat = "%.1f",
                valueOffset = graphState.tempMin
            )
        }

        item(key = "chart_battery_level") {
            HistoryPrecomputedCard(
                title = "电池电量 (%)",
                limitText = String.format(
                    Locale.US, "变化区间: %.0f%% - %.0f%% | 最终电量: %d%%",
                    graphState.batteryLevelChart.minValReal, graphState.batteryLevelChart.maxValReal, summaryData.lastBatteryLevel
                ),
                chartData = graphState.batteryLevelChart,
                lineColor = Color(0xFFFF5722),
                unit = "%",
                valueFormat = "%.0f"
            )
        }

        item(key = "chart_voltage") {
            HistoryPrecomputedCard(
                title = "电池电压 (V)",
                limitText = String.format(Locale.US, "范围: %.2f V - %.2f V", graphState.voltageMin, graphState.voltageMax),
                chartData = graphState.voltageChart,
                lineColor = Color(0xFFFBBC02),
                unit = "V",
                valueFormat = "%.2f",
                valueOffset = graphState.voltageMin
            )
        }

        item(key = "chart_current") {
            BiDirectionalPrecomputedCard(chartData = graphState.currentChart)
        }

        item(key = "chart_power") {
            val batCapLimitStr = if (summaryData.lastFullMah > 0f) {
                String.format(
                    Locale.US, "SoH: %.1f%% | 循环: %d次 | 容量: %.0f/%.0f mAh",
                    summaryData.lastSoh, summaryData.lastCycleCount, summaryData.lastFullMah, summaryData.lastDesignMah
                )
            } else null

            HistoryPrecomputedCard(
                title = "电池实时功率/功耗 (W)",
                limitText = batCapLimitStr,
                chartData = graphState.powerChart,
                lineColor = Color(0xFFE91E63),
                unit = "W",
                valueFormat = "%.2f"
            )
        }

        item(key = "chart_gpu_load") {
            HistoryPrecomputedCard(
                title = "GPU 负载率 (%)",
                limitText = graphState.gpuLimitStr,
                chartData = graphState.gpuLoadChart,
                lineColor = Color(0xFF9C27B0),
                unit = "%"
            )
        }

        item(key = "chart_gpu_freq") {
            HistoryPrecomputedCard(
                title = "GPU 运行频率 (GHz)",
                limitText = graphState.gpuLimitStr,
                chartData = graphState.gpuFreqChart,
                lineColor = Color(0xFFAB47BC),
                unit = "GHz",
                valueFormat = "%.3f"
            )
        }

        item(key = "cpu_title") {
            Text(
                text = "CPU 核心频率轨迹 (GHz)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(
            items = graphState.cpuCharts,
            key = { "cpu_core_${it.first}" }
        ) { (coreIndex, hwLimitStr, chartData) ->
            val coreColor = CoreColors.getOrElse(coreIndex) { Color.Gray }

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
                            Text(
                                text = "CPU 核心 $coreIndex",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (!hwLimitStr.isNullOrEmpty()) {
                                Text(
                                    text = hwLimitStr,
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "最高: ${String.format(Locale.US, "%.3f", chartData.maxValReal)} GHz",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = coreColor
                            )
                            Text(
                                text = "平均: ${String.format(Locale.US, "%.3f", chartData.avgValReal)} GHz",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = coreColor
                            )
                            Text(
                                text = "最低: ${String.format(Locale.US, "%.3f", chartData.minValReal)} GHz",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = coreColor
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))

                    FastPrecomputedLineChart(
                        chartData = chartData,
                        lineColor = coreColor,
                        modifier = Modifier.fillMaxWidth().height(100.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun FastPrecomputedLineChart(
    chartData: PrecomputedLineChartData,
    lineColor: Color,
    modifier: Modifier = Modifier,
    unit: String = "",
    valueFormat: String = "%.3f",
    valueOffset: Float = 0f
) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current
    val data = chartData.rawData

    BoxWithConstraints(modifier = modifier) {
        val widthPx = constraints.maxWidth.toFloat()

        Spacer(
            modifier = Modifier
                .fillMaxSize()
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
                            if (pointer != null && pointer.pressed && data.size >= 2) {
                                val stepX = size.width.toFloat() / (data.size - 1)
                                selectedIndex = (pointer.position.x / stepX).roundToInt().coerceIn(0, data.indices.last)
                            }
                        } while (event.changes.any { it.pressed })
                        selectedIndex = null
                    }
                }
                .drawWithCache {
                    val strokePx = 1.5.dp.toPx()
                    val dashHeightPx = size.height
                    val fillBrush = Brush.verticalGradient(
                        colors = listOf(
                            lineColor.copy(alpha = 0.35f),
                            lineColor.copy(alpha = 0.02f)
                        ),
                        startY = 0f,
                        endY = size.height
                    )

                    onDrawBehind {
                        if (data.size >= 2) {
                            // 零循环：直接绘制预构建的 Compose Path
                            drawPath(path = chartData.fillPath, brush = fillBrush)
                            drawPath(path = chartData.linePath, color = lineColor, style = Stroke(width = strokePx, cap = StrokeCap.Round))
                        }

                        selectedIndex?.let { index ->
                            if (index in data.indices) {
                                val stepX = size.width / (data.size - 1)
                                val rawVal = data[index]
                                val x = index * stepX
                                val y = size.height - ((rawVal / chartData.maxChartVal).coerceIn(0f, 1f) * size.height)

                                drawLine(
                                    color = lineColor.copy(alpha = 0.7f),
                                    start = Offset(x, 0f),
                                    end = Offset(x, dashHeightPx),
                                    strokeWidth = 1.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                                )

                                drawCircle(color = Color.White, radius = 5.dp.toPx(), center = Offset(x, y))
                                drawCircle(color = lineColor, radius = 3.5.dp.toPx(), center = Offset(x, y))
                            }
                        }
                    }
                }
        )

        selectedIndex?.let { index ->
            if (index in data.indices && data.size >= 2) {
                val realVal = data[index] + valueOffset
                val formattedVal = String.format(Locale.US, valueFormat, realVal)
                val textStr = if (unit.isNotEmpty()) "$formattedVal $unit" else formattedVal

                val stepX = widthPx / (data.size - 1)
                val xDp = with(density) { (stepX * index).toDp() }

                val isRightHalf = index > (data.size - 1) / 2
                val bubbleEstimatedWidth = 85.dp
                val targetX = if (isRightHalf) {
                    (xDp - bubbleEstimatedWidth).coerceAtLeast(0.dp)
                } else {
                    xDp.coerceAtMost(maxWidth - bubbleEstimatedWidth)
                }

                Surface(
                    color = Color.White.copy(alpha = 0.90f),
                    shape = RoundedCornerShape(6.dp),
                    shadowElevation = 3.dp,
                    modifier = Modifier.offset(x = targetX, y = 0.dp)
                ) {
                    Text(
                        text = textStr,
                        color = Color.Black,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun FastPrecomputedBiDirectionalChart(
    chartData: PrecomputedBiDirectionalData,
    modifier: Modifier = Modifier,
    chargeColor: Color = Color(0xFF4CAF50),
    dischargeColor: Color = Color(0xFFFF5722)
) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current
    val data = chartData.rawData

    BoxWithConstraints(modifier = modifier) {
        val widthPx = constraints.maxWidth.toFloat()

        Spacer(
            modifier = Modifier
                .fillMaxSize()
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
                            if (pointer != null && pointer.pressed && data.size >= 2) {
                                val stepX = size.width.toFloat() / (data.size - 1)
                                selectedIndex = (pointer.position.x / stepX).roundToInt().coerceIn(0, data.indices.last)
                            }
                        } while (event.changes.any { it.pressed })
                        selectedIndex = null
                    }
                }
                .drawWithCache {
                    val strokePx = 2.dp.toPx()
                    val dashHeightPx = size.height
                    val zeroY = size.height / 2f

                    onDrawBehind {
                        if (data.size >= 2) {
                            drawPath(
                                path = chartData.chargeFillPath,
                                brush = Brush.verticalGradient(
                                    colors = listOf(chargeColor.copy(alpha = 0.35f), Color.Transparent),
                                    startY = 0f, endY = zeroY
                                )
                            )
                            drawPath(
                                path = chartData.dischargeFillPath,
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, dischargeColor.copy(alpha = 0.35f)),
                                    startY = zeroY, endY = size.height
                                )
                            )
                            drawPath(path = chartData.chargePath, color = chargeColor, style = Stroke(width = strokePx, cap = StrokeCap.Round))
                            drawPath(path = chartData.dischargePath, color = dischargeColor, style = Stroke(width = strokePx, cap = StrokeCap.Round))
                        }

                        selectedIndex?.let { index ->
                            if (index in data.indices) {
                                val valMa = data[index]
                                val stepX = size.width / (data.size - 1)
                                val x = index * stepX
                                val normalized = (-valMa / chartData.maxAbs).coerceIn(-1f, 1f)
                                val y = zeroY - (normalized * zeroY)
                                val pointColor = if (valMa <= 0f) chargeColor else dischargeColor

                                drawLine(
                                    color = pointColor.copy(alpha = 0.7f),
                                    start = Offset(x, 0f), end = Offset(x, dashHeightPx),
                                    strokeWidth = 1.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                                )
                                drawCircle(color = Color.White, radius = 5.dp.toPx(), center = Offset(x, y))
                                drawCircle(color = pointColor, radius = 3.5.dp.toPx(), center = Offset(x, y))
                            }
                        }
                    }
                }
        )

        selectedIndex?.let { index ->
            if (index in data.indices && data.size >= 2) {
                val currentMa = data[index]
                val textStr = if (currentMa < 0f) {
                    String.format(Locale.US, "%.3f mA (充电)", abs(currentMa))
                } else {
                    String.format(Locale.US, "%.3f mA (放电)", currentMa)
                }

                val stepX = widthPx / (data.size - 1)
                val xDp = with(density) { (stepX * index).toDp() }
                val isRightHalf = index > (data.size - 1) / 2
                val bubbleEstimatedWidth = 95.dp
                val targetX = if (isRightHalf) {
                    (xDp - bubbleEstimatedWidth).coerceAtLeast(0.dp)
                } else {
                    xDp.coerceAtMost(maxWidth - bubbleEstimatedWidth)
                }

                Surface(
                    color = Color.White.copy(alpha = 0.90f),
                    shape = RoundedCornerShape(6.dp),
                    shadowElevation = 3.dp,
                    modifier = Modifier.offset(x = targetX, y = 0.dp)
                ) {
                    Text(
                        text = textStr,
                        color = Color.Black,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryPrecomputedCard(
    title: String,
    limitText: String? = null,
    chartData: PrecomputedLineChartData,
    lineColor: Color,
    unit: String,
    valueFormat: String = "%.2f",
    valueOffset: Float = 0f
) {
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
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
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
                        text = String.format(Locale.US, "最高: $valueFormat %s", chartData.maxValReal, unit),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = lineColor
                    )
                    Text(
                        text = String.format(Locale.US, "平均: $valueFormat %s", chartData.avgValReal, unit),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = lineColor
                    )
                    Text(
                        text = String.format(Locale.US, "最低: $valueFormat %s", chartData.minValReal, unit),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = lineColor
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            FastPrecomputedLineChart(
                chartData = chartData,
                lineColor = lineColor,
                modifier = Modifier.fillMaxWidth().height(100.dp),
                unit = unit,
                valueFormat = valueFormat,
                valueOffset = valueOffset
            )
        }
    }
}

@Composable
private fun BiDirectionalPrecomputedCard(
    chartData: PrecomputedBiDirectionalData
) {
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
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = String.format(Locale.US, "最高  充: %.0f mA | 放: %.0f mA", chartData.maxCharge, chartData.maxDischarge),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format(Locale.US, "平均  充: %.0f mA | 放: %.0f mA", chartData.avgCharge, chartData.avgDischarge),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format(Locale.US, "最低  充: %.0f mA | 放: %.0f mA", chartData.minCharge, chartData.minDischarge),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            FastPrecomputedBiDirectionalChart(
                chartData = chartData,
                modifier = Modifier.fillMaxWidth().height(100.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "▲ 充电 (-mA)",
                    fontSize = 10.sp,
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "0 mA 基准线",
                    fontSize = 10.sp,
                    color = Color.Gray
                )
                Text(
                    text = "▼ 放电 (+mA)",
                    fontSize = 10.sp,
                    color = Color(0xFFFF5722),
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "💡 提示：充电、放电参数不一定准确，只是对正数与负数做了个处理而已，因此数据仅供参考.",
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

data class PrecomputedLineChartData(
    val linePath: Path,
    val fillPath: Path,
    val rawData: List<Float>,
    val maxChartVal: Float,
    val minValReal: Float,
    val maxValReal: Float,
    val avgValReal: Float
)

data class PrecomputedBiDirectionalData(
    val chargePath: Path,
    val chargeFillPath: Path,
    val dischargePath: Path,
    val dischargeFillPath: Path,
    val rawData: List<Float>,
    val maxAbs: Float,
    val maxCharge: Float,
    val avgCharge: Float,
    val minCharge: Float,
    val maxDischarge: Float,
    val avgDischarge: Float,
    val minDischarge: Float
)

object ChartPrecompiler {

    /**
     * 在后台协程预构建单向折线 Path 与统计数据
     */
    suspend fun precomputeLine(
        data: List<Float>,
        maxVal: Float,
        widthPx: Float,
        heightPx: Float,
        valueOffset: Float = 0f
    ): PrecomputedLineChartData = withContext(Dispatchers.Default) {
        val lineNative = NativePath()
        val fillNative = NativePath()

        val effectiveMax = if (maxVal <= 0f) 1f else maxVal
        val dataSize = data.size

        var minVal = Float.MAX_VALUE
        var maxValReal = -Float.MAX_VALUE
        var sumVal = 0.0

        if (dataSize >= 2 && widthPx > 0f && heightPx > 0f) {
            val stepX = widthPx / (dataSize - 1)
            fillNative.moveTo(0f, heightPx)

            for (i in 0 until dataSize) {
                val v = data[i]
                val realV = v + valueOffset
                if (realV < minVal) minVal = realV
                if (realV > maxValReal) maxValReal = realV
                sumVal += realV

                val x = i * stepX
                val y = heightPx - ((v / effectiveMax).coerceIn(0f, 1f) * heightPx)

                if (i == 0) {
                    lineNative.moveTo(x, y)
                    fillNative.lineTo(x, y)
                } else {
                    lineNative.lineTo(x, y)
                    fillNative.lineTo(x, y)
                }
            }

            fillNative.lineTo(widthPx, heightPx)
            fillNative.close()
        } else {
            minVal = 0f
            maxValReal = 0f
        }

        val avgVal = if (dataSize > 0) (sumVal / dataSize).toFloat() else 0f

        PrecomputedLineChartData(
            linePath = lineNative.asComposePath(),
            fillPath = fillNative.asComposePath(),
            rawData = data,
            maxChartVal = effectiveMax,
            minValReal = if (minVal == Float.MAX_VALUE) 0f else minVal,
            maxValReal = if (maxValReal == -Float.MAX_VALUE) 0f else maxValReal,
            avgValReal = avgVal
        )
    }

    /**
     * 在后台协程预构建双向电流图表 Path 与统计数据
     */
    suspend fun precomputeBiDirectional(
        data: List<Float>,
        maxAbs: Float,
        widthPx: Float,
        heightPx: Float
    ): PrecomputedBiDirectionalData = withContext(Dispatchers.Default) {
        val chargeNative = NativePath()
        val dischargeNative = NativePath()
        val chargeFillNative = NativePath()
        val dischargeFillNative = NativePath()

        val zeroY = heightPx / 2f
        val stepX = if (data.size >= 2) widthPx / (data.size - 1) else 0f

        val chargeList = ArrayList<Float>()
        val dischargeList = ArrayList<Float>()

        if (data.size >= 2 && widthPx > 0f && heightPx > 0f) {
            var prevVal = data[0]
            var prevY = zeroY - ((-prevVal / maxAbs).coerceIn(-1f, 1f) * zeroY)
            var prevX = 0f

            chargeFillNative.moveTo(0f, zeroY)
            dischargeFillNative.moveTo(0f, zeroY)

            for (i in 0 until data.size) {
                val valMa = data[i]
                if (valMa < 0f) chargeList.add(abs(valMa))
                else if (valMa > 0f) dischargeList.add(valMa)

                if (i == 0) continue

                val currX = i * stepX
                val normalized = (-valMa / maxAbs).coerceIn(-1f, 1f)
                val currY = zeroY - (normalized * zeroY)

                if ((prevVal <= 0f && valMa <= 0f) || (prevVal >= 0f && valMa >= 0f)) {
                    val targetPath = if (prevVal <= 0f) chargeNative else dischargeNative
                    val targetFill = if (prevVal <= 0f) chargeFillNative else dischargeFillNative
                    targetPath.moveTo(prevX, prevY)
                    targetPath.lineTo(currX, currY)
                    targetFill.lineTo(currX, currY)
                } else {
                    val t = (zeroY - prevY) / (currY - prevY)
                    val crossX = prevX + t * (currX - prevX)

                    val path1 = if (prevVal <= 0f) chargeNative else dischargeNative
                    val fill1 = if (prevVal <= 0f) chargeFillNative else dischargeFillNative
                    path1.moveTo(prevX, prevY)
                    path1.lineTo(crossX, zeroY)
                    fill1.lineTo(crossX, zeroY)

                    val path2 = if (valMa <= 0f) chargeNative else dischargeNative
                    val fill2 = if (valMa <= 0f) dischargeNative else dischargeFillNative
                    path2.moveTo(crossX, zeroY)
                    path2.lineTo(currX, currY)
                    fill2.lineTo(currX, currY)
                }

                prevX = currX
                prevY = currY
                prevVal = valMa
            }

            chargeFillNative.lineTo(widthPx, zeroY)
            chargeFillNative.close()
            dischargeFillNative.lineTo(widthPx, zeroY)
            dischargeFillNative.close()
        }

        PrecomputedBiDirectionalData(
            chargePath = chargeNative.asComposePath(),
            chargeFillPath = chargeFillNative.asComposePath(),
            dischargePath = dischargeNative.asComposePath(),
            dischargeFillPath = dischargeFillNative.asComposePath(),
            rawData = data,
            maxAbs = maxAbs,
            maxCharge = chargeList.maxOrNull() ?: 0f,
            avgCharge = if (chargeList.isNotEmpty()) chargeList.average().toFloat() else 0f,
            minCharge = chargeList.minOrNull() ?: 0f,
            maxDischarge = dischargeList.maxOrNull() ?: 0f,
            avgDischarge = if (dischargeList.isNotEmpty()) dischargeList.average().toFloat() else 0f,
            minDischarge = dischargeList.minOrNull() ?: 0f
        )
    }
}

private data class PrecomputedGraphState(
    val summaryData: SummaryUiModel,
    val fpsChart: PrecomputedLineChartData,
    val refreshChart: PrecomputedLineChartData,
    val wlanTxChart: PrecomputedLineChartData,
    val wlanTxUnit: String,
    val wlanTxFormat: String,
    val wlanRxChart: PrecomputedLineChartData,
    val wlanRxUnit: String,
    val wlanRxFormat: String,
    val cellTxChart: PrecomputedLineChartData,
    val cellTxUnit: String,
    val cellTxFormat: String,
    val cellRxChart: PrecomputedLineChartData,
    val cellRxUnit: String,
    val cellRxFormat: String,
    val ramChart: PrecomputedLineChartData,
    val ramMin: Float,
    val ramTotal: Float,
    val zramChart: PrecomputedLineChartData,
    val zramMin: Float,
    val zramTotal: Float,
    val romReadChart: PrecomputedLineChartData,
    val romWriteChart: PrecomputedLineChartData,
    val tempChart: PrecomputedLineChartData,
    val tempMin: Float,
    val batteryLevelChart: PrecomputedLineChartData,
    val voltageChart: PrecomputedLineChartData,
    val voltageMin: Float,
    val voltageMax: Float,
    val currentChart: PrecomputedBiDirectionalData,
    val powerChart: PrecomputedLineChartData,
    val gpuLoadChart: PrecomputedLineChartData,
    val gpuFreqChart: PrecomputedLineChartData,
    val gpuLimitStr: String?,
    val cpuCharts: List<Triple<Int, String?, PrecomputedLineChartData>>,
    val wlanTotalStr: String,
    val cellTotalStr: String,
    val wlanLossRate: Float,
    val cellLossRate: Float,
    val refreshRateMin: Float,
    val refreshRateMax: Float
)

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
