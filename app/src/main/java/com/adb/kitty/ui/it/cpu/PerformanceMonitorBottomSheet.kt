package com.adb.kitty.ui.it.cpu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

val CoreColors = listOf(
    Color(0xFF2196F3), Color(0xFF03A9F4), Color(0xFF00BCD4), Color(0xFF009688),
    Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFFFF5722), Color(0xFFE91E63)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompletePerformanceMonitorBottomSheet(
    uiState: PerformanceUiState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onExportCsv: (csvContent: String) -> Unit,
    onDismissRequest: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 顶栏：标题 + 录制按钮 + ROOT 状态标识
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚡ 硬件性能与热状态监控",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 仅在手动点击时触发录制/停止
                    Button(
                        onClick = {
                            if (uiState.isRecording) onStopRecording() else onStartRecording()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.isRecording) Color.Red else MaterialTheme.colorScheme.primary
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(
                            text = if (uiState.isRecording) "⏹ 停止 (${uiState.recordedDurationSeconds}s)" else "🔴 开始录制",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = if (uiState.isRootConnected) "ROOT ACTIVE" else "WAIT",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (uiState.isRootConnected) Color(0xFFFF5252) else Color.Gray,
                        modifier = Modifier
                            .background(
                                (if (uiState.isRootConnected) Color(0xFFFF5252) else Color.Gray).copy(alpha = 0.12f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }

            // 录制停止后弹出的保存 CSV 提示卡片
            AnimatedVisibility(visible = uiState.exportCsvContent != null) {
                uiState.exportCsvContent?.let { csv ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("✅ 数据录制已完成", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("采样已停止，准备保存为电子表格", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                            }
                            Button(
                                onClick = { onExportCsv(csv) },
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) {
                                Text("💾 导出 CSV", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // 1. 全局系统指标卡片 (FPS / 刷新率 / 电池温度)
            SystemSummaryCard(state = uiState)

            MemoryStatusCard(
                ramTotalGb = uiState.ramTotalGb,
                ramAvailGb = uiState.ramAvailGb,
                ramHistory = uiState.ramAvailHistory,
                zramTotalGb = uiState.zramTotalGb,
                zramAvailGb = uiState.zramAvailGb,
                zramHistory = uiState.zramAvailHistory
            )

            BatteryStatusCard(
                batteryLevel = uiState.batteryLevel,
                batteryTemp = uiState.batteryTemp,
                batteryCurrentMa = uiState.batteryCurrentMa,
                historyData = uiState.batteryCurrentHistory
            )

            // 2. 屏幕显示参数卡片 (屏幕 API 读取当前分辨率与支持模式)
            DisplayInfoCard(uiState = uiState)

            // 3. GPU 指标卡片
            GpuMetricCard(gpu = uiState.gpuMetric)

            // 4. CPU 核心集群网格
            Text(
                text = "CPU 核心集群 (${uiState.cpuCores.size} Cores / IPC Pure HW)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            uiState.cpuCores.chunked(2).forEach { rowCores ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    rowCores.forEach { core ->
                        SingleCoreCard(core = core, modifier = Modifier.weight(1f))
                    }
                    if (rowCores.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun MemoryStatusCard(
    ramTotalGb: Float,
    ramAvailGb: Float,
    ramHistory: List<Float>,
    zramTotalGb: Float,
    zramAvailGb: Float,
    zramHistory: List<Float>
) {
    val ramUsedGb = (ramTotalGb - ramAvailGb).coerceAtLeast(0f)
    val zramUsedGb = (zramTotalGb - zramAvailGb).coerceAtLeast(0f)
    val ramUsedPercent = if (ramTotalGb > 0) (ramUsedGb / ramTotalGb) * 100f else 0f
    val zramUsedPercent = if (zramTotalGb > 0) (zramUsedGb / zramTotalGb) * 100f else 0f
    val minRam = (ramHistory.minOrNull() ?: 0f) * 0.95f
    val maxRam = (ramHistory.maxOrNull() ?: 1f) * 1.05f
    val minZram = (zramHistory.minOrNull() ?: 0f) * 0.95f
    val maxZram = (zramHistory.maxOrNull() ?: 1f) * 1.05f

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // RAM 物理内存
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("RAM 物理内存", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = String.format(Locale.US, "已用: %.3f GB / 总量: %.3f GB (可用: %.3f GB)", ramUsedGb, ramTotalGb, ramAvailGb),
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                }
                Text(
                    text = String.format(Locale.US, "%.1f%%", ramUsedPercent),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2196F3)
                )
            }
            MetricLineChart(
                data = ramHistory,
                minVal = minRam,
                maxVal = maxRam,
                lineColor = Color(0xFF2196F3),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
            )

            // ZRAM 虚拟内存
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("ZRAM 虚拟内存", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = String.format(Locale.US, "已用: %.3f GB / 总量: %.3f GB (可用: %.3f GB)", zramUsedGb, zramTotalGb, zramAvailGb),
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                }
                Text(
                    text = String.format(Locale.US, "%.1f%%", zramUsedPercent),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00BCD4)
                )
            }
            MetricLineChart(
                data = zramHistory,
                minVal = minZram,
                maxVal = maxZram,
                lineColor = Color(0xFF00BCD4),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
            )
        }
    }
}

@Composable
fun BatteryStatusCard(
    batteryLevel: Int,
    batteryTemp: Float,
    batteryCurrentMa: Float,
    historyData: List<Float>
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("电池功耗状态", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("剩余电量: $batteryLevel% | 温度: ${String.format(Locale.US, "%.1f", batteryTemp)}°C", fontSize = 10.sp, color = Color.Gray)
                }
                Text(
                    text = String.format(Locale.US, "%.0f mA", batteryCurrentMa),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF9800)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 实时放电电流折线图
            MetricLineChart(
                data = historyData,
                maxVal = (historyData.maxOrNull() ?: 1000f).coerceAtLeast(500f),
                lineColor = Color(0xFFFF9800),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
            )
        }
    }
}

@Composable
fun SystemSummaryCard(state: PerformanceUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("帧率波动", fontSize = 10.sp, color = Color.Gray)
                    Text(
                        text = String.format(Locale.US, "%.2f FPS", state.renderFps),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("屏幕刷新率", fontSize = 10.sp, color = Color.Gray)
                    Text(
                        text = String.format(Locale.US, "%.2f Hz", state.refreshRateHz),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("电池温度", fontSize = 10.sp, color = Color.Gray)
                    Text(
                        text = String.format(Locale.US, "%.1f °C", state.batteryTemp),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (state.batteryTemp > 45f) Color.Red else Color(0xFFFF5722)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            MetricLineChart(
                data = state.fpsHistory,
                maxVal = state.refreshRateHz.coerceAtLeast(60f),
                lineColor = Color(0xFF4CAF50),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
            )
        }
    }
}

@Composable
fun DisplayInfoCard(uiState: PerformanceUiState) {
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📺 屏幕参数 (DisplayManager)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "当前: ${uiState.currentResolution}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            Text(
                text = "支持的分辨率与帧率模式：",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OptInFlowRow(modes = uiState.supportedDisplayModes)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptInFlowRow(modes: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        modes.forEach { mode ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(
                    0.5.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                )
            ) {
                Text(
                    text = mode,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
fun GpuMetricCard(gpu: GpuMetric) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("GPU 核心", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${gpu.utilizationPercent.toInt()}% Load",
                        fontSize = 10.sp,
                        color = Color(0xFF9C27B0),
                        modifier = Modifier
                            .background(Color(0xFF9C27B0).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = String.format(Locale.US, "%.3f GHz", gpu.curFreqGhz),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF9C27B0)
                )
            }
            Text(
                text = String.format(Locale.US, "Limit: %.3f - %.3f GHz", gpu.minFreqGhz, gpu.maxFreqGhz),
                fontSize = 10.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            MetricLineChart(
                data = gpu.history,
                maxVal = gpu.maxFreqGhz.coerceAtLeast(0.1f),
                lineColor = Color(0xFF9C27B0),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
            )
        }
    }
}

@Composable
fun SingleCoreCard(core: CpuCoreMetric, modifier: Modifier = Modifier) {
    val color = CoreColors.getOrElse(core.coreIndex) { Color.Gray }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Core ${core.coreIndex}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    text = String.format(Locale.US, "%.3f GHz", core.curFreqGhz),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = color
                )
            }
            Text(
                text = String.format(Locale.US, "HW: %.3f-%.3fG", core.minFreqGhz, core.maxFreqGhz),
                fontSize = 9.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(6.dp))
            MetricLineChart(
                data = core.history,
                maxVal = core.maxFreqGhz.coerceAtLeast(0.1f),
                lineColor = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
            )
        }
    }
}

@Composable
fun MetricLineChart(
    data: List<Float>,
    maxVal: Float,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (data.size < 2) return@Canvas
        val width = size.width
        val height = size.height
        val stepX = width / (data.size - 1)

        val path = Path()
        val fillPath = Path()

        data.forEachIndexed { i, value ->
            val x = i * stepX
            val normalized = (value / maxVal).coerceIn(0f, 1f)
            val y = height - (normalized * height)

            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, height)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        fillPath.lineTo((data.size - 1) * stepX, height)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.35f), Color.Transparent)
            )
        )
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}
