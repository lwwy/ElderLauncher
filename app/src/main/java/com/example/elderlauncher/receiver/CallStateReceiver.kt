package com.example.elderlauncher.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.example.elderlauncher.ContactsHelper
import com.example.elderlauncher.Prefs
import com.example.elderlauncher.TtsManager

/**
 * 来电播报：纯本地 TTS 朗读来电人（不出网）。
 * 来电“大卡片”界面由 ElderInCallService 接管，这里不再弹 Activity，避免重复界面。
 */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state != TelephonyManager.EXTRA_STATE_RINGING) return

        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val name = if (!number.isNullOrEmpty()) ContactsHelper.lookupName(context, number) else null

        if (Prefs.enabled(context, Prefs.ANN_INCOMING) && !number.isNullOrEmpty()) {
            val who = if (name.isNullOrEmpty()) "未知号码" else name
            TtsManager.speakNow(context, "来电，$who")
        }
    }
}
