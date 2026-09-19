package libs.libs.libs.adb.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

public class AdbMdnsDiscoverer(public val context: Context) {

    public val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    public data class DiscoveredService(
        public val name: String,
        public val host: String,
        public val port: Int,
        public val isPairingService: Boolean
    )

    public fun discoverServices(): Flow<DiscoveredService> = callbackFlow {
        val pairingServiceType = "_adb-pairing-tls._tcp."
        val connectServiceType = "_adb-tls-connect._tcp."

        fun createListener(isPairing: Boolean) = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

                    override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                        val host = resolvedInfo.host?.hostAddress ?: return
                        trySend(
                            DiscoveredService(
                                name = resolvedInfo.serviceName,
                                host = host,
                                port = resolvedInfo.port,
                                isPairingService = isPairing
                            )
                        )
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
        }

        val pairingListener = createListener(isPairing = true)
        val connectListener = createListener(isPairing = false)

        nsdManager.discoverServices(pairingServiceType, NsdManager.PROTOCOL_DNS_SD, pairingListener)
        nsdManager.discoverServices(connectServiceType, NsdManager.PROTOCOL_DNS_SD, connectListener)

        awaitClose {
            runCatching { nsdManager.stopServiceDiscovery(pairingListener) }
            runCatching { nsdManager.stopServiceDiscovery(connectListener) }
        }
    }
}
