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

        // 注意：Android 10+ 普通应用拿不到来电号码（需 READ_CALL_LOG 或默认拨号器角色）。
        // 早期实现把「号码为空」也当成不播报，导致非默认拨号器场景下来电完全不响，
        // 这恰恰是老人最需要提示的时候——所以这里只要处于响铃状态就播。
        if (Prefs.enabled(context, Prefs.ANN_INCOMING)) {
            val who = if (!name.isNullOrEmpty()) name else "未知号码"
            TtsManager.speakNow(context, "来电，$who")
        }
    }
}
