package libs.libs.libs.adb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import libs.libs.libs.adb.protocol.AdbCrypto
import libs.libs.libs.adb.services.AdbServices
import libs.libs.libs.adb.session.AdbConnection
import libs.libs.libs.adb.transport.AdbTransport
import libs.libs.libs.adb.transport.SocketTransport
import libs.libs.libs.adb.transport.UsbAdbDetector
import java.io.InputStream
import java.io.OutputStream

public class Adb(
    public val connection: AdbConnection,
    public val services: AdbServices = AdbServices(connection)
) {

    /**
     * 执行 Shell 命令，返回流式输出（适合日志监听、实时控制等长命令）
     */
    public suspend fun shell(command: String): Flow<String> {
        return services.shell.exec(command)
    }

    /**
     * 执行 Shell 命令，挂起直到执行结束并返回完整文本结果
     */
    public suspend fun shellExec(command: String): String {
        return services.shell.exec(command).toList().joinToString("")
    }

    /**
     * 上传文件到远程设备 (Push)
     */
    public suspend fun push(
        inputStream: InputStream,
        remotePath: String,
        mode: Int = 0x1A4
    ): Boolean {
        return services.sync.push(inputStream, remotePath, mode)
    }

    /**
     * 从远程设备下载文件 (Pull)
     */
    public suspend fun pull(
        remotePath: String,
        outputStream: OutputStream
    ): Boolean {
        return services.sync.pull(remotePath, outputStream)
    }

    /**
     * 断开连接并释放底层的协程作用域与 Socket/USB 管道
     */
    public fun disconnect() {
        connection.close()
    }

    public companion object {

        public var defaultCrypto: AdbCrypto? = null

        /**
         * 使用 Android Context 自动从内部存储 (filesDir/adbkey) 初始化持久化秘钥
         */
        public fun initCrypto(context: Context): AdbCrypto {
            val crypto = AdbCrypto.loadOrGenerate(context)
            defaultCrypto = crypto
            return crypto
        }

        /**
         * 带 Context 参数的无线 ADB 一键建连（自动处理密钥本地化）
         */
        public suspend fun connectSocket(
            context: Context,
            host: String,
            port: Int = 5555,
            timeoutMs: Int = 10000
        ): Adb {
            val crypto = initCrypto(context)
            return connectSocket(host, port, crypto, timeoutMs)
        }

        /**
         * 通过 USB OTG 接口快速建立 ADB 连接
         */
        public suspend fun connectUsb(
            manager: UsbManager,
            device: UsbDevice,
            crypto: AdbCrypto = defaultCrypto!!,
            timeoutMs: Int = 5000
        ): Adb {
            val transport = UsbAdbDetector.createTransport(manager, device, timeoutMs)
                ?: throw IllegalStateException("未在此 USB 设备上侦测到合法的 ADB 接口或无 USB 访问权限")
            val connection = AdbConnection(transport, crypto)
            connection.connect()
            return Adb(connection)
        }

        /**
         * 基于自定义 AdbTransport 接口建立连接
         */
        public suspend fun connectTransport(
            transport: AdbTransport,
            crypto: AdbCrypto = defaultCrypto!!
        ): Adb {
            val connection = AdbConnection(transport, crypto)
            connection.connect()
            return Adb(connection)
        }
    }
}
