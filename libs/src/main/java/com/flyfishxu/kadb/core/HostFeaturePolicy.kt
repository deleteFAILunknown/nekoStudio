package com.flyfishxu.kadb.core

import com.flyfishxu.kadb.DelayedAckMode

fun shouldAdvertiseDelayedAck(mode: DelayedAckMode): Boolean = when (mode) {
    DelayedAckMode.AOSP_DEFAULT -> aospDefaultDelayedAckEnabled()
    DelayedAckMode.ENABLED -> true
    DelayedAckMode.DISABLED -> false
}

/**
 * 抹去 expect/actual 后，直接提供 Android 端的实体实现
 */
fun aospDefaultDelayedAckEnabled(): Boolean = false
