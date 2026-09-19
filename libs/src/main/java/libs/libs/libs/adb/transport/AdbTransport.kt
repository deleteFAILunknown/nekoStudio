package libs.libs.libs.adb.transport

import libs.libs.libs.adb.protocol.AdbCrypto

public interface AdbTransport {
    public suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
    public suspend fun write(buffer: ByteArray, offset: Int, length: Int)
    public suspend fun close()
    
    // 动态升级传输层为 TLS 加密通道
    public suspend fun startTls(crypto: AdbCrypto) {}
}
