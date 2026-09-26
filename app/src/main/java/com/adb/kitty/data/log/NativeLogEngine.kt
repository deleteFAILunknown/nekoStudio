package com.adb.kitty.data.log

import android.content.ComponentCallbacks2
import com.adb.kitty.data.NativeLibs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object NativeLogEngine {

    // 进程级独立作用域：不随 Activity/ViewModel 销毁而中止
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logInputChannel = Channel<String>(Channel.UNLIMITED)

    // UI 刷新版本号：仅通知 UI 界面“数据有更新，可读取堆外 Buffer”
    private val _uiUpdateVersion = MutableStateFlow(0L)
    val uiUpdateVersion: StateFlow<Long> = _uiUpdateVersion.asStateFlow()

    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")
    private var lastSecondTimestamp: Long = 0L
    private var cachedTimeString: String = ""

    val isLogEmpty: Boolean
        get() = NativeLibs.getWriteOffset() <= 0L

    init {
        // 全局单线程消费通道：确保串行写入 C++ 堆外内存，无需加锁
        logScope.launch {
            for (msg in logInputChannel) {
                processAndPushLog(msg)
                _uiUpdateVersion.value++
            }
        }
    }

    /**
     * 全局日志非阻塞写入入口 (线程安全)
     */
    fun appendLog(msg: String) {
        logInputChannel.trySend(msg)
    }

    /**
     * 多行解析 + 秒级缓存时间戳 + 直推 C++ 堆外内存
     */
    private fun processAndPushLog(msg: String) {
        val timeStr = getCachedTimeStamp()

        var start = 0
        val len = msg.length
        while (start < len) {
            var end = msg.indexOf('\n', start)
            if (end == -1) end = len

            if (end > start) {
                val line = msg.substring(start, end)
                if (line.isNotBlank()) {
                    // C++ append_fast 会自动处理内存越界检查与尾部换行
                    NativeLibs.appendNativeLog("$timeStr $line")
                }
            }
            start = end + 1
        }
    }

    /**
     * 内存紧张处理 / 系统回调
     */
    fun onTrimMemoryRequested(level: Int = ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
        while (logInputChannel.tryReceive().isSuccess) { /* 消费并清空 Channel 队列 */ }
        NativeLibs.clearNativeBuffer()
        _uiUpdateVersion.value++
    }

    /**
     * 清空日志
     */
    fun clearLogs() {
        while (logInputChannel.tryReceive().isSuccess) { /* 消费并清空 Channel 队列 */ }
        NativeLibs.clearNativeBuffer()
        _uiUpdateVersion.value++
    }

    /**
     * 文件导出：利用 NIO FileChannel 将 Native 堆外内存一键落盘（零拷贝）
     */
    suspend fun exportFullLogToFile(targetFile: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val buffer = NativeLibs.getDirectBuffer() ?: return@withContext false
            val writeOffset = NativeLibs.getWriteOffset().toInt()
            if (writeOffset <= 0) return@withContext false

            FileOutputStream(targetFile).channel.use { channel ->
                val readSlice = buffer.duplicate().apply {
                    position(0)
                    limit(writeOffset.coerceAtMost(capacity()))
                }
                channel.write(readSlice)
            }
            true
        }.getOrDefault(false)
    }

    /**
     * 高效秒级时间戳缓存（在 Dispatchers.Default 单线程循环中调用，天然无并发竞争）
     */
    private fun getCachedTimeStamp(): String {
        val currentSecond = System.currentTimeMillis() / 1000
        if (currentSecond != lastSecondTimestamp) {
            lastSecondTimestamp = currentSecond
            cachedTimeString = LocalDateTime.now().format(timeFormatter)
        }
        return cachedTimeString
    }
}
