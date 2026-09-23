package libs.libs.libs.adb.pair

public interface AdbPairingListener {
    public fun onPairingStarted()
    public fun onPairingSuccess(peerPublicKey: String?)
    public fun onPairingFailed(throwable: Throwable)
}
