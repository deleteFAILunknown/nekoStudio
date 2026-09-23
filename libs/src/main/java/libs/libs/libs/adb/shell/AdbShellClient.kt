package libs.libs.libs.adb.shell

import libs.libs.libs.adb.connect.AdbConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalSerializationApi::class)
public class AdbShellClient(
    // 标记为 @PublishedApi internal val，允许 public inline 函数访问
    @PublishedApi internal val connection: AdbConnection,
    @PublishedApi internal val protoBuf: ProtoBuf = ProtoBuf
) {

    /**
     * 以 Shell V2 协议 (`shell,v2,raw:`) 执行命令
     * 自动拆分 stdout, stderr 并精准捕获 exitCode
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
     * 执行 Shell V2 指令，并将 STDOUT 二进制流直接通过 Protobuf 反序列化为对象 [T]
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

    /**
     * Shell V2 实时流式输出 (按数据帧类型回调)
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

    /**
     * 兼容传统的 Shell V1 (Unframed Stream) 模式
     */
    public suspend fun execV1(command: String): String = withContext(Dispatchers.IO) {
        val stream = connection.openStream("exec:$command") ?: return@withContext ""
        val output = ByteArrayOutputStream()
        try {
            while (true) {
                val data = stream.read() ?: break
                if (data.isNotEmpty()) output.write(data)
            }
        } finally {
            stream.close()
        }
        output.toString(Charsets.UTF_8.name())
    }
}
