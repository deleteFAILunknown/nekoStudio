package libs.libs.libs.adb.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.Executors

public class AdbMdnsDiscoverer(public val context: Context) {

    public val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val executor = Executors.newSingleThreadExecutor()

    public data class DiscoveredService(
        public val name: String,
        public val host: String,
        public val port: Int,
        public val isPairingService: Boolean
    )

    public fun discoverServices(): Flow<DiscoveredService> = callbackFlow {
        val pairingServiceType = "_adb-pairing-tls._tcp."
        val connectServiceType = "_adb-tls-connect._tcp."

        fun resolveServiceInfo(serviceInfo: NsdServiceInfo, isPairing: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ (API 34+) 新版异步回调解析
                nsdManager.resolveService(
                    serviceInfo,
                    executor,
                    object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}

                        override fun onServiceUpdated(resolvedInfo: NsdServiceInfo) {
                            val host = resolvedInfo.hostAddresses.firstOrNull()?.hostAddress ?: return
                            trySend(
                                DiscoveredService(
                                    name = resolvedInfo.serviceName,
                                    host = host,
                                    port = resolvedInfo.port,
                                    isPairingService = isPairing
                                )
                            )
                        }

                        override fun onServiceLost() {}

                        override fun onServiceInfoCallbackUnregistered() {}
                    }
                )
            } else {
                // API < 34 旧版兼容逻辑
                @Suppress("DEPRECATION")
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

                    override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
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
        }

        fun createListener(isPairing: Boolean) = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveServiceInfo(serviceInfo, isPairing)
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
            executor.shutdown()
        }
    }
}
