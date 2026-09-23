package libs.libs.libs.adb.root

import libs.libs.libs.adb.connect.AdbConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public class AdbRootClient(private val connection: AdbConnection) {

    /**
     * 向目标 adbd 发送 `root:` 服务请求
     * 指示守护进程重启并尝试以 root 身份运行
     * 
     * @return adbd 返回的响应文本（例如："restarting adbd as root\n" 或 "cannot run as root in production builds\n"）
     */
    public suspend fun requestRoot(): String = withContext(Dispatchers.IO) {
        sendServiceCommand("root:")
    }

    /**
     * 向目标 adbd 发送 `unroot:` 服务请求
     * 指示守护进程重启并恢复为普通 shell 身份运行
     */
    public suspend fun requestUnroot(): String = withContext(Dispatchers.IO) {
        sendServiceCommand("unroot:")
    }

    private suspend fun sendServiceCommand(serviceCommand: String): String {
        // 1. 发送 CMD_OPEN 报文打开 "root:" 或 "unroot:" 服务通道
        val stream = connection.openStream(serviceCommand) 
            ?: return "Failed to open $serviceCommand stream"

        val responseBuilder = StringBuilder()

        try {
            // 2. 读取 adbd 返回的状态反馈（例如："restarting adbd as root"）
            while (true) {
                val data = stream.read() ?: break
                if (data.isEmpty()) continue
                responseBuilder.append(String(data, Charsets.UTF_8))
            }
        } catch (e: Exception) {
            responseBuilder.append("\n[Stream closed: ${e.message}]")
        } finally {
            stream.close()
        }

        return responseBuilder.toString().trim()
    }
}
