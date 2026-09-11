package com.adb.kitty.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.widget.Toast
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.annotation.Keep

@Keep
class PerformanceTurbo(private val context: Context) {

    private var hintSession: PerformanceHintManager.Session? = null

    suspend fun enterTurboMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            try {
                val hintManager = context.getSystemService<PerformanceHintManager>()
                if (hintManager != null) {
                    val myTid = Process.myTid()
                    // 智斗心理战：预期时间：7ms
                    val targetDurationNanos = 7_000_000L
                    hintSession = hintManager.createHintSession(intArrayOf(myTid), targetDurationNanos)
                    
                    // 智斗心理战提频：实际时间：0.8ms
                    hintSession?.reportActualWorkDuration(800_000L) 
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context.applicationContext, "✅ PerformanceHintManager", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun exitTurboMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hintSession?.close()
            hintSession = null
            
            withContext(Dispatchers.Main) {
                Toast.makeText(context.applicationContext, "❌ PerformanceHintManager", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
