package com.adb.kitty.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                // 场景：设备刚开机，用户尚未输入锁屏密码/图案解锁
                // 注意：此时无法访问常规的 SharedPreferences 或数据库 (Credential Encrypted 存储)
                val directBootContext = context.createDeviceProtectedStorageContext()
                // 使用 directBootContext 读写设备保护存储
                Log.i("com.adb.kitty.receiver.BootReceiver", "ACTION_BOOT_COMPLETED")
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                // 场景：设备已完全启动且用户已解锁
                // 常规存储空间已挂载并解锁，可以正常访问 SharedPreferences / Room 等
                Log.i("com.adb.kitty.receiver.BootReceiver", "ACTION_LOCKED_BOOT_COMPLETED")
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // 场景：当前 App 在系统中完成了覆盖安装/版本更新
                // 常用于更新升级后的数据库迁移、后台任务重新调度等
                Log.i("com.adb.kitty.receiver.BootReceiver", "ACTION_MY_PACKAGE_REPLACED")
            }
        }
    }
}
