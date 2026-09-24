package libs.libs.libs.adb.usb.host

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import libs.libs.libs.adb.public.AdbPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public class AdbUsbHostConnection(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) {

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null

    /**
     * 打开并初始化 USB Host (OTG) 通道
     */
    public suspend fun open(timeoutMs: Int = 5000): Boolean = withContext(Dispatchers.IO) {
        close()

        // 1. 查找 ADB 专用接口 (Vendor Class 255, Subclass 66, Protocol 1)
        var targetInterface: UsbInterface? = null
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (isAdbInterface(iface)) {
                targetInterface = iface
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) {
                            inEp = ep
                        } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                            outEp = ep
                        }
                    }
                }
                if (inEp != null && outEp != null) break
            }
        }

        if (targetInterface == null || inEp == null || outEp == null) {
            return@withContext false
        }

        // 2. 打开设备连接并占用接口
        val conn = usbManager.openDevice(device) ?: return@withContext false
        if (!conn.claimInterface(targetInterface, true)) {
            conn.close()
            return@withContext false
        }

        this@AdbUsbHostConnection.connection = conn
        this@AdbUsbHostConnection.usbInterface = targetInterface
        this@AdbUsbHostConnection.inEndpoint = inEp
        this@AdbUsbHostConnection.outEndpoint = outEp

        true
    }

    /**
     * 从 USB 接口读取完整的 ADB 报文
     */
    public suspend fun readPacket(timeoutMs: Int = 10000): AdbPacket = withContext(Dispatchers.IO) {
        val conn = connection ?: throw IllegalStateException("USB Connection not opened")
        val epIn = inEndpoint ?: throw IllegalStateException("USB IN endpoint is null")

        // 1. 读取 24 字节 Header
        val headerBytes = ByteArray(AdbPacket.HEADER_SIZE)
        val readHeaderLen = bulkTransferExactly(conn, epIn, headerBytes, AdbPacket.HEADER_SIZE, timeoutMs)
        check(readHeaderLen == AdbPacket.HEADER_SIZE) { "Failed to read full ADB header over USB Host" }

        val header = AdbPacket.parseHeader(headerBytes)
        check(header.isValid) { "Invalid ADB packet header received over USB Host" }

        // 2. 读取 Payload
        val payload = if (header.dataLength > 0) {
            val buf = ByteArray(header.dataLength)
            val readDataLen = bulkTransferExactly(conn, epIn, buf, header.dataLength, timeoutMs)
            check(readDataLen == header.dataLength) { "Failed to read full ADB payload over USB Host" }
            buf
        } else {
            ByteArray(0)
        }

        AdbPacket(
            command = header.command,
            arg0 = header.arg0,
            arg1 = header.arg1,
            payload = payload
        )
    }

    /**
     * 向 USB 接口写入 ADB 报文（附带 ZLP 零长包防挂起处理）
     */
    public suspend fun writePacket(
        packet: AdbPacket,
        skipChecksum: Boolean = true,
        timeoutMs: Int = 5000
    ) = withContext(Dispatchers.IO) {
        val conn = connection ?: throw IllegalStateException("USB Connection not opened")
        val epOut = outEndpoint ?: throw IllegalStateException("USB OUT endpoint is null")

        val bytes = packet.toByteArray(skipChecksum = skipChecksum)

        // 分块写入完整字节数组
        var bytesWritten = 0
        while (bytesWritten < bytes.size) {
            val count = conn.bulkTransfer(
                epOut,
                bytes,
                bytesWritten,
                bytes.size - bytesWritten,
                timeoutMs
            )
            check(count >= 0) { "USB Bulk write error at offset $bytesWritten" }
            bytesWritten += count
        }

        // --- 关键硬件特性修复：发送 ZLP (Zero-Length Packet) ---
        // 如果发送的数据长度刚好是 端点 MaxPacketSize 的整数倍，必须发送一个长度为 0 的包打破端点接收等待
        val maxPacketSize = epOut.maxPacketSize
        if (maxPacketSize > 0 && bytes.size % maxPacketSize == 0) {
            conn.bulkTransfer(epOut, ByteArray(0), 0, 0, timeoutMs)
        }
    }

    /**
     * 0 内存分配的批量读取辅助方法，直接填入目标 offset
     */
    private fun bulkTransferExactly(
        conn: UsbDeviceConnection,
        ep: UsbEndpoint,
        buffer: ByteArray,
        length: Int,
        timeoutMs: Int
    ): Int {
        var bytesRead = 0
        while (bytesRead < length) {
            val count = conn.bulkTransfer(
                ep,
                buffer,
                bytesRead,
                length - bytesRead,
                timeoutMs
            )
            if (count < 0) break
            bytesRead += count
        }
        return bytesRead
    }

    public fun close() {
        try {
            usbInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (_: Exception) {
        } finally {
            connection = null
            usbInterface = null
            inEndpoint = null
            outEndpoint = null
        }
    }

    public val isOpen: Boolean get() = connection != null

    companion object {
        /**
         * 校验接口是否为标准的 ADB Vendor 接口 (Class 255, Subclass 66, Protocol 1)
         */
        public fun isAdbInterface(iface: UsbInterface): Boolean {
            return iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                    iface.interfaceSubclass == 66 &&
                    iface.interfaceProtocol == 1
        }
    }
}
