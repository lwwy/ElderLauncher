package com.example.elderlauncher.phone.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.elderlauncher.core.LowBatteryAnnouncer

/**
 * 低电量语音提醒：系统广播的兼容入口。
 *
 * 注意：ACTION_BATTERY_LOW / BATTERY_OKAY 不在 Android 8.0+ 的隐式广播豁免清单内，
 * 多数 ROM 上清单静态注册的接收器会被系统拦截，收不到广播。
 * 因此播报主路径改由桌面动态注册的 ACTION_BATTERY_CHANGED 驱动
 * （见 HomeActivity.batteryReceiver → [LowBatteryAnnouncer.onBatteryChanged]）。
 *
 * 这里保留静态入口，仅用于少数仍会投递 BATTERY_LOW 的 ROM；
 * 两条路径共用 [LowBatteryAnnouncer] 的去重状态，不会重复播报。
 */
class BatteryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BATTERY_LOW -> LowBatteryAnnouncer.onSystemLowBattery(context)
            Intent.ACTION_BATTERY_OKAY -> LowBatteryAnnouncer.onSystemBatteryOkay()
        }
    }
}
