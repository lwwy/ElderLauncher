package com.example.elderlauncher.phone

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import com.example.elderlauncher.ui.InCallActivity

/**
 * 通话界面服务：系统把当前 Call 交给本服务，由它拉起全屏 InCallActivity 呈现接听/挂断界面。
 */
class ElderInCallService : InCallService() {

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        ActiveCall.call = call
        val i = Intent(this, InCallActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
        }
        try {
            startActivity(i)
        } catch (_: Exception) {
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (ActiveCall.call == call) ActiveCall.call = null
    }

    companion object {
        fun currentCall(): Call? = ActiveCall.call
    }
}

/** 当前通话引用，供 InCallActivity 读取 */
object ActiveCall {
    var call: Call? = null
}
