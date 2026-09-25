package libs.libs.libs.adb.abb

import libs.libs.libs.adb.connect.AdbConnection
import libs.libs.libs.adb.shell.ShellCommandResult
import libs.libs.libs.adb.shell.ShellV2Buffer
import libs.libs.libs.adb.shell.ShellV2Packet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

public class AdbAbbClient(
    @PublishedApi internal val connection: AdbConnection
) {

    /**
     * 将参数列表编码为 ABB 服务的目标字符串
     * 格式: "abb:arg0\u0000arg1\u0000arg2\u0000"
     */
    private fun buildDestination(servicePrefix: String, args: List<String>): String {
        return buildString {
            append(servicePrefix)
            args.forEach { arg ->
                append(arg)
                append('\u0000')
            }
        }
    }

    /**
     * 执行带帧的 ABB 命令 (`abb:`)
     * 例如: execAbb(listOf("package", "list", "packages", "-3"))
     */
    public suspend fun execAbb(args: List<String>): ShellCommandResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val destination = buildDestination("abb:", args)
        val stream = connection.openStream(destination)
            ?: return@withContext ShellCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "Failed to open ABB stream",
                durationMs = 0L
            )

        val stdoutStream = ByteArrayOutputStream()
        val stderrStream = ByteArrayOutputStream()
        var exitCode = -1
        val v2Buffer = ShellV2Buffer()

        try {
            while (true) {
                val data = stream.read() ?: break
                if (data.isNotEmpty()) {
                    v2Buffer.append(data)
                    while (true) {
                        val packet = v2Buffer.pollPacket() ?: break
                        when (packet.id) {
                            ShellV2Packet.ID_STDOUT -> stdoutStream.write(packet.payload)
                            ShellV2Packet.ID_STDERR -> stderrStream.write(packet.payload)
                            ShellV2Packet.ID_EXIT -> {
                                if (packet.payload.isNotEmpty()) {
                                    exitCode = packet.payload[0].toInt() and 0xFF
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            stream.close()
        }

        ShellCommandResult(
            exitCode = exitCode,
            stdout = stdoutStream.toString(Charsets.UTF_8.name()),
            stderr = stderrStream.toString(Charsets.UTF_8.name()),
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * 通过 ABB 极速流式安装单个 APK (支持从本地文件或网络 InputStream 直接 Pipe 输入)
     */
    public suspend fun installApk(
        apkStream: InputStream,
        apkSize: Long,
        options: AbbInstallOptions = AbbInstallOptions(),
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 1. 创建 Session
            val createArgs = mutableListOf("package", "install-create", "-S", apkSize.toString())
            createArgs.addAll(options.toArgs())

            var createResult = execAbb(createArgs)
            
            // 兼容性降级处理：若因 Android 13- 不识别 --bypass-low-target-sdk-block 报错，自动剔除该 Flag 重试
            if (!createResult.isSuccess && options.bypassLowTargetSdkBlock && createResult.stderr.contains("Unknown option")) {
                val fallbackArgs = mutableListOf("package", "install-create", "-S", apkSize.toString())
                fallbackArgs.addAll(options.toArgs(includeBypassLowSdk = false))
                createResult = execAbb(fallbackArgs)
            }

            check(createResult.isSuccess) { "Failed to create install session: ${createResult.stderr}" }

            // 解析 Session ID
            val sessionId = extractSessionId(createResult.stdout)
                ?: throw IllegalStateException("Failed to parse session ID from: ${createResult.stdout}")

            try {
                // 2. 写入 APK 数据流 (使用无帧的 abb_exec: 以达到最大传输吞吐量)
                val writeDestination = buildDestination(
                    "abb_exec:",
                    listOf("package", "install-write", "-S", apkSize.toString(), sessionId, "base.apk", "-")
                )
                val writeStream = connection.openStream(writeDestination)
                    ?: throw IllegalStateException("Failed to open install-write stream")

                try {
                    apkStream.use { input ->
                        val buffer = ByteArray(64 * 1024) // 64KB 传输缓冲区
                        var bytesWritten = 0L
                        var read: Int

                        while (input.read(buffer).also { read = it } != -1) {
                            if (read > 0) {
                                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                                writeStream.write(chunk)
                                bytesWritten += read
                                onProgress?.invoke(bytesWritten, apkSize)
                            }
                        }
                    }
                } finally {
                    writeStream.close()
                }

                // 3. 提交 Session
                val commitResult = execAbb(listOf("package", "install-commit", sessionId))
                check(commitResult.isSuccess && commitResult.stdout.contains("Success")) {
                    "Failed to commit install session $sessionId: ${commitResult.stdout}${commitResult.stderr}"
                }
            } catch (e: Exception) {
                // 异常时清理 Session
                execAbb(listOf("package", "install-abandon", sessionId))
                throw e
            }
        }
    }

    /**
     * 直接传入 .apks 文件进行【随机流读取 + 零磁盘解压】极速安装
     */
    public suspend fun installApks(
        apksFile: File,
        options: AbbInstallOptions = AbbInstallOptions(),
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(apksFile.exists()) { "APKS file non-existent: ${apksFile.absolutePath}" }

            // 使用 ZipFile 随机读取索引表，不产生任何临时解压文件
            ZipFile(apksFile).use { zip ->
                val apkEntries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                    .toList()

                check(apkEntries.isNotEmpty()) { "No .apk files found in ${apksFile.name}" }

                val totalBytes = apkEntries.sumOf { it.size }

                // 1. 创建 Session
                val createArgs = mutableListOf("package", "install-create", "-S", totalBytes.toString())
                createArgs.addAll(options.toArgs())

                var createResult = execAbb(createArgs)
                
                // 兼容性降级处理
                if (!createResult.isSuccess && options.bypassLowTargetSdkBlock && createResult.stderr.contains("Unknown option")) {
                    val fallbackArgs = mutableListOf("package", "install-create", "-S", totalBytes.toString())
                    fallbackArgs.addAll(options.toArgs(includeBypassLowSdk = false))
                    createResult = execAbb(fallbackArgs)
                }

                check(createResult.isSuccess) { "Failed to create install session: ${createResult.stderr}" }

                val sessionId = extractSessionId(createResult.stdout)
                    ?: throw IllegalStateException("Failed to parse session ID from: ${createResult.stdout}")

                var globalBytesWritten = 0L

                try {
                    // 2. 遍历每个 split entry，Pipe 给 Target 设备
                    apkEntries.forEach { entry ->
                        val splitName = entry.name.substringAfterLast('/')
                        val entrySize = entry.size

                        val writeDestination = buildDestination(
                            "abb_exec:",
                            listOf("package", "install-write", "-S", entrySize.toString(), sessionId, splitName, "-")
                        )

                        val writeStream = connection.openStream(writeDestination)
                            ?: throw IllegalStateException("Failed to open install-write stream for $splitName")

                        try {
                            zip.getInputStream(entry).use { apkStream ->
                                val buffer = ByteArray(64 * 1024)
                                var read: Int

                                while (apkStream.read(buffer).also { read = it } != -1) {
                                    if (read > 0) {
                                        val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                                        writeStream.write(chunk)
                                        globalBytesWritten += read
                                        onProgress?.invoke(globalBytesWritten, totalBytes)
                                    }
                                }
                            }
                        } finally {
                            writeStream.close()
                        }
                    }

                    // 3. 提交 Session
                    val commitResult = execAbb(listOf("package", "install-commit", sessionId))
                    check(commitResult.isSuccess && commitResult.stdout.contains("Success")) {
                        "Failed to commit install session $sessionId: ${commitResult.stdout}${commitResult.stderr}"
                    }
                } catch (e: Exception) {
                    execAbb(listOf("package", "install-abandon", sessionId))
                    throw e
                }
            }
        }
    }

    /**
     * 流式安装 Split APKs / APKS 套件 (适用于内存/网络 Stream Map)
     */
    public suspend fun installSplitApks(
        apks: Map<String, Pair<InputStream, Long>>, // splitName -> (InputStream, Size)
        options: AbbInstallOptions = AbbInstallOptions()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val totalSize = apks.values.sumOf { it.second }
            val createArgs = mutableListOf("package", "install-create", "-S", totalSize.toString())
            createArgs.addAll(options.toArgs())

            var createResult = execAbb(createArgs)
            if (!createResult.isSuccess && options.bypassLowTargetSdkBlock && createResult.stderr.contains("Unknown option")) {
                val fallbackArgs = mutableListOf("package", "install-create", "-S", totalSize.toString())
                fallbackArgs.addAll(options.toArgs(includeBypassLowSdk = false))
                createResult = execAbb(fallbackArgs)
            }

            val sessionId = extractSessionId(createResult.stdout)
                ?: throw IllegalStateException("Failed to parse session ID")

            try {
                apks.forEach { (splitName, streamWithSize) ->
                    val (stream, size) = streamWithSize
                    val writeDestination = buildDestination(
                        "abb_exec:",
                        listOf("package", "install-write", "-S", size.toString(), sessionId, "$splitName.apk", "-")
                    )
                    val writeStream = connection.openStream(writeDestination)
                        ?: throw IllegalStateException("Failed to open stream for $splitName")

                    try {
                        stream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                if (read > 0) {
                                    val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                                    writeStream.write(chunk)
                                }
                            }
                        }
                    } finally {
                        writeStream.close()
                    }
                }

                val commitResult = execAbb(listOf("package", "install-commit", sessionId))
                check(commitResult.isSuccess && commitResult.stdout.contains("Success")) {
                    "Commit failed: ${commitResult.stdout}"
                }
            } catch (e: Exception) {
                execAbb(listOf("package", "install-abandon", sessionId))
                throw e
            }
        }
    }

    private fun extractSessionId(output: String): String? {
        val regex = Regex("""\[(\d+)]""")
        return regex.find(output)?.groupValues?.get(1)
    }
}
