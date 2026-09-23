package libs.libs.libs.adb.shell

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class ShellStreamChunk(
    @ProtoNumber(1) val type: ShellStreamType,
    @ProtoNumber(2) val data: ByteArray,
    @ProtoNumber(3) val timestamp: Long = System.currentTimeMillis()
)
