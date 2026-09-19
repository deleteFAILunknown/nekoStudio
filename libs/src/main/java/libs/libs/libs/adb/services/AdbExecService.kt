package libs.libs.libs.adb.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import libs.libs.libs.adb.session.AdbConnection
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

public class AdbExecService(public val connection: AdbConnection) {

    private val features: Set<String> = connection.features
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    public val hasCmd: Boolean = "cmd" in features

    /**
     * 以 ByteArray 块的形式响应式流式获取 exec 原始输出（适用于大文件/数据流，如 screencap -p）
     * 100% 二进制安全，无 \r\n 转换
     */
    public fun execStream(command: String): Flow<ByteArray> = flow {
        val stream = connection.openStream("exec:$command")
        try {
            while (true) {
                val bytes = stream.read() ?: break
                if (bytes.isNotEmpty()) {
                    emit(bytes)
                }
            }
        } finally {
            stream.close()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 将 exec 输出直接写入 OutputStream（例如无损保存截图 screencap -p 到本地文件）
     */
    public suspend fun execToStream(command: String, outputStream: OutputStream): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val stream = connection.openStream("exec:$command")
                try {
                    while (true) {
                        val bytes = stream.read() ?: break
                        if (bytes.isNotEmpty()) {
                            outputStream.write(bytes)
                        }
                    }
                    outputStream.flush()
                } finally {
                    stream.close()
                }
            }.isSuccess
        }

    /**
     * 从 InputStream 读取二进制数据流写入远程进程 stdin（例如通过 exec:管道导入数据）
     */
    public suspend fun execFromStream(command: String, inputStream: InputStream): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val stream = connection.openStream("exec:$command")
                try {
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        stream.write(buffer, 0, bytesRead)
                    }
                    stream.flush()
                } finally {
                    stream.close()
                }
            }.isSuccess
        }

    /**
     * 无损获取文本输出（无 PTY \r\n 干扰，且支持完整 UTF-8 多字节字符还原）
     */
    public suspend fun execString(command: String): String = withContext(Dispatchers.IO) {
        val stream = connection.openStream("exec:$command")
        val baos = ByteArrayOutputStream()
        try {
            while (true) {
                val bytes = stream.read() ?: break
                if (bytes.isNotEmpty()) {
                    baos.write(bytes)
                }
            }
        } finally {
            stream.close()
        }
        baos.toString(StandardCharsets.UTF_8.name())
    }

    /**
     * 执行 Android cmd 服务命令 (如 "package list packages")
     * 自动判断特征：支持 `cmd` 时建立 `cmd:` 直连通道，否则自动降级为 `exec:cmd ...`
     * 已修复多字节 UTF-8 截断导致的乱码问题
     */
    public suspend fun cmd(command: String): String = withContext(Dispatchers.IO) {
        val destination = if (hasCmd) "cmd:$command" else "exec:cmd $command"
        val stream = connection.openStream(destination)
        val baos = ByteArrayOutputStream()
        try {
            while (true) {
                val bytes = stream.read() ?: break
                if (bytes.isNotEmpty()) {
                    baos.write(bytes)
                }
            }
        } finally {
            stream.close()
        }
        baos.toString(StandardCharsets.UTF_8.name())
    }
}
