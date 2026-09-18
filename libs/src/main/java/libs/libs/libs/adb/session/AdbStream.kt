package libs.libs.libs.adb.session

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import libs.libs.libs.adb.protocol.AdbCommand
import libs.libs.libs.adb.protocol.AdbPacket

public class AdbStream(
    public val localId: Int,
    public var remoteId: Int,
    public val connection: AdbConnection
) {
    public val channel: Channel<ByteArray> = Channel(Channel.UNLIMITED)
    public val responseFlow: Flow<ByteArray> = channel.consumeAsFlow()

    public suspend fun write(data: ByteArray) {
        connection.sendPacket(AdbPacket(AdbCommand.CMD_WRTE, localId, remoteId, data))
    }

    public suspend fun close() {
        connection.sendPacket(AdbPacket(AdbCommand.CMD_CLSE, localId, remoteId))
        channel.close()
        connection.unregisterStream(localId)
    }

    public suspend fun receiveData(data: ByteArray) {
        channel.send(data)
        connection.sendPacket(AdbPacket(AdbCommand.CMD_OKAY, localId, remoteId))
    }

    public fun onRemoteClosed() {
        channel.close()
        connection.unregisterStream(localId)
    }
}
