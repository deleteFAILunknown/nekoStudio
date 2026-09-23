package libs.libs.libs.adb.connect

public sealed class AdbConnectionState {
    public object Disconnected : AdbConnectionState()
    public object Connecting : AdbConnectionState()
    public object Authenticating : AdbConnectionState()
    public data class Connected(val banner: String) : AdbConnectionState()
    public data class Error(val throwable: Throwable) : AdbConnectionState()
}
