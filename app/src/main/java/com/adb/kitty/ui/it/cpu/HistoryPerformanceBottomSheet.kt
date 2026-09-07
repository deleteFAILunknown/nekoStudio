package com.adb.kitty.ui.it.cpu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

    val avgFps = samples.map { it.fps }.average().toFloat()
    val maxTemp = samples.maxOfOrNull { it.batteryTemp } ?: 0f
    val avgCurrent = samples.map { it.batteryCurrentMa }.average().toFloat()
    val lastBatteryLevel = samples.lastOrNull()?.batteryLevel ?: 0
    val maxGpuLoad = samples.maxOfOrNull { it.gpuLoadPercent } ?: 0f
    val maxCpuCount = samples.maxOfOrNull { it.cpuFreqsGhz.size } ?: 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. 概要数据统计卡片（加入电量与平均电流）
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("时长", fontSize = 9.sp, color = Color.Gray)
                    Text("${history.durationSeconds} 秒", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("平均帧率", fontSize = 9.sp, color = Color.Gray)
                    Text(String.format(Locale.US, "%.2f FPS", avgFps), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF4CAF50))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("电量/最高温度", fontSize = 9.sp, color = Color.Gray)
                    Text("$lastBatteryLevel% / ${String.format(Locale.US, "%.1f", maxTemp)}°C", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFFFF5722))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("平均放电电流", fontSize = 9.sp, color = Color.Gray)
                    Text(String.format(Locale.US, "%.0f mA", avgCurrent), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFFFF9800))
                }
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
            displayValue = ramAvailList.lastOrNull() ?: 0f
        )

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
            displayValue = zramAvailList.lastOrNull() ?: 0f
        )

        // 3. 电池温度 (差值拉伸，1.0°C 极差保底)
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
            valueFormat = "%.2f",
            displayValue = tempTypeList.lastOrNull() ?: 0f
        )

        // 4. 新增：电池放电电流趋势图 (mA)
        val maxCurrent = (samples.maxOfOrNull { it.batteryCurrentMa } ?: 1000f).coerceAtLeast(500f)
        HistoryChartCard(
            title = "放电电流 (mA)",
            limitText = "电池剩余电量: $lastBatteryLevel%",
            data = samples.map { it.batteryCurrentMa },
            maxVal = maxCurrent,
            lineColor = Color(0xFFFF9800),
            unit = "mA"
        )

        // 5. GPU 负载趋势图（包含 Limit 范围）
        val gpuMin = samples.mapNotNull { if (it.gpuMinFreqGhz > 0) it.gpuMinFreqGhz else null }.firstOrNull() ?: 0f
        val gpuMax = samples.mapNotNull { if (it.gpuMaxFreqGhz > 0) it.gpuMaxFreqGhz else null }.firstOrNull() ?: 0f
        val gpuLimitStr = if (gpuMax > 0f) String.format(Locale.US, "Limit: %.3f - %.3f GHz", gpuMin, gpuMax) else null

        HistoryChartCard(
            title = "GPU 负载率 (%)",
            limitText = gpuLimitStr,
            data = samples.map { it.gpuLoadPercent },
            maxVal = 100f,
            lineColor = Color(0xFF9C27B0),
            unit = "%"
        )

        // 6. CPU 各核心频率轨迹（包含 HW 限制）
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

@Composable
private fun HistoryChartCard(
    title: String,
    limitText: String? = null,
    data: List<Float>,
    maxVal: Float,
    lineColor: Color,
    unit: String,
    valueFormat: String = "%.2f",
    displayValue: Float? = null
) {
    val curVal = displayValue ?: (data.lastOrNull() ?: 0f)
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
                Column {
                    Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    if (!limitText.isNullOrEmpty()) {
                        Text(
                            text = limitText,
                            fontSize = 9.5.sp,
                            color = Color.Gray
                        )
                    }
                }
                Text(
                    text = String.format(Locale.US, "$valueFormat %s", curVal, unit),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = lineColor
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            MetricLineChart(
                data = data,
                maxVal = maxVal,
                lineColor = lineColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
            )
        }
    }
}
