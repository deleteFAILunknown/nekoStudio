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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow

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
        Text(
            text = "文件内容为空或格式不匹配",
            color = Color.Red,
            fontSize = 12.sp
        )
        return
    }

    val historyChannel = remember { Channel<HistoryRecording>(Channel.UNLIMITED) }
    var processedData by remember { mutableStateOf<ProcessedHistoryData?>(null) }

    // 生产者：当 history 发生变化时，非阻塞地把任务推入 Channel
    LaunchedEffect(history) {
        historyChannel.trySend(history)
    }

    // 消费者：在后台协程集中消费 Channel 中的数据并进行耗时计算
    LaunchedEffect(historyChannel) {
        withContext(Dispatchers.Default) {
            // 通过 consumeAsFlow 持续监听 Channel 中的数据更新
            historyChannel.consumeAsFlow().collect { incomingHistory ->
                val result = buildProcessedHistoryData(incomingHistory)
                withContext(Dispatchers.Main) {
                    processedData = result
                }
            }
        }
    }

    val data = processedData

    if (data == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "正在解析与生成图表数据…",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
        return
    }

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
                        value = String.format(Locale.US, "%.2f FPS", data.summaryData.maxFps),
                        valueColor = Color(0xFF4CAF50)
                    )
                    SummaryItem(
                        label = "最高刷新率",
                        value = String.format(Locale.US, "%.2f Hz", data.summaryData.maxRefreshRate),
                        valueColor = Color(0xFF00BCD4)
                    )
                    SummaryItem(
                        label = "电池电量",
                        value = "${data.summaryData.lastBatteryLevel}%",
                        valueColor = Color(0xFFFF5722)
                    )
                    SummaryItem(
                        label = "电池最高温度",
                        value = "${String.format(Locale.US, "%.1f", data.summaryData.maxTemp)}°C",
                        valueColor = Color(0xFFFF5722)
                    )
                    SummaryItem(
                        label = "最高放电电流",
                        value = String.format(Locale.US, "%.0f mA", data.summaryData.maxCurrent),
                        valueColor = Color(0xFFFF9800)
                    )
                    SummaryItem(
                        label = "最高功耗",
                        value = String.format(Locale.US, "%.2f W", data.summaryData.maxPower),
                        valueColor = Color(0xFFE91E63)
                    )
                    SummaryItem(
                        label = "最高电压",
                        value = String.format(Locale.US, "%.2fV", data.summaryData.maxVolt),
                        valueColor = Color(0xFFFFC107)
                    )
                    SummaryItem(
                        label = "电池SoH健康度",
                        value = if (data.summaryData.lastSoh > 0f) String.format(Locale.US, "%.1f%%", data.summaryData.lastSoh) else "Unknown",
                        valueColor = Color(0xFF4CAF50)
                    )
                    SummaryItem(
                        label = "循环次数",
                        value = if (data.summaryData.lastCycleCount > 0) "${data.summaryData.lastCycleCount} 次" else "未知",
                        valueColor = Color(0xFF00BCD4)
                    )
                    SummaryItem(
                        label = "电池状态",
                        value = data.summaryData.lastStatus
                    )
                    SummaryItem(
                        label = "充电类型",
                        value = data.summaryData.lastChargeType
                    )
                    SummaryItem(
                        label = "健康状况",
                        value = data.summaryData.lastHealth
                    )
                    SummaryItem(
                        label = "满电/设计容量",
                        value = String.format(Locale.US, "%.0f / %.0f mAh", data.summaryData.lastFullMah, data.summaryData.lastDesignMah)
                    )
                    SummaryItem(
                        label = "WLAN流量",
                        value = data.wlanTotalStr,
                        valueColor = Color(0xFF00BCD4)
                    )
                    SummaryItem(
                        label = "蜂窝流量",
                        value = data.cellTotalStr,
                        valueColor = Color(0xFF00BCD4)
                    )
                }
            }
        }

        item {
            HistoryChartCard(
                title = "帧率波动 (FPS)",
                data = data.fpsList,
                maxVal = data.maxFpsLimit,
                lineColor = Color(0xFF4CAF50),
                unit = "FPS"
            )
        }

        item {
            HistoryChartCard(
                title = "屏幕刷新率 (Hz)",
                limitText = String.format(Locale.US, "档位区间: %.0f Hz - %.0f Hz", data.refreshRateList.minOrNull() ?: 0f, data.maxRefreshRate),
                data = data.refreshRateList,
                maxVal = data.maxRefreshRate,
                lineColor = Color(0xFF00BCD4),
                unit = "Hz",
                valueFormat = "%.0f"
            )
        }

        item {
            HistoryChartCard(
                title = "WLAN 上传网速 (${data.wlanTx.unit})",
                limitText = String.format(Locale.US, "录制总流量: %s | 丢包率: %.2f%%", data.wlanTotalStr, samples.lastOrNull()?.wlanLossRate ?: 0f),
                data = data.wlanTx.data,
                maxVal = data.wlanTx.maxVal,
                lineColor = Color(0xFF0288D1),
                unit = data.wlanTx.unit,
                valueFormat = data.wlanTx.valueFormat
            )
        }

        item {
            HistoryChartCard(
                title = "WLAN 下载网速 (${data.wlanRx.unit})",
                limitText = String.format(Locale.US, "录制总流量: %s | 丢包率: %.2f%%", data.wlanTotalStr, samples.lastOrNull()?.wlanLossRate ?: 0f),
                data = data.wlanRx.data,
                maxVal = data.wlanRx.maxVal,
                lineColor = Color(0xFF00BCD4),
                unit = data.wlanRx.unit,
                valueFormat = data.wlanRx.valueFormat
            )
        }

        item {
            HistoryChartCard(
                title = "蜂窝网络上传网速 (${data.cellTx.unit})",
                limitText = String.format(Locale.US, "录制总流量: %s | 丢包率: %.2f%%", data.cellTotalStr, samples.lastOrNull()?.cellLossRate ?: 0f),
                data = data.cellTx.data,
                maxVal = data.cellTx.maxVal,
                lineColor = Color(0xFFC2185B),
                unit = data.cellTx.unit,
                valueFormat = data.cellTx.valueFormat
            )
        }

        item {
            HistoryChartCard(
                title = "蜂窝网络下载网速 (${data.cellRx.unit})",
                limitText = String.format(Locale.US, "录制总流量: %s | 丢包率: %.2f%%", data.cellTotalStr, samples.lastOrNull()?.cellLossRate ?: 0f),
                data = data.cellRx.data,
                maxVal = data.cellRx.maxVal,
                lineColor = Color(0xFFE91E63),
                unit = data.cellRx.unit,
                valueFormat = data.cellRx.valueFormat
            )
        }

        item {
            HistoryChartCard(
                title = "RAM 可用内存 (GB)",
                limitText = String.format(Locale.US, "RAM 总量: %.3f GB", data.ramData.third.second),
                data = data.ramData.first,
                maxVal = data.ramData.second,
                lineColor = Color(0xFF2196F3),
                unit = "GB",
                valueFormat = "%.3f",
                valueOffset = data.ramData.third.first
            )
        }

        item {
            HistoryChartCard(
                title = "ZRAM 可用内存 (GB)",
                limitText = String.format(Locale.US, "ZRAM 总量: %.3f GB", data.zramData.third.second),
                data = data.zramData.first,
                maxVal = data.zramData.second,
                lineColor = Color(0xFF00BCD4),
                unit = "GB",
                valueFormat = "%.3f",
                valueOffset = data.zramData.third.first
            )
        }

        item {
            HistoryChartCard(
                title = "ROM 读取速度 (MB/s)",
                data = data.romReadList,
                maxVal = (data.romReadList.maxOrNull() ?: 10f).coerceAtLeast(5f),
                lineColor = Color(0xFF3F51B5),
                unit = "MB/s",
                valueFormat = "%.2f"
            )
        }

        item {
            HistoryChartCard(
                title = "ROM 写入速度 (MB/s)",
                data = data.romWriteList,
                maxVal = (data.romWriteList.maxOrNull() ?: 10f).coerceAtLeast(5f),
                lineColor = Color(0xFF673AB7),
                unit = "MB/s",
                valueFormat = "%.2f"
            )
        }

        item {
            HistoryChartCard(
                title = "电池温度 (°C)",
                data = data.tempData.first,
                maxVal = data.tempData.second.first,
                lineColor = Color(0xFFFF5722),
                unit = "°C",
                valueFormat = "%.1f",
                valueOffset = data.tempData.second.second
            )
        }

        item {
            HistoryChartCard(
                title = "电池电量 (%)",
                limitText = String.format(
                    Locale.US, "变化区间: %.0f%% - %.0f%% | 最终电量: %d%%",
                    data.batteryLevelList.minOrNull() ?: 0f, data.batteryLevelList.maxOrNull() ?: 0f, data.summaryData.lastBatteryLevel
                ),
                data = data.batteryLevelList,
                maxVal = 100f,
                lineColor = Color(0xFFFF5722),
                unit = "%",
                valueFormat = "%.0f"
            )
        }

        item {
            HistoryChartCard(
                title = "电池电压 (V)",
                limitText = String.format(Locale.US, "范围: %.2f V - %.2f V", data.voltageData.third.first, data.voltageData.third.second),
                data = data.voltageData.first,
                maxVal = data.voltageData.second,
                lineColor = Color(0xFFFBBC02),
                unit = "V",
                valueFormat = "%.2f",
                valueOffset = data.voltageData.third.first
            )
        }

        item {
            BiDirectionalCurrentCard(currentData = data.currentList)
        }

        item {
            val batCapLimitStr = if (data.summaryData.lastFullMah > 0f) {
                String.format(
                    Locale.US, "SoH: %.1f%% | 循环: %d次 | 容量: %.0f/%.0f mAh",
                    data.summaryData.lastSoh, data.summaryData.lastCycleCount, data.summaryData.lastFullMah, data.summaryData.lastDesignMah
                )
            } else null

            HistoryChartCard(
                title = "电池实时功率/功耗 (W)",
                limitText = batCapLimitStr,
                data = data.powerList,
                maxVal = (data.powerList.maxOrNull() ?: 5f).coerceAtLeast(1f),
                lineColor = Color(0xFFE91E63),
                unit = "W",
                valueFormat = "%.2f"
            )
        }

        item {
            HistoryChartCard(
                title = "GPU 负载率 (%)",
                limitText = data.gpuLimitStr,
                data = data.gpuLoadList,
                maxVal = 100f,
                lineColor = Color(0xFF9C27B0),
                unit = "%"
            )
        }

        item {
            HistoryChartCard(
                title = "GPU 运行频率 (GHz)",
                limitText = data.gpuLimitStr,
                data = data.gpuFreqList,
                maxVal = (data.gpuFreqList.maxOrNull() ?: 1f).coerceAtLeast(0.5f),
                lineColor = Color(0xFFAB47BC),
                unit = "GHz",
                valueFormat = "%.3f"
            )
        }

        item {
            Text(
                text = "CPU 核心频率轨迹 (GHz)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(
            items = data.cpuCoreModels,
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
                            Text(
                                text = "CPU 核心 ${coreModel.coreIndex}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
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
                            .height(160.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun FastMetricLineChart(
    data: List<Float>,
    maxVal: Float,
    lineColor: Color,
    modifier: Modifier = Modifier,
    unit: String = "",
    valueFormat: String = "%.3f",
    valueOffset: Float = 0f
) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current

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
                        selectedIndex = null
                    }
                }
                .drawWithCache {
                    val linePath = Path()
                    val fillPath = Path()
                    val effectiveMax = if (maxVal <= 0f) 1f else maxVal
                    val stepX = if (data.size >= 2) size.width / (data.size - 1) else 0f

                    if (data.size >= 2) {
                        data.forEachIndexed { i, value ->
                            val x = i * stepX
                            val y = size.height - ((value / effectiveMax).coerceIn(0f, 1f) * size.height)
                            if (i == 0) {
                                linePath.moveTo(x, y)
                                fillPath.moveTo(x, y)
                            } else {
                                linePath.lineTo(x, y)
                                fillPath.lineTo(x, y)
                            }
                        }
                        fillPath.lineTo(size.width, size.height)
                        fillPath.lineTo(0f, size.height)
                        fillPath.close()
                    }

                    val strokePx = 1.5.dp.toPx()
                    val dashHeightPx = 160.dp.toPx()

                    onDrawBehind {
                        if (data.size >= 2) {
                            drawPath(
                                path = fillPath,
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        lineColor.copy(alpha = 0.35f),
                                        lineColor.copy(alpha = 0.02f)
                                    ),
                                    startY = 0f,
                                    endY = size.height
                                )
                            )

                            drawPath(
                                path = linePath,
                                color = lineColor,
                                style = Stroke(width = strokePx, cap = StrokeCap.Round)
                            )
                        }

                        selectedIndex?.let { index ->
                            if (index in data.indices) {
                                val rawVal = data[index]
                                val x = index * stepX
                                val y = size.height - ((rawVal / effectiveMax).coerceIn(0f, 1f) * size.height)

                                drawLine(
                                    color = lineColor.copy(alpha = 0.7f),
                                    start = Offset(x, 0f),
                                    end = Offset(x, dashHeightPx),
                                    strokeWidth = 1.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                                )

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

        // 使用 Popup 浮层挂载数据气泡
        selectedIndex?.let { index ->
            if (index in data.indices && data.size >= 2) {
                val realVal = data[index] + valueOffset
                val formattedVal = String.format(Locale.US, valueFormat, realVal)
                val textStr = if (unit.isNotEmpty()) "$formattedVal $unit" else formattedVal

                val stepX = widthPx / (data.size - 1)
                val lineXPx = (stepX * index).roundToInt()
                val lineXDp = with(density) { lineXPx.toDp() }

                val isRightHalf = index > (data.size - 1) / 2
                val yPx = with(density) { (-30).dp.roundToPx() }

                if (isRightHalf) {
                    val offsetFromRightPx = lineXPx - constraints.maxWidth
                    Popup(
                        alignment = Alignment.TopEnd,
                        offset = IntOffset(x = offsetFromRightPx, y = yPx),
                        properties = PopupProperties(
                            focusable = false,
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false,
                            clippingEnabled = false
                        )
                    ) {
                        Surface(
                            color = Color.White.copy(alpha = 0.90f),
                            shape = RoundedCornerShape(6.dp),
                            shadowElevation = 3.dp
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
                } else {
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(x = lineXPx, y = yPx),
                        properties = PopupProperties(
                            focusable = false,
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false,
                            clippingEnabled = false
                        )
                    ) {
                        Surface(
                            color = Color.White.copy(alpha = 0.90f),
                            shape = RoundedCornerShape(6.dp),
                            shadowElevation = 3.dp
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
                    .height(160.dp)
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
                    .height(160.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
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
                text = "💡 提示：电流硬件节点存在延迟刷新，这有可能是厂商的预期行为，因此充电、放电的数据仅供参考",
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
fun BiDirectionalMetricChart(
    data: List<Float>,
    maxAbs: Float,
    modifier: Modifier = Modifier,
    chargeColor: Color = Color(0xFF4CAF50),
    dischargeColor: Color = Color(0xFFFF5722)
) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current

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
                    val chargeFillPath = Path()
                    val dischargeFillPath = Path()

                    val stepX = if (data.size >= 2) size.width / (data.size - 1) else 0f
                    val zeroY = size.height / 2f

                    if (data.size >= 2) {
                        var prevPoint = Offset(
                            0f,
                            zeroY - ((-data[0] / maxAbs).coerceIn(-1f, 1f) * zeroY)
                        )
                        var prevVal = data[0]

                        chargeFillPath.moveTo(0f, zeroY)
                        dischargeFillPath.moveTo(0f, zeroY)

                        for (i in 1 until data.size) {
                            val valMa = data[i]
                            val x = i * stepX
                            val normalized = (-valMa / maxAbs).coerceIn(-1f, 1f)
                            val currPoint = Offset(x, zeroY - (normalized * zeroY))

                            if ((prevVal <= 0f && valMa <= 0f) || (prevVal >= 0f && valMa >= 0f)) {
                                val targetPath = if (prevVal <= 0f) chargePath else dischargePath
                                val targetFill = if (prevVal <= 0f) chargeFillPath else dischargeFillPath
                                targetPath.moveTo(prevPoint.x, prevPoint.y)
                                targetPath.lineTo(currPoint.x, currPoint.y)
                                targetFill.lineTo(currPoint.x, currPoint.y)
                            } else {
                                val t = (zeroY - prevPoint.y) / (currPoint.y - prevPoint.y)
                                val crossPoint = Offset(prevPoint.x + t * (currPoint.x - prevPoint.x), zeroY)

                                val path1 = if (prevVal <= 0f) chargePath else dischargePath
                                val fill1 = if (prevVal <= 0f) chargeFillPath else dischargeFillPath
                                path1.moveTo(prevPoint.x, prevPoint.y)
                                path1.lineTo(crossPoint.x, crossPoint.y)
                                fill1.lineTo(crossPoint.x, crossPoint.y)

                                val path2 = if (valMa <= 0f) chargePath else dischargePath
                                val fill2 = if (valMa <= 0f) chargeFillPath else dischargeFillPath
                                path2.moveTo(crossPoint.x, crossPoint.y)
                                path2.lineTo(currPoint.x, currPoint.y)
                                fill2.lineTo(currPoint.x, currPoint.y)
                            }

                            prevPoint = currPoint
                            prevVal = valMa
                        }

                        chargeFillPath.lineTo(size.width, zeroY)
                        chargeFillPath.close()

                        dischargeFillPath.lineTo(size.width, zeroY)
                        dischargeFillPath.close()
                    }

                    val strokePx = 2.dp.toPx()
                    val dashHeightPx = 160.dp.toPx()

                    onDrawBehind {
                        drawPath(
                            path = chargeFillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(chargeColor.copy(alpha = 0.35f), Color.Transparent),
                                startY = 0f,
                                endY = zeroY
                            )
                        )
                        drawPath(
                            path = dischargeFillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, dischargeColor.copy(alpha = 0.35f)),
                                startY = zeroY,
                                endY = size.height
                            )
                        )

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
                                    end = Offset(x, dashHeightPx),
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

        // 使用 Popup 浮层挂载数据气泡
        selectedIndex?.let { index ->
            if (index in data.indices && data.size >= 2) {
                val currentMa = data[index]
                val textStr = if (currentMa < 0f) {
                    String.format(Locale.US, "%.3f mA (充电)", abs(currentMa))
                } else {
                    String.format(Locale.US, "%.3f mA (放电)", currentMa)
                }

                val stepX = widthPx / (data.size - 1)
                val lineXPx = (stepX * index).roundToInt()
                val lineXDp = with(density) { lineXPx.toDp() }

                val isRightHalf = index > (data.size - 1) / 2
                val yPx = with(density) { (-30).dp.roundToPx() }

                if (isRightHalf) {
                    val offsetFromRightPx = lineXPx - constraints.maxWidth
                    Popup(
                        alignment = Alignment.TopEnd,
                        offset = IntOffset(x = offsetFromRightPx, y = yPx),
                        properties = PopupProperties(
                            focusable = false,
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false,
                            clippingEnabled = false
                        )
                    ) {
                        Surface(
                            color = Color.White.copy(alpha = 0.90f),
                            shape = RoundedCornerShape(6.dp),
                            shadowElevation = 3.dp
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
                } else {
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(x = lineXPx, y = yPx),
                        properties = PopupProperties(
                            focusable = false,
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false,
                            clippingEnabled = false
                        )
                    ) {
                        Surface(
                            color = Color.White.copy(alpha = 0.90f),
                            shape = RoundedCornerShape(6.dp),
                            shadowElevation = 3.dp
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

private data class ProcessedHistoryData(
    val summaryData: SummaryUiModel,
    val cpuCoreModels: List<DynamicCpuCoreModel>,
    val fpsList: List<Float>,
    val maxFpsLimit: Float,
    val refreshRateList: List<Float>,
    val maxRefreshRate: Float,
    val wlanTotalStr: String,
    val cellTotalStr: String,
    val wlanTx: ScaledSpeedData,
    val wlanRx: ScaledSpeedData,
    val cellTx: ScaledSpeedData,
    val cellRx: ScaledSpeedData,
    val ramData: Triple<List<Float>, Float, Pair<Float, Float>>,
    val zramData: Triple<List<Float>, Float, Pair<Float, Float>>,
    val romReadList: List<Float>,
    val romWriteList: List<Float>,
    val tempData: Pair<List<Float>, Pair<Float, Float>>,
    val batteryLevelList: List<Float>,
    val voltageData: Triple<List<Float>, Float, Pair<Float, Float>>,
    val currentList: List<Float>,
    val powerList: List<Float>,
    val gpuLoadList: List<Float>,
    val gpuFreqList: List<Float>,
    val gpuLimitStr: String?
)

/**
 * Min-Max 桶降采样：将数据分成 maxPoints/2 个区间，每个区间取最小值和最大值
 * 保证极短时间的单点突刺（Spike）和骤降（Drop）在视觉折线上 100% 留存
 */
private fun List<Float>.downsample(maxPoints: Int = 400): List<Float> {
    if (size <= maxPoints) return this
    val numBuckets = maxPoints / 2
    val bucketSize = size.toFloat() / numBuckets
    val result = ArrayList<Float>(maxPoints)

    for (i in 0 until numBuckets) {
        val start = (i * bucketSize).toInt().coerceIn(0, lastIndex)
        val end = ((i + 1) * bucketSize).toInt().coerceIn(start + 1, size)

        var min = this[start]
        var max = this[start]
        var minIdx = start
        var maxIdx = start

        for (j in start until end) {
            val v = this[j]
            if (v < min) { min = v; minIdx = j }
            if (v > max) { max = v; maxIdx = j }
        }

        // 按时间先后顺序加入 min 和 max，保持时间线连续
        if (minIdx < maxIdx) {
            result.add(min)
            result.add(max)
        } else {
            result.add(max)
            result.add(min)
        }
    }
    return result
}

/**
 * 后台线程数据构建方法：避免 CPU 多核逻辑重复循环，并使用 downsample() 优化渲染性能
 */
private fun buildProcessedHistoryData(history: HistoryRecording): ProcessedHistoryData {
    val samples = history.samples

    var maxFps = 0f
    var maxRefreshRate = 0f
    var maxTemp = 0f
    var maxCurrent = 0f
    var maxVolt = 0f
    var maxPower = 0f

    samples.forEach { s ->
        if (s.fps > maxFps) maxFps = s.fps
        if (s.refreshRate > maxRefreshRate) maxRefreshRate = s.refreshRate
        if (s.batteryTemp > maxTemp) maxTemp = s.batteryTemp
        if (s.batteryCurrentMa > maxCurrent) maxCurrent = s.batteryCurrentMa
        val voltSec = s.batteryVoltageMv / 1000f
        if (voltSec > maxVolt) maxVolt = voltSec
        if (s.batteryPowerW > maxPower) maxPower = s.batteryPowerW
    }

    val lastSample = samples.lastOrNull()
    val summaryData = SummaryUiModel(
        maxFps = maxFps,
        maxRefreshRate = maxRefreshRate,
        maxTemp = maxTemp,
        maxCurrent = maxCurrent,
        maxVolt = maxVolt,
        maxPower = maxPower,
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

    // CPU 多核数据解析优化：仅进行一次转置
    val coreCount = samples.maxOfOrNull { it.cpuFreqsGhz.size } ?: 0
    val rawCpuFreqs = List(coreCount) { ArrayList<Float>(samples.size) }
    val limitsMap = HashMap<Int, Pair<Float, Float>>()

    samples.forEach { sample ->
        for (c in 0 until coreCount) {
            val freq = sample.cpuFreqsGhz.getOrNull(c) ?: 0f
            rawCpuFreqs[c].add(freq)

            if (!limitsMap.containsKey(c)) {
                sample.cpuHwLimitsGhz.getOrNull(c)?.let {
                    if (it.second > 0f) limitsMap[c] = it
                }
            }
        }
    }

    val cpuCoreModels = (0 until coreCount).map { coreIndex ->
        val rawFreqs = rawCpuFreqs[coreIndex]
        val minVal = rawFreqs.minOrNull() ?: 0f
        val maxValReal = rawFreqs.maxOrNull() ?: 0f
        val avgVal = if (rawFreqs.isNotEmpty()) rawFreqs.average().toFloat() else 0f

        val limits = limitsMap[coreIndex]
        val hwLimitStr = if (limits != null && limits.second > 0f) {
            String.format(Locale.US, "HW: %.3f - %.3f GHz", limits.first, limits.second)
        } else null

        DynamicCpuCoreModel(
            coreIndex = coreIndex,
            freqs = rawFreqs.downsample(400),
            minVal = minVal,
            maxValReal = maxValReal,
            avgVal = avgVal,
            hwLimitStr = hwLimitStr,
            maxChartVal = maxValReal.coerceAtLeast(1f)
        )
    }

    // 基础指标提取并进行 downsample
    val rawFpsList = samples.map { it.fps }
    val maxFpsLimit = samples.maxOfOrNull { it.refreshRate }?.coerceAtLeast(60f) ?: 60f

    val rawRefreshList = samples.map { it.refreshRate }
    val maxRefresh = rawRefreshList.maxOrNull() ?: 60f

    val wlanTotalStr = formatMb(summaryData.lastWlanTotal)
    val cellTotalStr = formatMb(summaryData.lastCellTotal)

    val wlanTx = autoScaleKbpsList(samples.map { it.wlanTxSpeedKbps })
    val wlanRx = autoScaleKbpsList(samples.map { it.wlanRxSpeedKbps })
    val cellTx = autoScaleKbpsList(samples.map { it.cellTxSpeedKbps })
    val cellRx = autoScaleKbpsList(samples.map { it.cellRxSpeedKbps })

    // RAM 数据
    val ramList = samples.map { it.ramAvailGb }
    val ramMin = ramList.minOrNull() ?: 0f
    val ramMax = ramList.maxOrNull() ?: 0f
    val ramTotal = samples.lastOrNull()?.ramTotalGb ?: 1f
    val ramData = Triple(
        ramList.map { it - ramMin }.downsample(400),
        (ramMax - ramMin).coerceAtLeast(0.3f),
        ramMin to ramTotal
    )

    // ZRAM 数据
    val zramList = samples.map { it.zramAvailGb }
    val zramMin = zramList.minOrNull() ?: 0f
    val zramMax = zramList.maxOrNull() ?: 0f
    val zramTotal = samples.lastOrNull()?.zramTotalGb ?: 1f
    val zramData = Triple(
        zramList.map { it - zramMin }.downsample(400),
        (zramMax - zramMin).coerceAtLeast(0.3f),
        zramMin to zramTotal
    )

    // 温度数据
    val tempList = samples.map { it.batteryTemp }
    val tempMin = tempList.minOrNull() ?: 0f
    val tempMax = tempList.maxOrNull() ?: 0f
    val tempData = Pair(
        tempList.map { it - tempMin }.downsample(400),
        (tempMax - tempMin).coerceAtLeast(1.0f) to tempMin
    )

    // 电压数据
    val voltList = samples.map { it.batteryVoltageMv / 1000f }
    val voltMin = voltList.minOrNull() ?: 0f
    val voltMax = voltList.maxOrNull() ?: 0f
    val voltageData = Triple(
        voltList.map { it - voltMin }.downsample(400),
        (voltMax - voltMin).coerceAtLeast(0.1f),
        voltMin to voltMax
    )

    val gpuMin = samples.mapNotNull { if (it.gpuMinFreqGhz > 0) it.gpuMinFreqGhz else null }.firstOrNull() ?: 0f
    val gpuMax = samples.mapNotNull { if (it.gpuMaxFreqGhz > 0) it.gpuMaxFreqGhz else null }.firstOrNull() ?: 0f
    val gpuLimitStr = if (gpuMax > 0f) String.format(Locale.US, "Limit: %.3f - %.3f GHz", gpuMin, gpuMax) else null

    return ProcessedHistoryData(
        summaryData = summaryData,
        cpuCoreModels = cpuCoreModels,
        fpsList = rawFpsList.downsample(400),
        maxFpsLimit = maxFpsLimit,
        refreshRateList = rawRefreshList.downsample(400),
        maxRefreshRate = maxRefresh,
        wlanTotalStr = wlanTotalStr,
        cellTotalStr = cellTotalStr,
        wlanTx = wlanTx.copy(data = wlanTx.data.downsample(400)),
        wlanRx = wlanRx.copy(data = wlanRx.data.downsample(400)),
        cellTx = cellTx.copy(data = cellTx.data.downsample(400)),
        cellRx = cellRx.copy(data = cellRx.data.downsample(400)),
        ramData = ramData,
        zramData = zramData,
        romReadList = samples.map { it.romReadSpeedMb }.downsample(400),
        romWriteList = samples.map { it.romWriteSpeedMb }.downsample(400),
        tempData = tempData,
        batteryLevelList = samples.map { it.batteryLevel.toFloat() }.downsample(400),
        voltageData = voltageData,
        currentList = samples.map { it.batteryCurrentMa }.downsample(400),
        powerList = samples.map { it.batteryPowerW }.downsample(400),
        gpuLoadList = samples.map { it.gpuLoadPercent }.downsample(400),
        gpuFreqList = samples.map { it.gpuFreqGhz }.downsample(400),
        gpuLimitStr = gpuLimitStr
    )
}

data class DynamicCpuCoreModel(
    val coreIndex: Int,
    val freqs: List<Float>,
    val minVal: Float,
    val maxValReal: Float,
    val avgVal: Float,
    val hwLimitStr: String?,
    val maxChartVal: Float
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
