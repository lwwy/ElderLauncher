package com.example.elderlauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

class HomeActivity : AppCompatActivity() {

    private lateinit var tvTime: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvLunar: TextView
    private lateinit var tvBattery: TextView

    private val cardIds = intArrayOf(
        R.id.cardContact1, R.id.cardContact2, R.id.cardContact3
    )
    private val avatarIds = intArrayOf(
        R.id.imgAvatar1, R.id.imgAvatar2, R.id.imgAvatar3
    )
    private val nameIds = intArrayOf(
        R.id.tvName1, R.id.tvName2, R.id.tvName3
    )
    private val phones = arrayOfNulls<String>(3)

    // 时间区连点 5 次进入设置（防误触的隐藏入口，照顾人可用）
    private var tapCount = 0
    private var lastTapTime = 0L

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

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
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
        tvDate = findViewById(R.id.tvDate)
        tvLunar = findViewById(R.id.tvLunar)
        tvBattery = findViewById(R.id.tvBattery)

        // 设置入口：右上角齿轮（看护人使用）
        findViewById<android.view.View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 时间区：连点 5 次进入设置（隐藏入口）
        findViewById<android.view.View>(R.id.timeBlock).setOnClickListener {
            val now = System.currentTimeMillis()
            tapCount = if (now - lastTapTime > 1500) 1 else tapCount + 1
            lastTapTime = now
            if (tapCount >= 5) {
                tapCount = 0
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }

        // 2x2 联系人卡片：仅点击已添加联系人 → 一键拨号
        for (i in cardIds.indices) {
            val card = findViewById<android.view.View>(cardIds[i])
            card.setOnClickListener { onCardClick(i) }
        }

        updateClock()

        // 启动后台保活前台服务（保证来电/通话不被系统回收）
        KeepAliveService.start(this)
        // 注册为系统可拨号账号（设为默认电话应用后由系统路由来电/去电）
        TelecomHelper.register(this)
        requestNotifPermission()
    }

    private fun onCardClick(i: Int) {
        val phone = phones[i]
        if (phone.isNullOrEmpty()) {
            Toast.makeText(this, R.string.home_empty_hint, Toast.LENGTH_SHORT).show()
            return
        }
        call(phone)
    }

    private fun refreshContacts() {
        for (i in nameIds.indices) {
            val tv = findViewById<TextView>(nameIds[i])
            val img = findViewById<ImageView>(avatarIds[i])
            val contact = LocalContacts.get(this, i)
            if (contact == null || contact.name.isEmpty()) {
                tv.text = getString(R.string.contact_add)
                img.setImageResource(R.drawable.ic_person)
                img.setColorFilter(
                    ContextCompat.getColor(this, R.color.colorOnSurfaceVariant),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
                phones[i] = null
            } else {
                tv.text = contact.name
                val bmp: Bitmap? = LocalContacts.loadAvatar(this, i)
                if (bmp != null) {
                    img.clearColorFilter()
                    img.setImageBitmap(bmp)
                } else {
                    img.setImageResource(R.drawable.ic_person)
                    img.setColorFilter(
                        ContextCompat.getColor(this, R.color.colorOnSurfaceVariant),
                        android.graphics.PorterDuff.Mode.SRC_IN
                    )
                }
                phones[i] = contact.phone
            }
        }
    }

    private fun call(number: String) {
        if (number.isEmpty()) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 1)
            return
        }
        TelecomHelper.placeCall(this, number)
    }

    private fun updateClock() {
        val cal = Calendar.getInstance()
        val now = Date()
        tvTime.text = SimpleDateFormat("HH:mm").format(now)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        tvDate.text = "${m}月${d}日 ${weekdays[cal.get(Calendar.DAY_OF_WEEK) - 1]}"
        tvLunar.text = LunarUtil.getLunar(now)
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updateClock()
        refreshContacts()
        handler.post(clockRunnable)
        registerReceiver(
            batteryReceiver,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        )
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
