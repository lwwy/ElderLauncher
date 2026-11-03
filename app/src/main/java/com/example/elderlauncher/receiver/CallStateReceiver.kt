package com.example.elderlauncher.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.example.elderlauncher.ContactsHelper
import com.example.elderlauncher.Prefs
import com.example.elderlauncher.TtsManager

/** 来电播报 + 骚扰号码自动挂断（本地拦截，不出网） */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        if (state == TelephonyManager.EXTRA_STATE_RINGING && number != null) {
            if (Prefs.blockSet(context).contains(normalize(number))) {
                TtsManager.speakNow(context, "已拦截骚扰来电")
                hangup(context)
                return
            }
            if (!Prefs.enabled(context, Prefs.ANN_INCOMING)) return
            val name = ContactsHelper.lookupName(context, number)
            val who = if (name.isNullOrEmpty()) "未知号码" else name
            TtsManager.speakNow(context, "来电，$who")
        }
    }

    private fun normalize(n: String): String = n.replace("[^0-9]".toRegex(), "")

    private fun hangup(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                tm.endCall()
            }
        } catch (e: Exception) {
            // 部分机型/系统不支持程序化挂断，忽略
        }
    }
}
