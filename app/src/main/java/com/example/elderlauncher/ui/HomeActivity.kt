package com.example.elderlauncher.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.elderlauncher.R
import com.example.elderlauncher.core.CrashLogger
import com.example.elderlauncher.core.HomeRole
import com.example.elderlauncher.core.LowBatteryAnnouncer
import com.example.elderlauncher.core.LunarUtil
import com.example.elderlauncher.core.TtsManager
import com.example.elderlauncher.data.LocalContacts
import com.example.elderlauncher.data.Prefs
import com.example.elderlauncher.phone.CallThrottle
import com.example.elderlauncher.phone.PhonePermissions
import com.example.elderlauncher.phone.TelecomHelper
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

    // 右上角设置齿轮：声明为字段，供 onCreate 绑定点击、并在每次 onResume 重复应用显隐
    private lateinit var btnSettings: View

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

    // 用 ActivityResult API 替代已废弃的 requestPermissions / onRequestPermissionsResult：
    // 注册表的回调随 Activity 生命周期存活，天然抗配置变更（如旋转屏幕）导致的字段丢失。
    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onCallPermissionResult(granted) }

    // 来电播报权限：READ_PHONE_STATE + READ_CONTACTS。
    // 只声明在 Manifest 不够，系统投递 PHONE_STATE 广播时会校验运行时权限。
    // 拒绝时给一句可操作提示，设置页仍可再次申请。
    private val announcePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { !it }) {
            Toast.makeText(this, R.string.phone_perm_denied, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        // 用于 onSaveInstanceState 暂存待拨号码的 key
        private const val KEY_PENDING_CALL = "pending_call_number"
    }

    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 30000)
        }
    }

    // TTS 延迟预热抽成具名 Runnable：Activity 销毁时可从 Handler 里取消，
    // 避免实例被系统反复重建时残留的旧任务重复初始化语音引擎
    // （诊断日志曾出现 4 次 onCreate 对应 4 次 TTS 预热）。
    private val ttsWarmupRunnable = Runnable {
        TtsManager.init(applicationContext)
        CrashLogger.trace("TtsManager.init 完成（延迟预热）")
    }

    /**
     * 返回键拦截只在「本应用是默认桌面」时启用：那时退出会看到陌生的系统桌面，
     * 老人容易迷路，所以吞掉返回键只给一句提示。
     *
     * 非默认桌面时交还系统默认行为（Android 12+ 把 task 移后台），否则用户想退出
     * 只能按 Home 键，反而触发 HOME/LAUNCHER 两套 task 切换动画，观感断续。
     * 每次 onResume 按当前角色重新判定，切换默认桌面后即时生效。
     */
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            Toast.makeText(
                this@HomeActivity, R.string.home_back_blocked, Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            updateBattery(pct)
            // ACTION_BATTERY_CHANGED 是粘性广播，动态注册时会立即回调一次，
            // 之后电量每变化也会回调。交给统一状态机做「跨阈值只播一次」判定，
            // 替代在 Android 8+ 基本收不到的静态 ACTION_BATTERY_LOW。
            val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            LowBatteryAnnouncer.onBatteryChanged(this@HomeActivity, pct, plugged)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLogger.trace("HomeActivity.onCreate 开始")
        setContentView(R.layout.activity_home)
        CrashLogger.trace("setContentView 完成")

        // 还原“待拨号码”：若上一次“弹权限框→授权”过程中发生了屏幕旋转（Activity 重建），
        // 此处必须把暂存号码恢复回来，否则授权回调里拿不到号码、拨号不会自动重放。
        pendingCallNumber = savedInstanceState?.getString(KEY_PENDING_CALL)

        // 预热语音引擎：TTS 初始化要拉起引擎服务，放在首屏渲染之后再做，
        // 避免拖慢冷启动。只在 onCreate 排一次，onNewIntent 复用实例时不会重复排。
        handler.postDelayed(ttsWarmupRunnable, 3000)

        tvTime = findViewById(R.id.tvTime)
        tvDate = findViewById(R.id.tvDate)
        tvLunar = findViewById(R.id.tvLunar)
        tvAmpm = findViewById(R.id.tvAmpm)
        tvBattery = findViewById(R.id.tvBattery)
        tvBatteryLabel = findViewById(R.id.tvBatteryLabel)
        cardBattery = findViewById(R.id.cardBattery)
        CrashLogger.trace("findViewById 完成")

        // 设置入口：右上角齿轮（看护人使用）。点击监听只绑定一次，
        // 显隐交给 applySettingsVisibility()，在 onCreate 与每次 onResume 都重新读取开关。
        btnSettings = findViewById(R.id.btnSettings)
        btnSettings.setOnClickListener {
            startActivity(Intent(this@HomeActivity, SettingsActivity::class.java))
        }
        applySettingsVisibility()

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

        // 返回键：只在「是默认桌面」时拦截，非默认桌面交还系统默认行为。
        onBackPressedDispatcher.addCallback(this, backCallback)
        applyBackIntercept()

        // 来电播报权限只在冷启动申请一次：桌面是常驻 Activity，onCreate 不会因
        // 从设置页返回而重跑；旋转/重建也不重复弹，避免用户拒绝后反复打扰。
        // 用户若当时点了拒绝，进入设置页（或重新打开来电播报开关）仍可再次申请。
        ensureAnnouncePermissions(savedInstanceState == null)

        CrashLogger.trace("HomeActivity.onCreate 结束")
    }

    /**
     * 是否应拦截返回键：只有本应用当前是默认桌面时才拦截。供单元测试直接驱动。
     *
     * 这里刻意用 HomeRole 的「解析口径」而非合并后的 isDefaultHome() —— 返回键拦截是
     * 实际功能行为，v1.2.6 修判定假阴性时保持不变，避免影响已验证正常的桌面体验。
     */
    internal fun shouldInterceptBack(): Boolean = HomeRole.resolveHomeMatchesSelf(this)

    /**
     * 应用返回键拦截策略。默认按当前默认桌面角色判定；显式传参供单元测试驱动
     * 「非默认桌面必须关闭拦截」这条路径，不依赖测试环境的 HOME 解析结果。
     */
    internal fun applyBackIntercept(shouldIntercept: Boolean = shouldInterceptBack()) {
        backCallback.isEnabled = shouldIntercept
    }

    /** 当前返回键拦截是否启用（供单元测试验证 onCreate/onResume 真的应用了角色判定） */
    internal fun isBackInterceptEnabled(): Boolean = backCallback.isEnabled

    /**
     * singleTask 复用入口：从系统桌面点图标、或系统再次投递 HOME intent 时走这里，
     * 而不是重新 onCreate。这里绝不做 setContentView / 启动画面 / 权限申请 / TTS 预热，
     * 否则每复用一次就闪一次。只刷新随状态变化的内容。
     */
    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        CrashLogger.trace("HomeActivity.onNewIntent 复用实例，action=${intent.action}")
        applySettingsVisibility()
        applyBackIntercept()
    }

    /**
     * 确保来电播报所需的运行时权限已授予。
     *
     * 桌面只在冷启动（savedInstanceState == null）自动申请一次；从设置页返回、
     * 旋转屏幕等场景都不重复弹权限框。返回是否实际发起了申请，便于单元测试驱动。
     */
    internal fun ensureAnnouncePermissions(coldStart: Boolean): Boolean {
        if (!coldStart) return false
        val missing = PhonePermissions.missing(this)
        if (missing.isEmpty()) return false
        announcePermissionLauncher.launch(missing)
        return true
    }

    /**
     * 按“隐藏设置入口”开关刷新右上角齿轮的显隐。
     *
     * 桌面是常驻 Activity：从设置页返回只会走 onResume，不会重新 onCreate。
     * 因此显隐不能只在 onCreate 设置一次，必须在每次 onResume 重新读取开关，
     * 否则“设置里打开开关 → 返回桌面”时图标不会消失。
     * 隐藏后仍可用“连点时间区域 5 下”进入设置（见 timeBlock 监听），照顾人不受影响。
     */
    private fun applySettingsVisibility() {
        btnSettings.visibility =
            if (Prefs.enabled(this, Prefs.HIDE_HOME_SETTINGS, false)) View.GONE
            else View.VISIBLE
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
        // 防连点：窗口内的重复点击直接忽略，避免叠出两层系统拨号界面/重复弹权限框。
        // 授权后的自动重拨走 onCallPermissionResult，不经过这里，不受节流影响。
        if (!CallThrottle.tryAcquire()) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // 权限未授予：记下待拨号码并发起授权；授权结果由 callPermissionLauncher 回调处理
            pendingCallNumber = number
            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
            return
        }
        TelecomHelper.placeCall(this, number)
    }

    /**
     * 拨号权限授权结果的统一处理（由 callPermissionLauncher 回调调用，也可被单元测试直接驱动）。
     * 授权后自动用暂存的待拨号码真正发起拨号，无需用户再点一次；拒绝则提示且不拨。
     */
    internal fun onCallPermissionResult(granted: Boolean) {
        val number = pendingCallNumber
        pendingCallNumber = null
        if (granted && !number.isNullOrEmpty()) {
            TelecomHelper.placeCall(this, number)
        } else if (!number.isNullOrEmpty()) {
            Toast.makeText(this, R.string.call_perm_denied, Toast.LENGTH_SHORT).show()
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING_CALL, pendingCallNumber)
    }

    override fun onResume() {
        super.onResume()
        CrashLogger.trace("HomeActivity.onResume 开始")
        // 从设置页返回桌面只走 onResume，这里必须重新应用开关，隐藏/恢复才能即时生效
        applySettingsVisibility()
        // 默认桌面角色可能在系统设置里被改过，返回键拦截策略要跟着即时刷新
        applyBackIntercept()
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
        CrashLogger.trace("HomeActivity.onPause")
        handler.removeCallbacks(clockRunnable)
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onStop() {
        super.onStop()
        CrashLogger.trace("HomeActivity.onStop")
    }

    override fun onDestroy() {
        CrashLogger.trace("HomeActivity.onDestroy")
        // 实例销毁后不再执行残留的延迟任务，避免重复初始化、以及持有已销毁实例引用
        handler.removeCallbacks(ttsWarmupRunnable)
        handler.removeCallbacks(clockRunnable)
        super.onDestroy()
    }
}
