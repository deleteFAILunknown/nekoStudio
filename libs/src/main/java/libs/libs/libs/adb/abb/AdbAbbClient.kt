package libs.libs.libs.adb.abb

import libs.libs.libs.adb.connect.AdbConnection
import libs.libs.libs.adb.shell.AdbShellClient
import libs.libs.libs.adb.shell.ShellCommandResult
import libs.libs.libs.adb.shell.ShellV2Buffer
import libs.libs.libs.adb.shell.ShellV2Packet
import libs.libs.libs.adb.sync.AdbSyncClientV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

public class AdbAbbClient(
    @PublishedApi internal val connection: AdbConnection
) {
    private val shellClient by lazy { AdbShellClient(connection) }
    private val syncClient by lazy { AdbSyncClientV2(connection) }

    /**
     * 判断设备是否支持 ABB 协议 (Android 11+)
     */
    public fun isAbbSupported(): Boolean = 
        connection.hasFeature("abb_exec") || connection.hasFeature("abb")

    private fun buildDestination(servicePrefix: String, args: List<String>): String {
        return buildString {
            append(servicePrefix)
            args.forEach { arg ->
                append(arg)
                append('\u0000')
            }
        }
    }

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
     * 安装单体 APK File (自动评估 ABB 流或 Sync+pm 降级)
     */
    public suspend fun installApk(
        apkFile: File,
        options: AbbInstallOptions = AbbInstallOptions(),
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ): Result<Unit> {
        require(apkFile.exists()) { "APK file non-existent: ${apkFile.absolutePath}" }
        
        return if (isAbbSupported()) {
            apkFile.inputStream().use { stream ->
                installApk(stream, apkFile.length(), options, onProgress)
            }
        } else {
            // 降级机制：不支持 ABB 时退回到 Sync Push + pm install
            runCatching {
                val tempPath = "/data/local/tmp/temp_${System.currentTimeMillis()}.apk"
                apkFile.inputStream().use { stream ->
                    syncClient.pushV2(stream, tempPath, apkFile.length(), onProgress = onProgress)
                }
                val result = shellClient.execV2("pm install ${options.toArgs().joinToString(" ")} $tempPath")
                shellClient.execV2("rm -f $tempPath")
                check(result.isSuccess && result.stdout.contains("Success")) {
                    "Install failed: ${result.stdout} ${result.stderr}"
                }
            }
        }
    }

    /**
     * 通过 ABB 极速流式安装单个 APK Stream
     */
    public suspend fun installApk(
        apkStream: InputStream,
        apkSize: Long,
        options: AbbInstallOptions = AbbInstallOptions(),
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val createArgs = mutableListOf("package", "install-create", "-S", apkSize.toString())
            createArgs.addAll(options.toArgs())

            var createResult = execAbb(createArgs)
            if (!createResult.isSuccess && options.bypassLowTargetSdkBlock && createResult.stderr.contains("Unknown option")) {
                val fallbackArgs = mutableListOf("package", "install-create", "-S", apkSize.toString())
                fallbackArgs.addAll(options.toArgs(includeBypassLowSdk = false))
                createResult = execAbb(fallbackArgs)
            }

            check(createResult.isSuccess) { "Failed to create install session: ${createResult.stderr}" }

            val sessionId = extractSessionId(createResult.stdout)
                ?: throw IllegalStateException("Failed to parse session ID from: ${createResult.stdout}")

            try {
                val writeDestination = buildDestination(
                    "abb_exec:",
                    listOf("package", "install-write", "-S", apkSize.toString(), sessionId, "base.apk", "-")
                )
                val writeStream = connection.openStream(writeDestination)
                    ?: throw IllegalStateException("Failed to open install-write stream")

                try {
                    apkStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
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

    /**
     * 安装 APKS 套件 (自动评估 ABB 零磁盘流或 Sync+pm 会话降级)
     */
    public suspend fun installApks(
        apksFile: File,
        options: AbbInstallOptions = AbbInstallOptions(),
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        require(apksFile.exists()) { "APKS file non-existent: ${apksFile.absolutePath}" }

        if (isAbbSupported()) {
            runCatching {
                ZipFile(apksFile).use { zip ->
                    val apkEntries = zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                        .toList()

                    check(apkEntries.isNotEmpty()) { "No .apk files found in ${apksFile.name}" }

                    val totalBytes = apkEntries.sumOf { it.size }
                    val createArgs = mutableListOf("package", "install-create", "-S", totalBytes.toString())
                    createArgs.addAll(options.toArgs())

                    var createResult = execAbb(createArgs)
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
        } else {
            // 降级机制：退回到 Legacy Sync + pm install-create/write/commit 会话
            runCatching {
                ZipFile(apksFile).use { zip ->
                    val apkEntries = zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                        .toList()

                    check(apkEntries.isNotEmpty()) { "No .apk files found in ${apksFile.name}" }

                    val totalBytes = apkEntries.sumOf { it.size }
                    val createResult = shellClient.execV2("pm install-create -S $totalBytes ${options.toArgs().joinToString(" ")}")
                    check(createResult.isSuccess) { "Failed to create install session: ${createResult.stderr}" }

                    val sessionId = extractSessionId(createResult.stdout)
                        ?: throw IllegalStateException("Failed to parse session ID from: ${createResult.stdout}")

                    var globalWritten = 0L

                    try {
                        apkEntries.forEachIndexed { index, entry ->
                            val splitName = entry.name.substringAfterLast('/')
                            val tempPath = "/data/local/tmp/temp_split_${index}_${System.currentTimeMillis()}.apk"

                            zip.getInputStream(entry).use { inputStream ->
                                syncClient.pushV2(
                                    inputStream = inputStream,
                                    remotePath = tempPath,
                                    totalSize = entry.size,
                                    onProgress = { read, _ ->
                                        onProgress?.invoke(globalWritten + read, totalBytes)
                                    }
                                )
                            }
                            globalWritten += entry.size

                            val writeResult = shellClient.execV2("pm install-write -S ${entry.size} $sessionId $splitName $tempPath")
                            shellClient.execV2("rm -f $tempPath")
                            check(writeResult.isSuccess) { "Failed to write split $splitName: ${writeResult.stderr}" }
                        }

                        val commitResult = shellClient.execV2("pm install-commit $sessionId")
                        check(commitResult.isSuccess && commitResult.stdout.contains("Success")) {
                            "Failed to commit session $sessionId: ${commitResult.stdout}${commitResult.stderr}"
                        }
                    } catch (e: Exception) {
                        shellClient.execV2("pm install-abandon $sessionId")
                        throw e
                    }
                }
            }
        }
    }

    /**
     * 流式安装 Split APKs 套件
     */
    public suspend fun installSplitApks(
        apks: Map<String, Pair<InputStream, Long>>,
        options: AbbInstallOptions = AbbInstallOptions(),
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
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

            var globalBytesWritten = 0L

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
                                    globalBytesWritten += read
                                    onProgress?.invoke(globalBytesWritten, totalSize)
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
