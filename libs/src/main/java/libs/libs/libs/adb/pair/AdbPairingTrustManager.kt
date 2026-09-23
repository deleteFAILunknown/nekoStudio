package libs.libs.libs.adb.pair

import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

public class AdbPairingTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // 客户端无需校验
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // 无线配对阶段信任服务端的自签名证书
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        return arrayOf()
    }
}
