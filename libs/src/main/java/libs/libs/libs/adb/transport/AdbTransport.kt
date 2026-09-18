package libs.libs.libs.adb.transport

public interface AdbTransport {
    public suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
    public suspend fun write(buffer: ByteArray, offset: Int, length: Int)
    public suspend fun close()
}
