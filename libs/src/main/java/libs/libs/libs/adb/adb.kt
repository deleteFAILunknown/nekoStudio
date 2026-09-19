package libs.libs.libs.adb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import libs.libs.libs.adb.discovery.AdbMdnsDiscoverer
import libs.libs.libs.adb.pairing.AdbPairingClient
import libs.libs.libs.adb.protocol.AdbCrypto
import libs.libs.libs.adb.services.AdbServices
import libs.libs.libs.adb.session.AdbConnection
import libs.libs.libs.adb.transport.AdbTransport
import libs.libs.libs.adb.transport.SocketTransport
import libs.libs.libs.adb.transport.TlsTransport
import libs.libs.libs.adb.transport.UsbAdbDetector
import java.io.InputStream
import java.io.OutputStream

public class Adb(
    public val connection: AdbConnection,
    public val services: AdbServices = AdbServices(connection)
) {

    public suspend fun shell(command: String): Flow<String> {
        return services.shell.exec(command)
    }

    public suspend fun shellExec(command: String): String {
        return services.shell.exec(command).toList().joinToString("")
    }

    public suspend fun push(
        inputStream: InputStream,
        remotePath: String,
        mode: Int = 420
    ): Boolean {
        return services.sync.push(inputStream, remotePath, mode)
    }

    public suspend fun pull(
        remotePath: String,
        outputStream: OutputStream
    ): Boolean {
        return services.sync.pull(remotePath, outputStream)
    }

    public fun disconnect() {
        connection.close()
    }

    public companion object {

        public var defaultCrypto: AdbCrypto? = null

        public fun initCrypto(context: Context): AdbCrypto {
            val crypto = AdbCrypto.loadOrGenerate(context)
            defaultCrypto = crypto
            return crypto
        }

        public suspend fun connectSocket(
            context: Context,
            host: String,
            port: Int = 5555,
            timeoutMs: Int = 10000
        ): Adb {
            val crypto = initCrypto(context)
            val transport = SocketTransport(host, port, timeoutMs)
            transport.connect()
            val connection = AdbConnection(transport, crypto)
            connection.connect()
            return Adb(connection)
        }

        public suspend fun connectTlsSocket(
            context: Context,
            host: String,
            port: Int,
            timeoutMs: Int = 10000
        ): Adb {
            val crypto = initCrypto(context)
            val transport = TlsTransport(host, port, crypto, timeoutMs)
            transport.connect()
            val connection = AdbConnection(transport, crypto)
            connection.connect()
            return Adb(connection)
        }

        public suspend fun connectUsb(
            context: Context,
            manager: UsbManager,
            device: UsbDevice,
            timeoutMs: Int = 5000
        ): Adb {
            val crypto = initCrypto(context)
            val transport = UsbAdbDetector.createTransport(manager, device, timeoutMs)
                ?: throw IllegalStateException("未检测到合法 ADB USB 接口或权限不足")
            val connection = AdbConnection(transport, crypto)
            connection.connect()
            return Adb(connection)
        }

        public suspend fun connectTransport(
            transport: AdbTransport,
            crypto: AdbCrypto
        ): Adb {
            val connection = AdbConnection(transport, crypto)
            connection.connect()
            return Adb(connection)
        }

        public fun discoverDevices(context: Context): Flow<AdbMdnsDiscoverer.DiscoveredService> {
            return AdbMdnsDiscoverer(context).discoverServices()
        }

        public suspend fun pair(
            context: Context,
            host: String,
            port: Int,
            pairingCode: String
        ): Boolean {
            val crypto = initCrypto(context)
            val client = AdbPairingClient(host, port, crypto)
            return client.pair(pairingCode)
        }
    }
}
