package libs.libs.libs.adb.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libs.libs.libs.adb.session.AdbConnection
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

public class AdbAbbService(public val connection: AdbConnection) {

    private val features: Set<String> = connection.features
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    public val hasAbbExec: Boolean = "abb_exec" in features
    public val hasAbb: Boolean = "abb" in features

    /**
     * 执行 ABB 命令（参数使用 \u0000 严格分割）
     */
    public suspend fun executeAbb(
        args: List<String>,
        inputStream: InputStream? = null
    ): String = withContext(Dispatchers.IO) {
        if (!hasAbbExec && !hasAbb) {
            return@withContext fallbackCmdExecute(args, inputStream)
        }

        val prefix = if (hasAbbExec) "abb_exec:" else "abb:"
        val destination = prefix + args.joinToString("\u0000") + "\u0000"

        val stream = connection.openStream(destination)
        val responseBuilder = StringBuilder()

        try {
            if (inputStream != null) {
                val buf = ByteArray(64 * 1024)
                var read: Int
                while (inputStream.read(buf).also { read = it } != -1) {
                    val chunk = if (read == buf.size) buf else buf.copyOf(read)
                    stream.write(chunk)
                }
            }

            while (true) {
                val bytes = stream.read() ?: break
                if (bytes.isNotEmpty()) {
                    responseBuilder.append(String(bytes, Charsets.UTF_8))
                }
            }
        } finally {
            stream.close()
        }

        responseBuilder.toString()
    }

    /**
     * 安装单包 .apk
     */
    public suspend fun installApp(apkFile: File): String {
        return apkFile.inputStream().use { input ->
            installAppStream(input, apkFile.length())
        }
    }

    /**
     * 安装 .apks (ZIP 格式拆分包)
     * 自动从压缩包中提取所有的 .apk 节点并使用同一 Session 极速写入
     */
    public suspend fun installApks(apksFile: File): String = withContext(Dispatchers.IO) {
        runCatching {
            ZipFile(apksFile).use { zip ->
                // 筛选出压缩包中所有的 .apk 文件 (如 splits/base-master.apk, splits/base-arm64.apk)
                val apkEntries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                    .toList()

                if (apkEntries.isEmpty()) {
                    return@withContext "安装失败: .apks 文件中未找到任何 .apk 拆分包"
                }

                val totalSize = apkEntries.sumOf { it.size }

                // 1. 创建包含总大小参数的 Install Session
                val createRes = executeAbb(listOf("package", "install-create", "-S", totalSize.toString()))
                val sessionId = parseSessionId(createRes)
                    ?: return@withContext "创建安装 Session 失败: $createRes"

                // 2. 依次解压并写入各个 split apk
                for (entry in apkEntries) {
                    val splitName = entry.name.substringAfterLast('/')
                    val entrySize = entry.size

                    val writeRes = zip.getInputStream(entry).use { entryStream ->
                        executeAbb(
                            args = listOf("package", "install-write", "-S", entrySize.toString(), sessionId, splitName, "-"),
                            inputStream = entryStream
                        )
                    }

                    if (!writeRes.contains("Success", ignoreCase = true)) {
                        executeAbb(listOf("package", "install-abandon", sessionId))
                        return@withContext "写入拆分包 [$splitName] 失败: $writeRes"
                    }
                }

                // 3. 统一提交 Session
                executeAbb(listOf("package", "install-commit", sessionId))
            }
        }.getOrElse { e -> "解析/安装 .apks 异常: ${e.message}" }
    }

    /**
     * 安装本地离散的多个拆分 APK 文件 (Multiple Split APKs)
     */
    public suspend fun installSplitApps(apkFiles: List<File>): String = withContext(Dispatchers.IO) {
        if (apkFiles.isEmpty()) return@withContext "错误: APK 文件列表为空"

        val totalSize = apkFiles.sumOf { it.length() }

        // 1. 创建 Session
        val createRes = executeAbb(listOf("package", "install-create", "-S", totalSize.toString()))
        val sessionId = parseSessionId(createRes)
            ?: return@withContext "创建安装 Session 失败: $createRes"

        // 2. 逐个写入
        for (file in apkFiles) {
            val writeRes = file.inputStream().use { input ->
                executeAbb(
                    args = listOf("package", "install-write", "-S", file.length().toString(), sessionId, file.name, "-"),
                    inputStream = input
                )
            }

            if (!writeRes.contains("Success", ignoreCase = true)) {
                executeAbb(listOf("package", "install-abandon", sessionId))
                return@withContext "写入拆分包 [${file.name}] 失败: $writeRes"
            }
        }

        // 3. 提交 Session
        executeAbb(listOf("package", "install-commit", sessionId))
    }

    private suspend fun installAppStream(apkStream: InputStream, apkSize: Long): String {
        val createRes = executeAbb(listOf("package", "install-create", "-S", apkSize.toString()))
        val sessionId = parseSessionId(createRes)
            ?: return "创建安装 Session 失败: $createRes"

        val writeRes = executeAbb(
            args = listOf("package", "install-write", "-S", apkSize.toString(), sessionId, "base.apk", "-"),
            inputStream = apkStream
        )

        if (!writeRes.contains("Success", ignoreCase = true)) {
            executeAbb(listOf("package", "install-abandon", sessionId))
            return "写入 APK 失败: $writeRes"
        }

        return executeAbb(listOf("package", "install-commit", sessionId))
    }

    private fun parseSessionId(response: String): String? {
        val regex = Regex("""\[(\d+)]""")
        return regex.find(response)?.groupValues?.get(1)
    }

    private suspend fun fallbackCmdExecute(args: List<String>, inputStream: InputStream?): String {
        val execService = AdbExecService(connection)
        val cmdStr = args.joinToString(" ")
        if (inputStream == null) {
            return execService.cmd(cmdStr)
        } else {
            val stream = connection.openStream("exec:cmd $cmdStr")
            val responseBuilder = StringBuilder()
            try {
                val buf = ByteArray(64 * 1024)
                var read: Int
                while (inputStream.read(buf).also { read = it } != -1) {
                    val chunk = if (read == buf.size) buf else buf.copyOf(read)
                    stream.write(chunk)
                }
                while (true) {
                    val bytes = stream.read() ?: break
                    if (bytes.isNotEmpty()) {
                        responseBuilder.append(String(bytes, Charsets.UTF_8))
                    }
                }
            } finally {
                stream.close()
            }
            return responseBuilder.toString()
        }
    }
}
