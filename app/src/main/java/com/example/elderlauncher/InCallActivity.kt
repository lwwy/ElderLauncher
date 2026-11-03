package com.example.elderlauncher

import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.VideoProfile
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 全屏来电/通话大卡片：高对比、大字号。
 * 振铃时显示「接听 / 挂断」，接通后仅显示「挂断」，挂断或空闲自动关闭。
 */
class InCallActivity : AppCompatActivity() {
    private var call: Call? = null
    private val callback = object : Call.Callback() {
        override fun onStateChanged(c: Call, state: Int) {
            runOnUiThread { updateUi(state) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        setContentView(R.layout.activity_incoming_call)
        bindCall()
    }

    private fun bindCall() {
        var c = ElderInCallService.currentCall()
        if (c == null) {
            // 服务尚未把 Call 引用传递过来，稍后重试
            window.decorView.postDelayed({
                c = ElderInCallService.currentCall()
                if (c == null) {
                    finish()
                } else {
                    attach(c!!)
                }
            }, 300)
        } else {
            attach(c)
        }
    }

    private fun attach(c: Call) {
        call = c
        c.registerCallback(callback)
        val number = c.details?.handle?.schemeSpecificPart ?: ""
        val name = if (number.isNotEmpty()) ContactsHelper.lookupName(this, number) else null
        findViewById<TextView>(R.id.tvCaller).text =
            name ?: (if (number.isNotEmpty()) number else getString(R.string.incoming_call))
        findViewById<TextView>(R.id.tvNumber).text = name?.let { number } ?: ""
        findViewById<Button>(R.id.btnAnswer).setOnClickListener { answer() }
        findViewById<Button>(R.id.btnHangup).setOnClickListener { hangup() }
        updateUi(c.state)
    }

    private fun updateUi(state: Int) {
        val btnAnswer = findViewById<Button>(R.id.btnAnswer)
        when (state) {
            Call.STATE_RINGING -> btnAnswer.visibility = android.view.View.VISIBLE
            Call.STATE_ACTIVE, Call.STATE_DIALING -> btnAnswer.visibility = android.view.View.GONE
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> finish()
        }
    }

    private fun answer() {
        try {
            call?.answer(VideoProfile.STATE_AUDIO_ONLY)
        } catch (_: Exception) {
        }
    }

    private fun hangup() {
        try {
            call?.disconnect()
        } catch (_: Exception) {
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        call?.unregisterCallback(callback)
    }
}
