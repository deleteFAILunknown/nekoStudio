package libs.libs.libs.adb.shell

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class ShellRequest(
    @ProtoNumber(1) val command: String,
    @ProtoNumber(2) val useExecMode: Boolean = true,
    @ProtoNumber(3) val timeoutMs: Long = 30000L
)
