package libs.libs.libs.adb.shell

import libs.libs.libs.adb.connect.AdbConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalSerializationApi::class)
public class AdbShellClient(
    @PublishedApi internal val connection: AdbConnection,
    @PublishedApi internal val protoBuf: ProtoBuf = ProtoBuf
) {

    /**
     * 检查当前连接设备是否原生支持 Shell V2 协议
     */
    public val supportsShellV2: Boolean get() = connection.hasFeature("shell_v2")

    // 场景一：通用 Shell 执行 (智能选择 V2，不支持则优雅降级为 V1)

    /**
     * 执行 Shell 命令（自动判断 V2/V1）
     * - 支持 Shell V2: 使用 `shell,v2,raw:` 精准分离 stdout/stderr 并获取真实 exitCode
     * - 不支持 Shell V2: 退回 `exec:` + 哨兵 Marker 提取 exitCode
     */
    public suspend fun exec(command: String): ShellCommandResult = withContext(Dispatchers.IO) {
        if (supportsShellV2) {
            execV2(command)
        } else {
            execV1(command)
        }
    }

    /**
     * 强制使用 Shell V1 协议执行命令 (`exec:`)
     * 通过追加 exit code 哨兵标记精准解析 exitCode，stdout 与 stderr 混合输出
     */
    public suspend fun execV1(command: String): ShellCommandResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val sentinel = "__ADB_EXIT_CODE_${System.currentTimeMillis()}__:"
        // 在 V1 命令后追加哨兵标记输出返回值
        val wrappedCommand = "($command); echo -n \"\n$sentinel$?\""

        val stream = connection.openStream("exec:$wrappedCommand")
            ?: return@withContext ShellCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "Failed to open exec (V1) stream",
                durationMs = 0L
            )

        val outputStream = ByteArrayOutputStream()
        try {
            while (true) {
                val data = stream.read() ?: break
                if (data.isNotEmpty()) {
                    outputStream.write(data)
                }
            }
        } finally {
            stream.close()
        }

        val rawOutput = outputStream.toString(Charsets.UTF_8.name())
        val sentinelIndex = rawOutput.lastIndexOf(sentinel)

        val (stdout, exitCode) = if (sentinelIndex != -1) {
            val cleanStdout = rawOutput.substring(0, sentinelIndex).trimEnd('\r', '\n')
            val exitCodeStr = rawOutput.substring(sentinelIndex + sentinel.length).trim()
            val code = exitCodeStr.toIntOrNull() ?: 0
            cleanStdout to code
        } else {
            rawOutput to 0
        }

        ShellCommandResult(
            exitCode = exitCode,
            stdout = stdout,
            stderr = "",
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * 显式执行 Shell V2 命令（若设备不支持则自动退回 V1）
     */
    public suspend fun execV2(command: String): ShellCommandResult = withContext(Dispatchers.IO) {
        if (!supportsShellV2) return@withContext execV1(command)

        val startTime = System.currentTimeMillis()
        val stream = connection.openStream("shell,v2,raw:$command")
            ?: return@withContext ShellCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "Failed to open shell_v2 stream",
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
     * 读取 Shell V1 命令返回的纯原始字节数组（无协议拆包开销，适合下载/截图等二进制流）
     */
    public suspend fun execRawBytes(command: String): ByteArray = withContext(Dispatchers.IO) {
        val stream = connection.openStream("exec:$command")
            ?: throw IllegalStateException("Failed to open stream for $command")

        val output = ByteArrayOutputStream()
        try {
            while (true) {
                val data = stream.read() ?: break
                if (data.isNotEmpty()) output.write(data)
            }
        } finally {
            stream.close()
        }
        output.toByteArray()
    }

    /**
     * 执行 Shell V2 并直接返回纯二进制 STDOUT (避免 UTF-8 编码转换损坏 Protobuf/图片等原始数据)
     */
    public suspend fun execV2RawBytes(command: String): ByteArray = withContext(Dispatchers.IO) {
        val stream = connection.openStream("shell,v2,raw:$command")
            ?: throw IllegalStateException("Failed to open shell_v2 stream")

        val stdoutStream = ByteArrayOutputStream()
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

        check(exitCode == 0) { "Shell V2 execution failed with exit code $exitCode" }
        stdoutStream.toByteArray()
    }

    // 场景二：系统 Proto Dump / 反序列化

    /**
     * 直接读取 STDOUT 二进制 Payload 并反序列化为 Kotlin 对象 [T]
     * 自动兼容 V1 与 V2，使用纯字节流读取，防止 UTF-8 转码破坏 Protobuf 结构
     */
    public suspend inline fun <reified T> execProto(command: String): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = if (this@AdbShellClient.supportsShellV2) {
                this@AdbShellClient.execV2RawBytes(command)
            } else {
                this@AdbShellClient.execRawBytes(command)
            }
            protoBuf.decodeFromByteArray<T>(bytes)
        }
    }

    @Deprecated("Renamed to execProto for standard naming", ReplaceWith("execProto<T>(command)"))
    public suspend inline fun <reified T> execV2Proto(command: String): Result<T> = execProto(command)

    // 场景三：双向 Proto IPC 交互

    /**
     * 将 [requestPayload] 序列化为字节写入 Shell STDIN，并将返回结果解码为 [R]
     */
    public suspend inline fun <reified Req, reified Resp> execProtoWithInput(
        command: String,
        requestPayload: Req
    ): Result<Resp> = withContext(Dispatchers.IO) {
        runCatching {
            val inputBytes = protoBuf.encodeToByteArray(requestPayload)

            val outputBytes = if (this@AdbShellClient.supportsShellV2) {
                val stream = connection.openStream("shell,v2,raw:$command")
                    ?: throw IllegalStateException("Failed to open shell_v2 stream")

                val stdinFrame = ShellV2Packet.createFrame(ShellV2Packet.ID_STDIN, inputBytes)
                val closeStdinFrame = ShellV2Packet.createFrame(ShellV2Packet.ID_CLOSE_STDIN)

                val stdoutStream = ByteArrayOutputStream()
                val v2Buffer = ShellV2Buffer()
                var exitCode = 0

                try {
                    stream.write(stdinFrame)
                    stream.write(closeStdinFrame)

                    while (true) {
                        val data = stream.read() ?: break
                        if (data.isNotEmpty()) {
                            v2Buffer.append(data)
                            while (true) {
                                val packet = v2Buffer.pollPacket() ?: break
                                when (packet.id) {
                                    ShellV2Packet.ID_STDOUT -> stdoutStream.write(packet.payload)
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

                check(exitCode == 0) { "Command execution failed with exit code $exitCode" }
                stdoutStream.toByteArray()
            } else {
                // V1 降级传输
                val stream = connection.openStream("exec:$command")
                    ?: throw IllegalStateException("Failed to open exec stream")

                val stdoutStream = ByteArrayOutputStream()
                try {
                    stream.write(inputBytes)
                    while (true) {
                        val data = stream.read() ?: break
                        if (data.isNotEmpty()) stdoutStream.write(data)
                    }
                } finally {
                    stream.close()
                }
                stdoutStream.toByteArray()
            }

            protoBuf.decodeFromByteArray<Resp>(outputBytes)
        }
    }

    // 场景四：结构化流式传输 (Flow)

    /**
     * 流式监听 Shell 输出，按 [ShellStreamChunk] 结构打包发出
     * 自动支持 V1 与 V2：V2 下可区分 STDOUT/STDERR，V1 下按 STDOUT 发送 Raw Stream
     */
    public fun execStream(command: String): Flow<ShellStreamChunk> = flow {
        if (supportsShellV2) {
            val stream = connection.openStream("shell,v2,raw:$command")
            if (stream == null) {
                emit(ShellStreamChunk(ShellStreamType.ERROR, "Failed to open shell_v2 stream".toByteArray()))
                return@flow
            }

            val v2Buffer = ShellV2Buffer()
            try {
                while (true) {
                    val data = stream.read() ?: break
                    if (data.isNotEmpty()) {
                        v2Buffer.append(data)
                        while (true) {
                            val packet = v2Buffer.pollPacket() ?: break
                            when (packet.id) {
                                ShellV2Packet.ID_STDOUT -> emit(ShellStreamChunk(ShellStreamType.STDOUT, packet.payload))
                                ShellV2Packet.ID_STDERR -> emit(ShellStreamChunk(ShellStreamType.STDERR, packet.payload))
                                ShellV2Packet.ID_EXIT -> return@flow
                            }
                        }
                    }
                }
            } finally {
                stream.close()
            }
        } else {
            // V1 流降级
            val stream = connection.openStream("exec:$command")
            if (stream == null) {
                emit(ShellStreamChunk(ShellStreamType.ERROR, "Failed to open exec stream".toByteArray()))
                return@flow
            }

            try {
                while (true) {
                    val data = stream.read() ?: break
                    if (data.isNotEmpty()) {
                        emit(ShellStreamChunk(ShellStreamType.STDOUT, data))
                    }
                }
            } finally {
                stream.close()
            }
        }
    }.flowOn(Dispatchers.IO)

    public fun execV2Stream(command: String): Flow<ShellStreamChunk> = execStream(command)
}
