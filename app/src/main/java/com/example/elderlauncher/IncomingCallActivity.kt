package com.example.elderlauncher

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * 来电大卡片：全屏显示来电人，仅提供「接听 / 挂断」两个大按钮。
 * 参考老人桌面 UI：高对比、大字号、操作极简。
 * 接听/挂断通过 TelecomManager 完成，需 ANSWER_PHONE_CALLS 权限；
 * 在 Android 10+ 上建议把本应用设为默认电话应用以保证可靠生效（见设置页）。
 */
class IncomingCallActivity : AppCompatActivity() {

    private var listener: PhoneStateListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 锁屏也能亮屏弹出大卡片
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

        val name = intent.getStringExtra("name") ?: ""
        val number = intent.getStringExtra("number") ?: ""

        val tvCaller = findViewById<TextView>(R.id.tvCaller)
        val tvNumber = findViewById<TextView>(R.id.tvNumber)
        tvCaller.text = if (name.isNotEmpty()) name else (if (number.isNotEmpty()) number else getString(R.string.incoming_call))
        tvNumber.text = if (name.isNotEmpty()) number else ""

        findViewById<Button>(R.id.btnAnswer).setOnClickListener { answer() }
        findViewById<Button>(R.id.btnHangup).setOnClickListener { hangup() }

        registerCallEndWatcher()
    }

    private fun answer() {
        if (hasAnswerPerm()) {
            try {
                val tm = getSystemService(TELECOM_SERVICE) as TelecomManager
                tm.acceptRingingCall()
            } catch (_: Exception) {
                // 部分机型/系统在非默认电话应用下不支持，忽略
            }
        }
        finish()
    }

    private fun hangup() {
        if (hasAnswerPerm()) {
            try {
                val tm = getSystemService(TELECOM_SERVICE) as TelecomManager
                tm.endCall()
            } catch (_: Exception) {
                // 忽略
            }
        }
        finish()
    }

    private fun hasAnswerPerm(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    /** 通话结束（IDLE）时自动关闭大卡片 */
    private fun registerCallEndWatcher() {
        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                if (state == TelephonyManager.CALL_STATE_IDLE) finish()
            }
        }
        if (hasPhoneStatePerm()) {
            @Suppress("DEPRECATION")
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun hasPhoneStatePerm(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        val tm = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
        try {
            @Suppress("DEPRECATION")
            tm?.listen(listener, PhoneStateListener.LISTEN_NONE)
        } catch (_: Exception) {
        }
    }
}
