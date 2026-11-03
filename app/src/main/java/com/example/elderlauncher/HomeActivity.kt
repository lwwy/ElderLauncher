package com.example.elderlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

class HomeActivity : AppCompatActivity() {

    private lateinit var tvTime: TextView
    private lateinit var tvAmPm: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvLunar: TextView
    private lateinit var tvBattery: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val weekdays = arrayOf(
        "星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"
    )

    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 30000)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            tvBattery.text = if (pct >= 0) pct.toString() else "--"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        TtsManager.init(this)

        tvTime = findViewById(R.id.tvTime)
        tvAmPm = findViewById(R.id.tvAmPm)
        tvDate = findViewById(R.id.tvDate)
        tvLunar = findViewById(R.id.tvLunar)
        tvBattery = findViewById(R.id.tvBattery)

        // 时间卡右上角四圆点图标 -> 设置
        findViewById<ImageView>(R.id.ivWidget).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 2x2 联系人卡片与底部加号 -> 联系人列表（留空待添加）
        val openContacts = {
            startActivity(Intent(this, ContactsActivity::class.java))
        }
        findViewById<View>(R.id.cardContact1).setOnClickListener { openContacts() }
        findViewById<View>(R.id.cardContact2).setOnClickListener { openContacts() }
        findViewById<View>(R.id.cardContact3).setOnClickListener { openContacts() }
        findViewById<View>(R.id.cardContact4).setOnClickListener { openContacts() }
        findViewById<View>(R.id.btnAdd).setOnClickListener { openContacts() }

        updateClock()
    }

    private fun updateClock() {
        val cal = Calendar.getInstance()
        val now = Date()
        tvTime.text = DateFormat.format("HH:mm", now)
        tvAmPm.text = if (cal.get(Calendar.HOUR_OF_DAY) < 12)
            getString(R.string.home_am) else getString(R.string.home_pm)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        tvDate.text = "${m}月${d}日 ${weekdays[cal.get(Calendar.DAY_OF_WEEK) - 1]}"
        tvLunar.text = LunarUtil.getLunar(now)
    }

    override fun onResume() {
        super.onResume()
        updateClock()
        handler.post(clockRunnable)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockRunnable)
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
        }
    }
}
