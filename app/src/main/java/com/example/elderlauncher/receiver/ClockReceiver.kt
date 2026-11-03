package com.example.elderlauncher.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.elderlauncher.Prefs
import com.example.elderlauncher.TtsManager
import java.util.Calendar
import java.util.Locale

/** 整点报时 */
class ClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.enabled(context, Prefs.ANN_HOURLY)) return
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val am = if (h < 12) "上午" else "下午"
        val hour12 = if (h % 12 == 0) 12 else h % 12
        val text = String.format(Locale.CHINESE, "现在时间 %s%d点", am, hour12)
        TtsManager.speakNow(context, text)
    }
}
