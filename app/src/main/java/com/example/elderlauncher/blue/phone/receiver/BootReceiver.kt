package com.example.elderlauncher.blue.phone.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.elderlauncher.blue.core.LowBatteryAlarmScheduler
import com.example.elderlauncher.blue.ui.HomeActivity

/**
 * 开机 / 快速重启 / 应用更新后：拉起桌面。
 *
 * 说明：不再启动任何常驻前台服务。重启后 App 的正常工作依赖两点，均由系统按需拉起，
 * 与进程是否常驻无关：
 * 1. 桌面本身：本 Activity 声明为 HOME，设为默认桌面时系统会自动显示；这里做兜底拉起；
 * 2. 低电量播报：由 [LowBatteryAlarmScheduler] 用 AlarmManager 排的「系统级定时哨兵」驱动，
 *    息屏 / 其他 App 前台也能提醒；开机后在此重新武装该哨兵。
 *
 * 前提是用户在系统里允许本应用「自启动」（设置页有引导），国产 ROM 否则可能拦下开机广播。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val home = Intent(context, HomeActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(home)
                } catch (_: Exception) {
                    // Android 10+ 限制后台启动 Activity：若被拦下，设为默认桌面时
                    // 系统仍会自动显示 HOME，不影响重启后进入桌面
                }
                // 重启 / 应用更新后重新武装低电量播报的系统级定时哨兵（息屏也能提醒）
                LowBatteryAlarmScheduler.ensureScheduled(context.applicationContext)
            }
        }
    }
}
