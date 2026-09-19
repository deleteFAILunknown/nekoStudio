package libs.libs.libs.adb.services

import libs.libs.libs.adb.session.AdbConnection

public class AdbServices(public val connection: AdbConnection) {
    public val shell: AdbShellService = AdbShellService(connection)
    public val sync: AdbSyncService = AdbSyncService(connection)
}
