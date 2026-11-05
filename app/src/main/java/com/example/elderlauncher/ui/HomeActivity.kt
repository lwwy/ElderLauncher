package com.example.elderlauncher.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.elderlauncher.R
import com.example.elderlauncher.core.CrashLogger
import com.example.elderlauncher.core.ElderApp
import com.example.elderlauncher.core.LunarUtil
import com.example.elderlauncher.core.TtsManager
import com.example.elderlauncher.data.LocalContacts
import com.example.elderlauncher.phone.TelecomHelper
import com.example.elderlauncher.service.KeepAliveService
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    private lateinit var tvTime: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvLunar: TextView
    private lateinit var tvAmpm: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvBatteryLabel: TextView
    private lateinit var cardBattery: MaterialCardView

    private val cardIds = intArrayOf(
        R.id.cardContact1, R.id.cardContact2, R.id.cardContact3
    )
    private val avatarIds = intArrayOf(
        R.id.imgAvatar1, R.id.imgAvatar2, R.id.imgAvatar3
    )
    private val nameIds = intArrayOf(
        R.id.tvName1, R.id.tvName2, R.id.tvName3
    )
    private val placeholderIds = intArrayOf(
        R.id.ivPlaceholder1, R.id.ivPlaceholder2, R.id.ivPlaceholder3
    )
    private val phones = arrayOfNulls<String>(3)

    // 时间区连点 5 次进入设置（防误触的隐藏入口，照顾人可用）
    private var tapCount = 0
    private var lastTapTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val weekdays = arrayOf(
        "星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"
    )

    // 拨号前若权限未授予，先记下待拨号码，待用户在授权弹窗点“允许”后再真正发起，
    // 避免“首次点击只弹权限框、必须再点一次才拨”的割裂体验。
    private var pendingCallNumber: String? = null

    companion object {
        private const val REQ_CALL_PHONE = 1
        private const val REQ_POST_NOTIF = 2
    }

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
            updateBattery(pct)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前接管启动画面，否则冷启动会先闪一帧空窗口
        installSplashScreen()
        super.onCreate(savedInstanceState)
        CrashLogger.trace("HomeActivity.onCreate 开始")
        setContentView(R.layout.activity_home)
        CrashLogger.trace("setContentView 完成")

        // 预热语音引擎：TTS 初始化要拉起引擎服务，放在首屏渲染之后再做，
        // 避免拖慢冷启动；首次来电播报前也能就绪。
        handler.postDelayed({
            TtsManager.init(applicationContext)
            CrashLogger.trace("TtsManager.init 完成（延迟预热）")
        }, 3000)

        tvTime = findViewById(R.id.tvTime)
        tvDate = findViewById(R.id.tvDate)
        tvLunar = findViewById(R.id.tvLunar)
        tvAmpm = findViewById(R.id.tvAmpm)
        tvBattery = findViewById(R.id.tvBattery)
        tvBatteryLabel = findViewById(R.id.tvBatteryLabel)
        cardBattery = findViewById(R.id.cardBattery)
        CrashLogger.trace("findViewById 完成")

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
        CrashLogger.trace("点击事件绑定完成")

        updateClock()
        CrashLogger.trace("updateClock 完成")

        // 启动后台保活前台服务（保证来电/通话不被系统回收）
        // 安全模式：上次启动异常时本次跳过，确保能进桌面
        val app = application as? ElderApp
        if (app?.skipKeepAliveOnce == true) {
            CrashLogger.trace("安全模式：本次跳过 KeepAliveService")
        } else {
            KeepAliveService.start(this)
            CrashLogger.trace("KeepAliveService.start 调用完成")
        }

        requestNotifPermission()

        // 拦截返回键：本页就是桌面，按返回不应退到别的桌面去。
        // 老人误触返回的概率很高，退出后看到陌生的系统桌面会找不到回来的路，
        // 所以这里直接吞掉这个手势，只给一句提示。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(
                    this@HomeActivity, R.string.home_back_blocked, Toast.LENGTH_SHORT
                ).show()
            }
        })

        CrashLogger.trace("HomeActivity.onCreate 结束")
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
            val placeholder = findViewById<ImageView>(placeholderIds[i])
            val contact = LocalContacts.get(this, i)
            if (contact == null || contact.name.isEmpty()) {
                tv.text = getString(R.string.contact_add)
                // 无联系人：隐藏铺满的背景层，只显示居中的小人像 + 姓名
                img.setImageDrawable(null)
                img.visibility = View.INVISIBLE
                placeholder.visibility = View.VISIBLE
                phones[i] = null
            } else {
                tv.text = contact.name
                val bmp: Bitmap? = LocalContacts.loadAvatar(this, i)
                if (bmp != null) {
                    // 真实头像：铺满头像区，隐藏占位小人像
                    img.imageTintList = null
                    img.setImageBitmap(bmp)
                    img.visibility = View.VISIBLE
                    placeholder.visibility = View.GONE
                } else {
                    img.setImageDrawable(null)
                    img.visibility = View.INVISIBLE
                    placeholder.visibility = View.VISIBLE
                }
                phones[i] = contact.phone
            }
        }
    }

    /** 更新电量显示：低于 20% 用优雅的 Material 红提示，否则用桌面同色系的中性正常色 */
    private fun updateBattery(pct: Int) {
        if (pct < 0) {
            tvBattery.text = "--"
            return
        }
        tvBattery.text = pct.toString()
        val low = pct < 20
        val fg = ContextCompat.getColor(
            this, if (low) R.color.colorBatteryLow else R.color.colorBatteryNormalFg
        )
        val bg = ContextCompat.getColor(
            this, if (low) R.color.colorBatteryLowBg else R.color.colorBatteryNormalBg
        )
        cardBattery.setCardBackgroundColor(bg)
        tvBattery.setTextColor(fg)
        tvBatteryLabel.setTextColor(fg)
    }

    private fun call(number: String) {
        if (number.isEmpty()) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingCallNumber = number
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL_PHONE
            )
            return
        }
        TelecomHelper.placeCall(this, number)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CALL_PHONE) {
            val number = pendingCallNumber
            pendingCallNumber = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                && !number.isNullOrEmpty()
            ) {
                // 用户刚授予权限，立即发起拨号，无需再次点击
                TelecomHelper.placeCall(this, number)
            } else if (!number.isNullOrEmpty()) {
                Toast.makeText(this, R.string.call_perm_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateClock() {
        val cal = Calendar.getInstance()
        val now = Date()
        tvTime.text = SimpleDateFormat("hh:mm", Locale.CHINA).format(now)
        tvAmpm.text = SimpleDateFormat("a", Locale.CHINA).format(now)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        tvDate.text = getString(
            R.string.home_date, m, d, weekdays[cal.get(Calendar.DAY_OF_WEEK) - 1]
        )
        tvLunar.text = LunarUtil.getLunar(now)
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIF
            )
        }
    }

    override fun onResume() {
        super.onResume()
        CrashLogger.trace("HomeActivity.onResume 开始")
        updateClock()
        refreshContacts()
        handler.post(clockRunnable)
        registerReceiver(
            batteryReceiver,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        )
        // 走到这里说明桌面已完整显示，标记本次启动成功
        CrashLogger.markSessionReady()
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
