package com.adb.kitty.ui.it

import android.*
import android.util.*
import android.content.pm.*
import android.graphics.*
import android.animation.*
import android.provider.*
import android.app.PendingIntent

import android.os.*
import android.view.*
import android.widget.*
import android.content.*
import android.hardware.usb.*

import android.net.*
import android.net.wifi.*
import android.net.nsd.*
import android.text.method.*

import androidx.core.view.*
import androidx.core.content.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
/*******************************
*        kotlinx 协程         *
*    suspend 都给我挂起     *
********************************/
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.*
import androidx.lifecycle.viewmodel.internal.*
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
import org.json.*

import android.os.*
import androidx.annotation.*
import androidx.activity.*
import androidx.activity.compose.*
import androidx.activity.result.contract.*
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.*
import androidx.lifecycle.viewmodel.compose.*
import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.text.selection.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.data.*
import com.adb.kitty.*
import com.adb.kitty.R

@Keep
@Composable
fun QrCodePopupDialog(
    contentString: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    
    val qrBitmap = remember(contentString) {
        QrCodeUtils.createQrCode(contentString, targetSize = 512)
    }
    
    val qrString = stringResource(R.string.action_qr_content)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.action_qr_okay)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Generated QR Code",
                        modifier = Modifier.size(240.dp),
                        filterQuality = FilterQuality.None 
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = "$qrString: $contentString",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(text = stringResource(R.string.action_qr_fail), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (qrBitmap != null) {
                        saveQrCodeToGallery(context, qrBitmap)
                    }
                }
            ) {
                Text(
                    stringResource(R.string.action_qr_store)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.action_qr_close)
                )
            }
        }
    )
}

@Keep
fun saveQrCodeToGallery(context: Context, bitmap: Bitmap, fileName: String = "QR_${System.currentTimeMillis()}") {
    val resolver = context.contentResolver
    val imageDetails = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UsbFlashQR")
    }

    val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageDetails)

    if (imageUri != null) {
        var outputStream: OutputStream? = null
        try {
            outputStream = resolver.openOutputStream(imageUri)
            if (outputStream != null) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                Toast.makeText(context, "二维码已保存至相册 Pictures/UsbFlashQR", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            try {
                outputStream?.close()
            } catch (_: Exception) {}
        }
    } else {
        Toast.makeText(context, "无法创建媒体文件条目", Toast.LENGTH_SHORT).show()
    }
}

@Keep
@Composable
fun QrDecodeResultDialog(
    rawResult: String,
    onDismiss: () -> Unit,
    onExportToFile: (String) -> Unit
) {
    val context = LocalContext.current
    val isList = rawResult.contains("\n")
    val listItems = if (isList) rawResult.split("\n").filter { it.isNotBlank() } else emptyList()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.action_qr_osc)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                SelectionContainer {
                    if (isList) {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(listItems) { item ->
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = item,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = rawResult,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onExportToFile(rawResult) }
                ) {
                    Text(stringResource(R.string.action_qr_osa), style = MaterialTheme.typography.labelMedium)
                }
                
                Button(
                    onClick = {
                        try {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = ClipData.newPlainText("QR_Result", rawResult)
                            
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "已复制全部内容到剪贴板", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(context, "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_qr_osb), style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.action_qr_close)
                )
            }
        }
    )
}

@Keep
fun saveTextToFlashFolder(context: Context, flashFolder: File, content: String): String? {
    try {
        if (!flashFolder.exists()) {
            flashFolder.mkdirs()
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "QR_DECODE_$timeStamp.txt"
        val targetFile = File(flashFolder, fileName)

        targetFile.writeText(content, Charsets.UTF_8)

        Toast.makeText(context, "文件已成功输出至 flash/$fileName", Toast.LENGTH_LONG).show()
        return fileName
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "文件写入失败: ${e.message}", Toast.LENGTH_SHORT).show()
        return null
    }
}
