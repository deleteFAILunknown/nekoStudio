package libs.libs.libs.adb.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import libs.libs.libs.adb.session.AdbConnection
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

public class AdbShellResult(
    public val stdout: String,
    public val stderr: String,
    public val exitCode: Int
)

public class AdbShellService(public val connection: AdbConnection) {

    /**
     * 基础响应式流（普通 shell 协议，增量解码防止 UTF-8 中文截断乱码）
     */
    public fun exec(command: String): Flow<String> = flow {
        val stream = connection.openStream("shell:$command")
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)

        try {
            while (true) {
                val bytes = stream.read() ?: break
                if (bytes.isNotEmpty()) {
                    val inBuffer = ByteBuffer.wrap(bytes)
                    val outBuffer = CharBuffer.allocate(bytes.size * 2)

                    decoder.decode(inBuffer, outBuffer, false)
                    outBuffer.flip()

                    if (outBuffer.hasRemaining()) {
                        emit(outBuffer.toString())
                    }
                }
            }

            val finalBuffer = CharBuffer.allocate(32)
            decoder.decode(ByteBuffer.allocate(0), finalBuffer, true)
            decoder.flush(finalBuffer)
            finalBuffer.flip()
            if (finalBuffer.hasRemaining()) {
                emit(finalBuffer.toString())
            }
        } finally {
            stream.close()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 全版本兼容带结果返回：
     * 优先使用 shell,v2；若设备不支持或解析失败则自动降级到普通 shell 并提取 exitCode
     */
    public suspend fun execWithResult(command: String): AdbShellResult = withContext(Dispatchers.IO) {
        // 1. 优先尝试 shell,v2 协议 (Android 7.0+)
        val v2Result = runCatching { execV2(command) }.getOrNull()
        if (v2Result != null) {
            return@withContext v2Result
        }

        // 2. 降级使用普通 shell 协议 (Android 6.0 及以下)
        execV1WithExitCode(command)
    }

    /**
     * Shell V2 协议严格解析器（包含 5 字节 Header 拆包/粘包状态机）
     */
    private suspend fun execV2(command: String): AdbShellResult? {
        val stream = connection.openStream("shell,v2,raw:$command")
        val stdoutStream = ByteArrayOutputStream()
        val stderrStream = ByteArrayOutputStream()
        var exitCode: Int? = null
        var hasValidV2Frame = false

        // 拆包/粘包环形缓冲区
        var ringBuffer = ByteArray(0)

        try {
            while (true) {
                val chunk = stream.read() ?: break
                if (chunk.isEmpty()) continue

                ringBuffer += chunk
                var offset = 0

                // V2 帧头 5 字节: ID(1B) + Length(4B Little-Endian)
                while (ringBuffer.size - offset >= 5) {
                    val id = ringBuffer[offset].toInt() and 0xFF
                    val length = (ringBuffer[offset + 1].toInt() and 0xFF) or
                            ((ringBuffer[offset + 2].toInt() and 0xFF) shl 8) or
                            ((ringBuffer[offset + 3].toInt() and 0xFF) shl 16) or
                            ((ringBuffer[offset + 4].toInt() and 0xFF) shl 24)

                    // 合法 ID 范围: 1=STDOUT, 2=STDERR, 3=EXIT_CODE
                    if (id !in 1..3) {
                        return null // 非法 ID，说明对端不支持 Shell V2
                    }

                    // 检查缓冲区数据是否足够一帧 payload
                    if (ringBuffer.size - offset - 5 < length) {
                        break // 数据不足一帧，等待下一包 Socket 输入
                    }

                    hasValidV2Frame = true
                    val payloadOffset = offset + 5

                    when (id) {
                        1 -> stdoutStream.write(ringBuffer, payloadOffset, length)
                        2 -> stderrStream.write(ringBuffer, payloadOffset, length)
                        3 -> if (length > 0) exitCode = ringBuffer[payloadOffset].toInt() and 0xFF
                    }

                    offset += 5 + length
                }

                // 游标裁剪已消费的数据
                if (offset > 0) {
                    ringBuffer = ringBuffer.copyOfRange(offset, ringBuffer.size)
                }
            }
        } finally {
            stream.close()
        }

        if (!hasValidV2Frame) return null

        return AdbShellResult(
            stdout = stdoutStream.toString(StandardCharsets.UTF_8.name()),
            stderr = stderrStream.toString(StandardCharsets.UTF_8.name()),
            exitCode = exitCode ?: 0
        )
    }

    /**
     * 普通 shell 协议降级（自动处理 PTY \r\n 并通过 echo 提取 exitCode）
     */
    private suspend fun execV1WithExitCode(command: String): AdbShellResult {
        val exitMarker = "__ADB_EXIT__:"
        val wrappedCommand = "$command; echo \"\n$exitMarker\$?\""

        val stream = connection.openStream("shell:$wrappedCommand")
        val outputStream = ByteArrayOutputStream()

        try {
            while (true) {
                val bytes = stream.read() ?: break
                if (bytes.isNotEmpty()) {
                    outputStream.write(bytes)
                }
            }
        } finally {
            stream.close()
        }

        // PTY 会将 \n 转为 \r\n，统一替换回标准的 \n
        val fullOutput = outputStream.toString(StandardCharsets.UTF_8.name())
            .replace("\r\n", "\n")

        val markerIndex = fullOutput.lastIndexOf(exitMarker)

        return if (markerIndex != -1) {
            val stdout = fullOutput.substring(0, markerIndex).trimEnd('\n')
            val codeStr = fullOutput.substring(markerIndex + exitMarker.length).trim()
            val exitCode = codeStr.toIntOrNull() ?: 0
            AdbShellResult(stdout = stdout, stderr = "", exitCode = exitCode)
        } else {
            AdbShellResult(stdout = fullOutput, stderr = "", exitCode = 0)
        }
    }
}
