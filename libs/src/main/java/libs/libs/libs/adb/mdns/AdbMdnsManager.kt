package libs.libs.libs.adb.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

public class AdbMdnsManager(context: Context) {

    private val nsdManager: NsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    // 用于串行化处理 resolveService 调用的互斥锁，防止 Android NsdManager 报 FAILURE_ALREADY_ACTIVE
    private val resolveMutex = Mutex()

    /**
     * 监听指定的 ADB mDNS 服务类型，返回发现并解析成功的设备流
     */
    public fun discoverServices(mdnsType: AdbMdnsType): Flow<AdbMdnsServiceInfo> =
        discoverRawServices(mdnsType).mapNotNull { rawService ->
            resolveServiceSafely(rawService, mdnsType)
        }

    /**
     * 发现指定 mDNS 类型的原始 NSD 服务流
     */
    private fun discoverRawServices(mdnsType: AdbMdnsType): Flow<NsdServiceInfo> =
        callbackFlow<NsdServiceInfo> {
            val discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType.contains(mdnsType.rawType.trimEnd('.'))) {
                        trySendBlocking(serviceInfo)
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    close(RuntimeException("Start discovery failed with error code: $errorCode"))
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    nsdManager.stopServiceDiscovery(this)
                }
            }

            try {
                nsdManager.discoverServices(
                    mdnsType.rawType,
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener
                )
            } catch (e: Exception) {
                close(e)
            }

            awaitClose {
                try {
                    nsdManager.stopServiceDiscovery(discoveryListener)
                } catch (_: Exception) {
                }
            }
        }

    /**
     * 安全地异步解析 NsdServiceInfo（带有 Mutex 互斥锁）
     */
    @Suppress("DEPRECATION")
    private suspend fun resolveServiceSafely(
        serviceInfo: NsdServiceInfo,
        type: AdbMdnsType
    ): AdbMdnsServiceInfo? = resolveMutex.withLock {
        suspendCoroutine { continuation ->
            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    continuation.resume(null)
                }

                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    val attributesMap = mutableMapOf<String, String>()

                    // 解析 TXT 记录
                    resolvedInfo.attributes?.forEach { (key, value) ->
                        if (value != null) {
                            attributesMap[key] = String(value, StandardCharsets.UTF_8)
                        }
                    }

                    // API 34+ 使用 hostAddresses 列表，旧版本降级使用 host
                    val hostAddress: InetAddress? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        resolvedInfo.hostAddresses.firstOrNull()
                    } else {
                        resolvedInfo.host
                    }

                    val result = AdbMdnsServiceInfo(
                        name = resolvedInfo.serviceName,
                        type = type,
                        host = hostAddress,
                        port = resolvedInfo.port,
                        attributes = attributesMap
                    )
                    continuation.resume(result)
                }
            }

            try {
                nsdManager.resolveService(serviceInfo, resolveListener)
            } catch (e: Exception) {
                continuation.resume(null)
            }
        }
    }
}
