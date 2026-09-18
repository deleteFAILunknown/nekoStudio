package libs.libs.libs.adb.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

public class SocketTransport(
    public val host: String,
    public val port: Int = 5555,
    public val timeoutMs: Int = 10000
) : AdbTransport {
    public var socket: Socket? = null

    public suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        socket = Socket().apply {
            // 禁用 Nagle 算法，消除 ACK 延迟
            tcpNoDelay = true 
            // 显式将 TCP 读写缓冲区扩至 1MB
            sendBufferSize = 1024 * 1024     
            receiveBufferSize = 1024 * 1024  
            connect(InetSocketAddress(host, port), timeoutMs)
            soTimeout = timeoutMs
        }
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = withContext(Dispatchers.IO) {
        val input = socket?.getInputStream() ?: throw IllegalStateException("Socket 未连接")
        var totalRead = 0
        while (totalRead < length) {
            val bytes = input.read(buffer, offset + totalRead, length - totalRead)
            if (bytes == -1) break
            totalRead += bytes
        }
        totalRead
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int): Unit = withContext(Dispatchers.IO) {
        val output = socket?.getOutputStream() ?: throw IllegalStateException("Socket 未连接")
        output.write(buffer, offset, length)
        output.flush()
    }

    override suspend fun close(): Unit = withContext(Dispatchers.IO) {
        runCatching { socket?.close() }
        socket = null
    }
}
