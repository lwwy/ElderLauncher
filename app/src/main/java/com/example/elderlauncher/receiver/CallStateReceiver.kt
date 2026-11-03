package com.example.elderlauncher.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.example.elderlauncher.ContactsHelper
import com.example.elderlauncher.Prefs
import com.example.elderlauncher.TtsManager

/** 来电时弹出大卡片（接听/挂断），并按设置播报来电人（纯本地，不出网） */
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

        val i = Intent(context, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            putExtra("number", number ?: "")
            putExtra("name", name ?: "")
        }
        try {
            context.startActivity(i)
        } catch (_: Exception) {
            // 后台启动活动被系统限制时忽略，设置页可引导设为默认电话应用
        }
    }
}
