package com.flyfishxu.kadb.cert.platform

import android.os.Build

fun defaultDeviceName(software: String? = null): String {
    software
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it }

    val userName = System.getProperty("user.name")
        ?: System.getenv("USER")
    val hostName = System.getenv("HOSTNAME")
        ?: Build.MODEL.takeIf { !it.isNullOrBlank() }
        ?: Build.DEVICE
    return formatDefaultDeviceName(userName, hostName)
}

fun formatDefaultDeviceName(loginName: String?, hostName: String?): String {
    fun normalize(component: String?): String {
        return component
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "unknown"
    }

    return "${normalize(loginName)}@${normalize(hostName)}"
}
