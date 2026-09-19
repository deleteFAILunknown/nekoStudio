package libs.libs.libs.adb.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libs.libs.libs.adb.session.AdbConnection
import libs.libs.libs.adb.sync.AdbFileEntry
import libs.libs.libs.adb.sync.AdbSyncClient
import java.io.File
import java.io.InputStream
import java.io.OutputStream

public class AdbSyncService(public val connection: AdbConnection) {

    /**
     * 推送本地 File 到设备端 (支持单个文件或递归文件夹)
     */
    public suspend fun push(
        localFile: File,
        remotePath: String,
        mode: Int = 33188,
        progress: ((transferred: Long, total: Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            AdbSyncClient.open(connection).use { client ->
                if (localFile.isDirectory) {
                    pushDirectoryInternal(client, localFile, remotePath, progress)
                } else {
                    client.push(localFile, remotePath, mode, progress)
                }
            }
        }.isSuccess
    }

    /**
     * 【直连无临时文件】从 InputStream 推送数据流到远程文件 (适合 ContentResolver/Uri 读取)
     */
    public suspend fun push(
        inputStream: InputStream,
        remotePath: String,
        mode: Int = 33188,
        totalSize: Long = -1L,
        mtime: Long = System.currentTimeMillis() / 1000,
        progress: ((transferred: Long, total: Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            AdbSyncClient.open(connection).use { client ->
                client.pushStream(inputStream, remotePath, mode, totalSize, mtime, progress)
            }
        }.isSuccess
    }

    /**
     * 从设备端拉取文件/目录保存到本地 File
     */
    public suspend fun pull(
        remotePath: String,
        localFile: File,
        progress: ((transferred: Long, total: Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            AdbSyncClient.open(connection).use { client ->
                val stat = client.stat(remotePath)
                if (stat.isDirectory) {
                    pullDirectoryInternal(client, remotePath, localFile, progress)
                } else {
                    localFile.parentFile?.mkdirs()
                    client.pull(remotePath, localFile, progress)
                }
            }
        }.isSuccess
    }

    /**
     * 【直连无临时文件】拉取远程文件数据流并写入 OutputStream
     */
    public suspend fun pull(
        remotePath: String,
        outputStream: OutputStream,
        progress: ((transferred: Long, total: Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            AdbSyncClient.open(connection).use { client ->
                client.pull(remotePath, outputStream, progress)
            }
        }.isSuccess
    }

    /**
     * 列出远程目录文件列表
     */
    public suspend fun list(remotePath: String): List<AdbFileEntry> = withContext(Dispatchers.IO) {
        AdbSyncClient.open(connection).use { client ->
            client.list(remotePath)
        }
    }

    /**
     * 查询远程文件或目录状态
     */
    public suspend fun stat(remotePath: String): AdbFileEntry = withContext(Dispatchers.IO) {
        AdbSyncClient.open(connection).use { client ->
            client.stat(remotePath)
        }
    }

    // ================= 内部目录递归实现 =================

    private suspend fun pushDirectoryInternal(
        client: AdbSyncClient,
        localDir: File,
        remotePath: String,
        progress: ((transferred: Long, total: Long) -> Unit)?
    ) {
        // 转为 List 解决 Sequence 被消费导致的二次遍历问题
        val fileList = localDir.walkTopDown().filter { it.isFile }.toList()
        val totalSize = fileList.sumOf { it.length() }
        var totalTransferred = 0L

        val cleanRemoteBase = remotePath.trimEnd('/')

        fileList.forEach { file ->
            val relativePath = file.relativeTo(localDir).path.replace('\\', '/')
            val targetRemotePath = "$cleanRemoteBase/$relativePath"

            client.push(file, targetRemotePath) { transferred, _ ->
                progress?.invoke(totalTransferred + transferred, totalSize)
            }
            totalTransferred += file.length()
        }
    }

    private suspend fun pullDirectoryInternal(
        client: AdbSyncClient,
        remoteDir: String,
        localDir: File,
        progress: ((transferred: Long, total: Long) -> Unit)?
    ) {
        if (!localDir.exists()) localDir.mkdirs()
        val entries = client.list(remoteDir)
        val cleanRemoteDir = remoteDir.trimEnd('/')

        for (entry in entries) {
            if (entry.name == "." || entry.name == "..") continue
            val remoteChildPath = "$cleanRemoteDir/${entry.name}"
            val localChildFile = File(localDir, entry.name)

            if (entry.isDirectory) {
                pullDirectoryInternal(client, remoteChildPath, localChildFile, progress)
            } else {
                localChildFile.parentFile?.mkdirs()
                client.pull(remoteChildPath, localChildFile, progress)
            }
        }
    }
}
