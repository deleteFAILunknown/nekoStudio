package libs.libs.libs.adb.pairing

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

public enum class PairingPacketType(val value: Int) {
    UNKNOWN(0),
    SPAKE2_MATTER(1),
    SPAKE2_ERROR(2),
    PAIRING_COMPLETE(3)
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class PairingPacket(
    @ProtoNumber(1) val type: Int = PairingPacketType.UNKNOWN.value,
    @ProtoNumber(2) val seq: Int = 0,
    @ProtoNumber(3) val payload: ByteArray = byteArrayOf()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PairingPacket
        return type == other.type && seq == other.seq && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + seq
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
