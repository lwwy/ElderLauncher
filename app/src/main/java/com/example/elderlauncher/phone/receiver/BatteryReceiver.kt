package com.example.elderlauncher.phone.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.elderlauncher.R
import com.example.elderlauncher.core.TtsManager
import com.example.elderlauncher.data.Prefs

/** 低电量语音提醒 */
class BatteryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BATTERY_LOW) {
            if (!Prefs.enabled(context, Prefs.ANN_LOW)) return
            TtsManager.speakNow(context, context.getString(R.string.ann_low_battery))
        }
    }
}
