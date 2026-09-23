package libs.libs.libs.adb.shell

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class ShellCommandResult(
    @ProtoNumber(1) val exitCode: Int,
    @ProtoNumber(2) val stdout: String,
    @ProtoNumber(3) val stderr: String = "",
    @ProtoNumber(4) val durationMs: Long = 0L
) {
    val isSuccess: Boolean get() = exitCode == 0
}
