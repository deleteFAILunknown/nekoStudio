package com.adb.kitty.data

import android.content.Context
import android.util.Log
import com.flyfishxu.kadb.cert.HostKeySet
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import okio.Path.Companion.toPath
import java.io.File
import androidx.annotation.Keep

@Keep
class AdbKeyManager(private val context: Context) {

    companion object {
        private const val TAG = "AdbKeyManager"
    }

    init {
        initializeKadbCertEngine()
    }

    private fun initializeKadbCertEngine() {
        try {
            val privFile = File(context.filesDir, "adbkey")
            val okioPath = privFile.absolutePath.toPath()

            val store = OkioFilePrivateKeyStore(privateKeyPath = okioPath)

            val policy = KadbCertPolicy()

            KadbCert.configure(
                store = store,
                policy = policy,
                additionalPrivateKeysPem = emptyList()
            )

            val snapshot = KadbCert.ensureReady()
            
            Log.i(TAG, "🎉 KadbCert 密码学引擎部署就绪！物理指纹 SHA256: ${snapshot.fingerprintSha256}")
        } catch (e: Exception) {
            Log.e(TAG, "💥 初始化全局 KadbCert 矩阵发生致命断裂", e)
        }
    }

    fun forceRotateKeys() {
        try {
            Log.w(TAG, "⚠️ 触发强制重新轮换全局 ADB 密钥")
            
            val newSnapshot = KadbCert.rotate()
            
            Log.i(TAG, "🔄 密钥轮换成功！全新设备物理指纹: ${newSnapshot.fingerprintSha256}")
        } catch (e: Exception) {
            Log.e(TAG, "💥 强制轮换密钥对失败", e)
        }
    }

    fun getHostKeySet(): HostKeySet {
        return KadbCert.currentKeySet()
    }
}
