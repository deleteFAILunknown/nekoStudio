package libs.libs.libs.adb.services

import libs.libs.libs.adb.session.AdbConnection

public class AdbServices(public val connection: AdbConnection) {

    /** Shell 命令执行服务 (支持 shell_v2 协议解析与 V1 降级) */
    public val shell: AdbShellService by lazy { 
        AdbShellService(connection) 
    }

    /** 文件同步服务 (支持 Push/Pull/List/Stat 及 Sync V2 特征) */
    public val sync: AdbSyncService by lazy { 
        AdbSyncService(connection) 
    }

    /** 原生二进制与 Cmd 服务 (绕过 PTY 伪终端，支持 cmd 通道) */
    public val exec: AdbExecService by lazy { 
        AdbExecService(connection) 
    }

    /** ABB 极速安装服务 (支持 abb/abb_exec、单包 APK、拆分包及 .apks 安装) */
    public val abb: AdbAbbService by lazy { 
        AdbAbbService(connection) 
    }

    /** 设备状态与控制服务 (支持 reboot、remount、root/unroot、getprop) */
    public val control: AdbDeviceControlService by lazy { 
        AdbDeviceControlService(connection) 
    }
}
