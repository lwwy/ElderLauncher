package com.example.elderlauncher

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log

/**
 * 通话界面服务：系统把当前 Call 交给本服务，由它拉起全屏 InCallActivity，
 * 并响应“无障碍”自动接听。
 */
class ElderInCallService : InCallService() {

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        ActiveCall.call = call

        // 无障碍：自动接听
        if (Prefs.autoAnswer(this)) {
            val delay = Prefs.autoAnswerDelay(this).coerceAtLeast(1)
            Handler(Looper.getMainLooper()).postDelayed({ safeAnswer(call) }, delay * 1000L)
        }

        val i = Intent(this, InCallActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
        }
        try {
            startActivity(i)
        } catch (e: Exception) {
            Log.e("ElderInCall", "start InCallActivity failed", e)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (ActiveCall.call == call) ActiveCall.call = null
    }

    private fun safeAnswer(call: Call) {
        try {
            call.answer(VideoProfile.STATE_AUDIO_ONLY)
        } catch (_: Exception) {
        }
    }

    companion object {
        fun currentCall(): Call? = ActiveCall.call
    }
}

/** 当前通话引用，供 InCallActivity 读取 */
object ActiveCall {
    var call: Call? = null
}
