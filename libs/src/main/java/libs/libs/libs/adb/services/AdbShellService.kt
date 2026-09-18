package libs.libs.libs.adb.services

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import libs.libs.libs.adb.session.AdbConnection

public class AdbShellService(public val connection: AdbConnection) {

    public suspend fun exec(command: String): Flow<String> {
        val stream = connection.openStream("shell:$command")
        return stream.responseFlow.map { String(it, Charsets.UTF_8) }
    }
}
