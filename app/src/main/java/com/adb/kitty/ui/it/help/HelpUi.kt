package com.adb.kitty.ui.it.help

import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.data.*
import com.adb.kitty.data.help.*
import com.adb.kitty.*
import com.adb.kitty.R

import androidx.annotation.Keep
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Keep
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandHelpBottomSheet(
    isVisible: Boolean,
    onDismissRequest: () -> Unit
) {
    if (!isVisible) return
    val commandList = remember {
        listOf(
            CommandHelp(
                title = "ADB 无线/有线调试",
                command = "adb",
                description = "使用有线/无线连接到adbd",
                options = listOf(
                    CommandOption(
                        flag = "adb pair [IP:配对端口] [配对码]",
                        description = "使用 adb pair 在同一 Wi-Fi 下进行 ADB 无线配对"
                    ),
                    CommandOption(
                        flag = "adb connect [IP:无线调试端口]",
                        description = "无线配对完成之后，使用 adb connect 连接到目标IP地址设备的 adbd, 进行无线调试"
                    ),
                    CommandOption(
                        flag = "adb push [本地文件名] [远端路径]",
                        description = "使用 adb push 将文件推送至目标设备的adb可访问的位置"
                    ),
                    CommandOption(
                        flag = "adb pull [远端路径] (可选本地落地名)",
                        description = "使用 adb pull 拉取目标设备的文件, 前提是adb可访问"
                    ),
                    CommandOption(
                        flag = "adb install [本地文件名]",
                        description = "使用 adb install 将apk、apks、xapk安装到目标设备上"
                    ),
                    CommandOption(
                        flag = "adb uninstall [包名]",
                        description = "使用 adb uninstall 卸载目标设备上的应用"
                    ),
                    CommandOption(
                        flag = "adb shell [指令] [选项] [参数]",
                        description = "使用 adb shell 通过 adbd 调用 Shell, 将拥有 uid 2000 的特权"
                    )
                )
            ),
            CommandHelp(
                title = "fastboot 刷机",
                command = "fastboot",
                description = "使用 fastboot 原生底层链路进行有线刷机, 高级玩法请迁移至 Termux 使用 fastboot 可执行文件来完成",
                options = listOf(
                    CommandOption(
                        flag = "reboot <可选参数>",
                        description = "进入 fastboot 后, 使用 reboot 进行重启操作"
                    ),
                    CommandOption(
                        flag = "getvar <参数>",
                        description = "进入 fastboot 后, 使用 getvar 进行查询操作"
                    ),
                    CommandOption(
                        flag = "oem <参数>",
                        description = "进入 fastboot 后, 使用 oem 进行解锁、回锁或查询操作"
                    ),
                    CommandOption(
                        flag = "erase <分区>",
                        description = "进入 fastboot 后, 使用 erase 进行擦除数据操作"
                    ),
                    CommandOption(
                        flag = "format <分区>",
                        description = "进入 fastboot 后, 使用 format 进行格式化操作"
                    ),
                    CommandOption(
                        flag = "set_active <a或b>",
                        description = "进入 fastboot 后, 使用 set_active 进行切换活跃插槽操作"
                    ),
                    CommandOption(
                        flag = "flash <分区> <路径>",
                        description = "进入 fastboot 后, 使用 flash 进行线刷操作"
                    ),
                    CommandOption(
                        flag = "boot <文件名>",
                        description = "进入 fastboot 后, 使用 boot 进行临时引导数据操作"
                    )
                )
            ),
            CommandHelp(
                title = "Shell",
                command = "id",
                description = "普通 Shell",
                options = listOf(
                    CommandOption(
                        flag = "su -c id",
                        description = "Root Shell, 需要授权 root 权限和传统 su 命令支持"
                    )
                )
            ),
            CommandHelp(
                title = "解析 APK 签名",
                command = "apk-sig 或 neko-sig",
                description = "使用 apk-sig 或 neko-sig 可调出底部对话框来从应用列表选择或从本地选择 APK 进行签名解析",
                options = emptyList()
            ),
            CommandHelp(
                title = "下载",
                command = "download <链接>",
                description = "使用 download 指令下载文件, 仅限 http/https 链接",
                options = emptyList()
            ),
            CommandHelp(
                title = "查询高级保护模式",
                command = "query-apm",
                description = "使用 query-apm 查询设备上的高级保护模式是否启用, Android 16+ 专属",
                options = emptyList()
            ),
            CommandHelp(
                title = "生成 QR 二维码",
                command = "qr-gen <文本>",
                description = "使用 qr-gen 可生成二维码, 最多 2000 字节",
                options = emptyList()
            ),
            CommandHelp(
                title = "二维码解码",
                command = "qr-decode <文件>",
                description = "使用 qr-decode 进行二维码解码操作",
                options = listOf(
                    CommandOption(
                        flag = "qr-decode --system",
                        description = "从系统图片选择器选择二维码图片来进行解码操作"
                    )
                )
            ),
            CommandHelp(
                title = "提取视频中的音频",
                command = "neko-audio",
                description = "使用 neko-audio 可唤起系统的视频选择器来选择视频进行提取音频的操作",
                options = emptyList()
            )
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.fillMaxHeight(1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = "指令集使用说明",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(commandList) { help ->
                    CommandHelpItem(help = help)
                }

                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                item {
                    SupportAndDependencySection()
                }
            }
        }
    }
}

@Keep
@Composable
private fun CommandHelpItem(help: CommandHelp) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = help.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = help.command,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = help.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (help.options.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "可选参数选项：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                Spacer(modifier = Modifier.height(4.dp))

                help.options.forEachIndexed { index, option ->
                    val isLast = index == help.options.lastIndex
                    val prefixBranch = if (isLast) "└─ " else "├─ "

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = prefixBranch,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.flag,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Keep
@Composable
private fun SupportAndDependencySection() {
    OutlinedCard(
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "依赖信息与支持范围",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            InfoRow(label = "System", value = "Android 7.0 - 17")
            InfoRow(label = "api", value = "24 - 37")
            InfoRow(label = "abi", value = "arm64-v8a, armeabi-v7a, x86, x86_64, riscv64")
            InfoRow(label = "github", value = "https://github.com/deleteFAILunknown/nekoStudio")
            InfoRow(label = "fastboot数据目录", value = "/storage/emulated/0/Android/data/com.adb.kitty/files/flash")
            InfoRow(label = "adb数据目录", value = "/storage/emulated/0/Android/data/com.adb.kitty/files/flash")
            InfoRow(label = "cpu数据目录", value = "/storage/emulated/0/Android/data/com.adb.kitty/files/cpu")
            InfoRow(label = "log数据目录", value = "/storage/emulated/0/Android/data/com.adb.kitty/cache/logs")
        }
    }
}

@Keep
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}
