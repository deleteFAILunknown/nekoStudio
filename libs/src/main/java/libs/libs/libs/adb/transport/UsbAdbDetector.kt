package libs.libs.libs.adb.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

public object UsbAdbDetector {
    public const val ADB_CLASS: Int = 255
    public const val ADB_SUBCLASS: Int = 66
    public const val ADB_PROTOCOL: Int = 1

    public fun findAdbInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == ADB_CLASS &&
                intf.interfaceSubclass == ADB_SUBCLASS &&
                intf.interfaceProtocol == ADB_PROTOCOL) {
                return intf
            }
        }
        return null
    }

    public fun createTransport(
        manager: UsbManager,
        device: UsbDevice,
        timeoutMs: Int = 5000
    ): UsbTransport? {
        val intf = findAdbInterface(device) ?: return null
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null

        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    inEp = ep
                } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                    outEp = ep
                }
            }
        }

        if (inEp == null || outEp == null) return null

        val connection = manager.openDevice(device) ?: return null
        if (!connection.claimInterface(intf, true)) {
            connection.close()
            return null
        }

        return UsbTransport(connection, inEp, outEp, timeoutMs)
    }
}
