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

    // 场景一：标准 Shell 命令 (无序列化开销，极致轻量)
    // 适用于: getprop, pm list, cat, rm 等常规 CLI 指令

    /**
     * 执行标准 Shell V2 命令，精准分离 stdout、stderr 并获取 exitCode
     */
    public suspend fun execV2(command: String): ShellCommandResult = withContext(Dispatchers.IO) {
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
     * 读取命令返回的原始字节数组（用于文件传输、截图等二进制数据）
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

    // 场景二：系统 Proto Dump / 自定义 Native 进程输出 (单向 Protobuf 解码)
    // 适用于: dumpsys window --proto, dumpsys activity --proto 或自定义 C++/Rust Daemon

    /**
     * 直接读取 STDOUT 二进制 Payload 并反序列化为 Kotlin 对象 [T]
     * 跳过 String 中转，解析速度快、内存占用极低
     */
    public suspend inline fun <reified T> execV2Proto(command: String): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val stream = connection.openStream("shell,v2,raw:$command")
                ?: throw IllegalStateException("Failed to open shell_v2 stream")

            val stdoutStream = ByteArrayOutputStream()
            val v2Buffer = ShellV2Buffer()
            var exitCode = 0

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

            check(exitCode == 0) { "Shell V2 command failed with exit code: $exitCode" }
            protoBuf.decodeFromByteArray<T>(stdoutStream.toByteArray())
        }
    }

    // 场景三：双向 IPC 交互 (向 STDIN 写入 Proto，从 STDOUT 接收 Proto)
    // 适用于: 运行设备端 Helper 进程（如 App 注入/内存监控 Daemon）

    /**
     * 将 [requestPayload] 序列化为字节写入 Shell STDIN，并将返回结果解码为 [R]
     */
    public suspend inline fun <reified Req, reified Resp> execProtoWithInput(
        command: String,
        requestPayload: Req
    ): Result<Resp> = withContext(Dispatchers.IO) {
        runCatching {
            val stream = connection.openStream("shell,v2,raw:$command")
                ?: throw IllegalStateException("Failed to open shell_v2 stream")

            val inputBytes = protoBuf.encodeToByteArray(requestPayload)
            val stdinFrame = ShellV2Packet.createFrame(ShellV2Packet.ID_STDIN, inputBytes)
            val closeStdinFrame = ShellV2Packet.createFrame(ShellV2Packet.ID_CLOSE_STDIN)

            val stdoutStream = ByteArrayOutputStream()
            val v2Buffer = ShellV2Buffer()
            var exitCode = 0

            try {
                // 1. 发送输入数据包并关闭 stdin
                stream.write(stdinFrame)
                stream.write(closeStdinFrame)

                // 2. 读取响应
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
            protoBuf.decodeFromByteArray<Resp>(stdoutStream.toByteArray())
        }
    }

    // 场景四：结构化流式传输 (将流块封装为 Proto 消息发往上层 UI/服务)
    // 适用于: 实时日志、硬件采样流、多模块通讯

    /**
     * 流式监听 Shell 输出，并将数据按 [ShellStreamChunk] 结构打包（含类型标签与时间戳）
     */
    public fun execV2Stream(command: String): Flow<ShellStreamChunk> = flow {
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
    }.flowOn(Dispatchers.IO)
}
