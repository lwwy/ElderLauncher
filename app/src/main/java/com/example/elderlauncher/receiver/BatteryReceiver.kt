package com.example.elderlauncher.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.elderlauncher.Prefs
import com.example.elderlauncher.TtsManager

/** 低电量语音提醒 */
class BatteryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BATTERY_LOW) {
            if (!Prefs.enabled(context, Prefs.ANN_LOW)) return
            TtsManager.speakNow(context, "电量不足，请插上充电器")
        }
    }
}
