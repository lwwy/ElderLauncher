package com.example.elderlauncher.blue.phone.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.elderlauncher.blue.core.LowBatteryAlarmScheduler

/**
 * 低电量播报的闹钟接收器（系统级，exported=false）。
 *
 * 由 [LowBatteryAlarmScheduler] 用 AlarmManager 唤醒：读取当前电量（粘性广播），
 * 交给 [LowBatteryAlarmScheduler.tick] 走统一状态机并续排下一次闹钟。
 * 这样息屏 / 其他 App 前台时也能按时提醒，不再依赖桌面 Activity 存活。
 */
class LowBatteryAlarmReceiver : BroadcastReceiver() {

    /** 可注入的电量读取，便于单测不依赖真实广播 */
    internal var batteryReader: (Context) -> Pair<Int, Boolean> =
        { LowBatteryAlarmScheduler.readBattery(it) }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != LowBatteryAlarmScheduler.ACTION) return
        val (pct, plugged) = batteryReader(context)
        LowBatteryAlarmScheduler.tick(context.applicationContext, pct, plugged)
    }
}
