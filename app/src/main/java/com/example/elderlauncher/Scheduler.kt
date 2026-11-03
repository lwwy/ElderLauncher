package com.example.elderlauncher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/** 周期性调度整点报时广播 */
object Scheduler {
    private const val REQ = 1001

    fun scheduleHourly(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, com.example.elderlauncher.receiver.ClockReceiver::class.java).apply {
            action = "com.example.elderlauncher.ACTION_HOURLY"
        }
        val pi = PendingIntent.getBroadcast(
            context, REQ, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cal = Calendar.getInstance().apply {
            if (get(Calendar.MINUTE) > 0 || get(Calendar.SECOND) > 0) {
                add(Calendar.HOUR_OF_DAY, 1)
            }
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            cal.timeInMillis,
            AlarmManager.INTERVAL_HOUR,
            pi
        )
    }
}
