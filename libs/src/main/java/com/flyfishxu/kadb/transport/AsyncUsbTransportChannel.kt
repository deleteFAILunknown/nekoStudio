package com.flyfishxu.kadb.transport

import android.os.Build
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 极限异步 USB 传输通道 (适配 Kadb Suspend ByteBuffer TransportChannel 规范)
 */
class AsyncUsbTransportChannel(
    private val connection: UsbDeviceConnection,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val bufferCapacity: Int = 1024 * 1024
) : TransportChannel {

    @Volatile
    private var closed = false

    override val isOpen: Boolean
        get() = !closed && connection.fileDescriptor != -1

    // USB 模拟虚拟 Socket 接口地址
    override val localAddress: InetSocketAddress = InetSocketAddress("127.0.0.1", 0)
    override val remoteAddress: InetSocketAddress = InetSocketAddress("127.0.0.1", 0)

    // Direct Physical Memory 物理硬件缓冲区
    private val inBufferA: ByteBuffer = ByteBuffer.allocateDirect(bufferCapacity)
    private val inBufferB: ByteBuffer = ByteBuffer.allocateDirect(bufferCapacity)

    private val reqA: UsbRequest = UsbRequest().apply { initialize(connection, epIn) }
    private val reqB: UsbRequest = UsbRequest().apply { initialize(connection, epIn) }

    private var activeBuf: ByteBuffer = inBufferA
    private var activeReq: UsbRequest = reqA
    private var shadowBuf: ByteBuffer = inBufferB
    private var shadowReq: UsbRequest = reqB

    private val readLock = ReentrantLock()
    private val writeLock = ReentrantLock()

    init {
        queueRequest(activeReq, activeBuf)
    }

    override suspend fun read(dst: ByteBuffer, timeout: Long, unit: TimeUnit): Int = withContext(Dispatchers.IO) {
        readLock.withLock {
            if (closed) throw IOException("AsyncUsbTransportChannel is closed")

            queueRequest(shadowReq, shadowBuf)

            waitForRequest(activeReq)
            val readBytes = activeBuf.position()

            var copied = 0
            if (readBytes > 0) {
                activeBuf.flip()
                copied = minOf(dst.remaining(), activeBuf.remaining())
                val oldLimit = activeBuf.limit()
                activeBuf.limit(activeBuf.position() + copied)
                dst.put(activeBuf)
                activeBuf.limit(oldLimit)
                activeBuf.clear()
            }

            swapBuffers()
            copied
        }
    }

    override suspend fun readExactly(dst: ByteBuffer, timeout: Long, unit: TimeUnit) {
        val timeoutMs = if (timeout > 0) unit.toMillis(timeout) else 0L
        val startTime = System.currentTimeMillis()

        while (dst.hasRemaining()) {
            val elapsed = System.currentTimeMillis() - startTime
            if (timeoutMs > 0 && elapsed >= timeoutMs) {
                throw IOException("USB read timed out")
            }
            val remainingTimeout = if (timeoutMs > 0) timeoutMs - elapsed else 0L
            val readBytes = read(dst, remainingTimeout, TimeUnit.MILLISECONDS)
            if (readBytes < 0) {
                throw IOException("EOF reached before target buffer was filled")
            }
        }
    }

    override suspend fun write(src: ByteBuffer, timeout: Long, unit: TimeUnit): Int = withContext(Dispatchers.IO) {
        writeLock.withLock {
            if (closed) throw IOException("AsyncUsbTransportChannel is closed")

            val remaining = src.remaining()
            if (remaining == 0) return@withContext 0

            val chunkLen = minOf(remaining, bufferCapacity)
            val tempBuf = ByteArray(chunkLen)
            src.get(tempBuf, 0, chunkLen)

            val timeoutMs = if (timeout > 0) unit.toMillis(timeout).toInt() else 0
            val transferred = connection.bulkTransfer(epOut, tempBuf, chunkLen, timeoutMs)

            if (transferred < 0) {
                throw IOException("USB Out bulk transfer failed: $transferred")
            }
            transferred
        }
    }

    override suspend fun writeExactly(src: ByteBuffer, timeout: Long, unit: TimeUnit) {
        val timeoutMs = if (timeout > 0) unit.toMillis(timeout) else 0L
        val startTime = System.currentTimeMillis()

        while (src.hasRemaining()) {
            val elapsed = System.currentTimeMillis() - startTime
            if (timeoutMs > 0 && elapsed >= timeoutMs) {
                throw IOException("USB write timed out")
            }
            val remainingTimeout = if (timeoutMs > 0) timeoutMs - elapsed else 0L
            write(src, remainingTimeout, TimeUnit.MILLISECONDS)
        }
    }

    override suspend fun shutdownInput() {
        runCatching {
            reqA.cancel()
            reqB.cancel()
        }
    }

    override suspend fun shutdownOutput() {
        // USB 端点不支持逻辑半关闭，保留空实现
    }

    private fun queueRequest(req: UsbRequest, buf: ByteBuffer) {
        buf.clear()
    
        // API 26 (Android 8.0) 及以上使用新的 queue(buf) 接口
        val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            req.queue(buf)
        } else {
            @Suppress("DEPRECATION")
            req.queue(buf, bufferCapacity)
        }

        if (!queued) {
            throw IOException("Failed to queue USB IN Request into kernel pipeline")
        }
    }

    private fun waitForRequest(targetReq: UsbRequest) {
        while (!closed) {
            val completed = connection.requestWait() ?: throw IOException("USB hardware IO interrupted or channel closed")
            if (completed.endpoint.endpointNumber == epIn.endpointNumber) {
                return
            }
        }
        throw IOException("USB Channel closed during wait")
    }

    private fun swapBuffers() {
        val tempBuf = activeBuf
        activeBuf = shadowBuf
        shadowBuf = tempBuf

        val tempReq = activeReq
        activeReq = shadowReq
        shadowReq = tempReq
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            reqA.cancel()
            reqB.cancel()
            reqA.close()
            reqB.close()
        }
    }
}
