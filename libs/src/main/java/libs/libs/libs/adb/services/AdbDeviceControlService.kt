package libs.libs.libs.adb.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libs.libs.libs.adb.session.AdbConnection

public class AdbDeviceControlService(public val connection: AdbConnection) {

    private val execService = AdbExecService(connection)
    private val shellService = AdbShellService(connection)

    /**
     * 重启设备
     * @param target reboot 目标: "" (正常重启), "bootloader", "recovery", "sideload", "download"
     */
    public suspend fun reboot(target: String = ""): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val destination = if (target.isEmpty()) "reboot:" else "reboot:$target"
            val stream = connection.openStream(destination)
            stream.close()
        }.isSuccess
    }

    /**
     * 重挂载 /system, /vendor 等分区为可读写 (Remount)
     */
    public suspend fun remount(): String = withContext(Dispatchers.IO) {
        runCatching {
            val stream = connection.openStream("remount:")
            val sb = StringBuilder()
            try {
                while (true) {
                    val bytes = stream.read() ?: break
                    if (bytes.isNotEmpty()) {
                        sb.append(String(bytes, Charsets.UTF_8))
                    }
                }
            } finally {
                stream.close()
            }
            sb.toString()
        }.getOrElse {
            // 降级使用 shell remount
            shellService.execWithResult("remount").stdout
        }
    }

    /**
     * 切换 adbd 运行在 Root 模式
     */
    public suspend fun root(): String = withContext(Dispatchers.IO) {
        runCatching {
            val stream = connection.openStream("root:")
            val sb = StringBuilder()
            try {
                while (true) {
                    val bytes = stream.read() ?: break
                    if (bytes.isNotEmpty()) {
                        sb.append(String(bytes, Charsets.UTF_8))
                    }
                }
            } finally {
                stream.close()
            }
            sb.toString()
        }.getOrDefault("Failed to switch to root")
    }

    /**
     * 恢复 adbd 为普通 Shell 用户模式 (Unroot)
     */
    public suspend fun unroot(): String = withContext(Dispatchers.IO) {
        runCatching {
            val stream = connection.openStream("unroot:")
            val sb = StringBuilder()
            try {
                while (true) {
                    val bytes = stream.read() ?: break
                    if (bytes.isNotEmpty()) {
                        sb.append(String(bytes, Charsets.UTF_8))
                    }
                }
            } finally {
                stream.close()
            }
            sb.toString()
        }.getOrDefault("Failed to unroot")
    }

    /**
     * 极速查询 System Property 属性
     */
    public suspend fun getProperty(key: String): String = withContext(Dispatchers.IO) {
        execService.cmd("getprop $key").trim()
    }
}
