package com.example.elderlauncher.phone

import android.os.SystemClock

/**
 * 拨号防连点：老人手抖或点击后没立刻看到反馈时容易连点，可能叠出两层系统拨号界面、
 * 或在「权限弹窗」期间重复发起授权请求。
 *
 * 只在「用户点击拨号」的入口（HomeActivity.call）调用，
 * 绝不用于 onCallPermissionResult 的授权后自动重拨 —— 那是一次点击的延续，
 * 若被节流挡掉会变回「允许权限后不拨号」的老问题。
 *
 * 用 elapsedRealtime（开机计时）而非 currentTimeMillis，避免用户改系统时间导致误判。
 */
object CallThrottle {

    /** 1 秒内的重复点击视为误触；老人动作慢，窗口不宜再长，否则真要重拨会等太久 */
    private const val WINDOW_MS = 1000L

    private var lastCallTime: Long? = null

    /** @return true 表示允许本次发起拨号；false 表示是窗口内的重复点击，应忽略 */
    @Synchronized
    fun tryAcquire(now: Long = SystemClock.elapsedRealtime()): Boolean {
        val last = lastCallTime
        if (last != null && now - last in 0 until WINDOW_MS) return false
        lastCallTime = now
        return true
    }

    /** 仅供单元测试复位进程内状态 */
    internal fun resetForTest() {
        lastCallTime = null
    }
}
