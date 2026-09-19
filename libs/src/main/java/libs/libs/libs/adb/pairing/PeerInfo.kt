package libs.libs.libs.adb.pairing

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class PeerInfo(
    @ProtoNumber(1) public val type: Int = 2, // 2 = ADB_CLIENT
    @ProtoNumber(2) public val guid: String,  // 客户端唯一识别码
    @ProtoNumber(3) public val name: String  // 展现在手机端已配对设备列表中的名称
)
