package com.example.elderlauncher.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import com.example.elderlauncher.ContactsHelper
import com.example.elderlauncher.Prefs
import com.example.elderlauncher.TtsManager

/** 收到新短信时播报（本地，不出网） */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.enabled(context, Prefs.ANN_SMS)) return
        val pdus = intent.getSerializableExtra("pdus") as? Array<*>
        if (pdus == null) return
        val msgs = pdus.mapNotNull {
            val bytes = it as? ByteArray ?: return@mapNotNull null
            SmsMessage.createFromPdu(bytes)
        }
        for (m in msgs) {
            val addr = m.displayOriginatingAddress ?: continue
            val name = ContactsHelper.lookupName(context, addr) ?: addr
            TtsManager.speakNow(context, "收到短信，来自 $name")
        }
    }
}
