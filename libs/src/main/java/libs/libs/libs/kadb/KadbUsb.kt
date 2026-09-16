@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package libs.libs.libs.kadb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import com.flyfishxu.kadb.KadbOptions
import com.flyfishxu.kadb.cert.AndroidPubkey
import com.flyfishxu.kadb.cert.HostKeySet
import com.flyfishxu.kadb.cert.platform.defaultDeviceName
import com.flyfishxu.kadb.core.AdbConnection
import com.flyfishxu.kadb.core.AdbMessage
import com.flyfishxu.kadb.core.AdbProtocol
import com.flyfishxu.kadb.core.AdbReader
import com.flyfishxu.kadb.core.AdbWriter
import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.security.interfaces.RSAPublicKey

class AsyncDirectUsbSource(
    private val connection: UsbDeviceConnection,
    private val epIn: UsbEndpoint,
    private val queueDepth: Int = 4,
    private val packetSize: Int = 64 * 1024
) : Source {

    private val requests = Array(queueDepth) { UsbRequest() }
    private val buffers = Array(queueDepth) { ByteBuffer.allocateDirect(packetSize) }
    private val reqToBufferMap = HashMap<UsbRequest, ByteBuffer>()
    private val internalBuffer = Buffer()
    private var isQueueStarted = false

    private fun startAsyncPipeline() {
        if (isQueueStarted) return
        for (i in 0 until queueDepth) {
            requests[i].initialize(connection, epIn)
            buffers[i].clear()
            reqToBufferMap[requests[i]] = buffers[i]
            requests[i].queue(buffers[i])
        }
        isQueueStarted = true
    }

    override fun read(sink: Buffer, byteCount: Long): Long {
        startAsyncPipeline()

        if (internalBuffer.size > 0) {
            val toRead = minOf(byteCount, internalBuffer.size)
            sink.write(internalBuffer, toRead)
            return toRead
        }

        val completedReq = connection.requestWait() 
            ?: throw IOException("USB 物理链路断开或 DMA 传输异常")

        val directBuf = reqToBufferMap[completedReq] 
            ?: throw IOException("接收到未知的 UsbRequest")

        directBuf.flip()
        val bytesTransferred = directBuf.remaining()

        if (bytesTransferred > 0) {
            internalBuffer.write(directBuf)
        }

        directBuf.clear()
        completedReq.queue(directBuf)

        if (bytesTransferred == 0) return 0L

        val toRead = minOf(byteCount, internalBuffer.size)
        sink.write(internalBuffer, toRead)
        return toRead
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        for (req in requests) {
            try { req.close() } catch (_: Throwable) {}
        }
    }
}

class AsyncDirectUsbSink(
    private val connection: UsbDeviceConnection,
    private val epOut: UsbEndpoint,
    private val queueDepth: Int = 2,
    private val packetSize: Int = 64 * 1024
) : Sink {

    private val requests = Array(queueDepth) { UsbRequest() }
    private val buffers = Array(queueDepth) { ByteBuffer.allocateDirect(packetSize) }
    private var activeRequests = 0

    init {
        for (i in 0 until queueDepth) {
            requests[i].initialize(connection, epOut)
        }
    }

    override fun write(source: Buffer, byteCount: Long) {
        var remaining = byteCount

        while (remaining > 0) {
            val bufIndex = activeRequests % queueDepth
            val buf = buffers[bufIndex]
            val req = requests[bufIndex]

            if (activeRequests >= queueDepth) {
                connection.requestWait() ?: throw IOException("USB 物理写入挂起失败")
                activeRequests--
            }

            buf.clear()
            val toWrite = minOf(remaining, buf.capacity().toLong()).toInt()

            val tempBytes = source.readByteArray(toWrite.toLong())
            buf.put(tempBytes)
            buf.flip()

            req.queue(buf)
            activeRequests++
            remaining -= toWrite
        }
    }

    override fun flush() {
        while (activeRequests > 0) {
            connection.requestWait() ?: throw IOException("USB Flush 异常")
            activeRequests--
        }
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        try { flush() } catch (_: Throwable) {}
        for (req in requests) {
            try { req.close() } catch (_: Throwable) {}
        }
    }
}

suspend fun AdbConnection.Companion.connectUsbMax(
    connection: UsbDeviceConnection,
    epIn: UsbEndpoint,
    epOut: UsbEndpoint,
    hostKeySet: HostKeySet,
    options: KadbOptions = KadbOptions()
): AdbConnection {
    val usbSource = AsyncDirectUsbSource(connection, epIn)
    val usbSink = AsyncDirectUsbSink(connection, epOut)

    val reader = AdbReader(usbSource)
    val writer = AdbWriter(usbSink)
    var authKeyIndex = 0

    try {
        val advertisedFeatures = AdbProtocol.connectFeatures(options.delayedAckMode).toSet()
        val connectPayload = AdbProtocol.connectPayload(advertisedFeatures.toList())
        writer.writeConnect(connectPayload)

        var message: AdbMessage = reader.readMessage()

        while (true) {
            when (message.command) {
                AdbProtocol.CMD_AUTH -> {
                    check(message.arg0 == AdbProtocol.AUTH_TYPE_TOKEN) { "Unsupported auth type: $message" }
                    val authKey = hostKeySet.keyPairs.getOrNull(authKeyIndex)
                    if (authKey != null) {
                        authKeyIndex += 1
                        writer.writeAuth(AdbProtocol.AUTH_TYPE_SIGNATURE, authKey.signPayload(message))
                    } else {
                        val pubKeyBytes = AndroidPubkey.encodeWithName(
                            hostKeySet.defaultKeyPair.publicKey as RSAPublicKey,
                            defaultDeviceName()
                        )
                        writer.writeAuth(AdbProtocol.AUTH_TYPE_RSA_PUBLIC, pubKeyBytes)
                    }
                    message = reader.readMessage()
                }
                AdbProtocol.CMD_CNXN -> break
                else -> throw IOException("USB 连接握手失败: $message")
            }
        }

        val featuresStr = String(message.payload)
        val features = if (featuresStr.contains("features=")) {
            featuresStr.substringAfter("features=")
                .substringBefore(";")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        } else emptySet()

        val negotiatedFeatures = features.intersect(advertisedFeatures)
        val version = minOf(message.arg0, AdbProtocol.A_VERSION)
        writer.updateProtocolVersion(version)

        val peerMaxPayloadSize = message.arg1
        val negotiatedMaxPayloadSize = minOf(peerMaxPayloadSize, AdbProtocol.CONNECT_MAXDATA)
            .coerceAtLeast(1)
            .coerceAtMost(AdbProtocol.CONNECT_MAXDATA)

        reader.setInboundMaxPayloadSize(negotiatedMaxPayloadSize)

        return AdbConnection(
            adbReader = reader,
            adbWriter = writer,
            closeable = Closeable {
                try { reader.close() } catch (_: Throwable) {}
                try { writer.close() } catch (_: Throwable) {}
            },
            supportedFeatures = negotiatedFeatures,
            version = version,
            outboundMaxPayloadSize = negotiatedMaxPayloadSize
        )
    } catch (t: Throwable) {
        try { reader.close() } catch (_: Throwable) {}
        try { writer.close() } catch (_: Throwable) {}
        throw t
    }
}
