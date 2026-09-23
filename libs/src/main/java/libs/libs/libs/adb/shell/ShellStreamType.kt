package libs.libs.libs.adb.shell

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
public enum class ShellStreamType {
    @ProtoNumber(0) STDOUT,
    @ProtoNumber(1) STDERR,
    @ProtoNumber(2) ERROR
}
