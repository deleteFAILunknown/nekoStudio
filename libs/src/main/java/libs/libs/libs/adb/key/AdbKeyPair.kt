package libs.libs.libs.adb.key

import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.RSAKeyParameters

public data class AdbKeyPair(
    val privateKey: AsymmetricKeyParameter,
    val publicKeyParams: RSAKeyParameters,
    val adbPublicKeyString: String
)
