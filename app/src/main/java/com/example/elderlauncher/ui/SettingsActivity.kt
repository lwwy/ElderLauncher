package com.example.elderlauncher.ui

import android.content.ComponentName
import android.content.Intent
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.elderlauncher.R
import com.example.elderlauncher.core.CrashLogger
import com.example.elderlauncher.core.Diagnostics
import com.example.elderlauncher.core.HomeRole
import com.example.elderlauncher.core.TtsManager
import com.example.elderlauncher.data.LocalContacts
import com.example.elderlauncher.data.Prefs
import com.example.elderlauncher.phone.PhonePermissions
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    /** 进入设置页时的默认桌面状态，用于从系统设置返回后判断是否设置成功 */
    private var wasDefaultHome = false

    // 来电播报开关打开、或进入设置页发现权限缺失时补一次申请。
    // 桌面冷启动只申请一次，用户当时若点了拒绝，这里提供重新授权的机会。
    private val announcePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { !it }) {
            Toast.makeText(this, R.string.phone_perm_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 来电 / 低电量 播报
        val swIncoming = findViewById<SwitchMaterial>(R.id.swIncoming)
        val swLow = findViewById<SwitchMaterial>(R.id.swLow)
        swIncoming.isChecked = Prefs.enabled(this, Prefs.ANN_INCOMING)
        swLow.isChecked = Prefs.enabled(this, Prefs.ANN_LOW)
        swIncoming.setOnCheckedChangeListener { _, v ->
            Prefs.set(this, Prefs.ANN_INCOMING, v)
            // 主动打开来电播报时补齐权限（用户可能在冷启动时拒绝过）
            if (v) requestAnnouncePermissionsIfNeeded()
        }
        swLow.setOnCheckedChangeListener { _, v -> Prefs.set(this, Prefs.ANN_LOW, v) }

        // 来电播报已开启但权限未授予：进设置页再给一次申请机会，
        // 避免冷启动那一次被拒绝后功能永久失效、用户又不知道去哪开。
        if (swIncoming.isChecked) requestAnnouncePermissionsIfNeeded()

        // 桌面入口：隐藏/恢复首页右上角设置齿轮。
        // 开启后首页齿轮隐藏，但仍保留“连点时间区域 5 下进入设置”的隐藏入口。
        val swHideSettings = findViewById<SwitchMaterial>(R.id.swHideSettings)
        swHideSettings.isChecked = Prefs.enabled(this, Prefs.HIDE_HOME_SETTINGS, false)
        swHideSettings.setOnCheckedChangeListener { _, v ->
            Prefs.set(this, Prefs.HIDE_HOME_SETTINGS, v)
        }

        // 常用联系人（本地可编辑）
        buildContactRows()

        // 自启动权限 / 电池优化
        findViewById<MaterialButton>(R.id.btnAutostart).setOnClickListener { openAutostart() }
        findViewById<MaterialButton>(R.id.btnBattery).setOnClickListener { ignoreBattery() }

        // 设为默认桌面：稳定设置页入口 + 全程反馈，杜绝「点了没反应」
        wasDefaultHome = HomeRole.isDefaultHome(this)
        findViewById<MaterialButton>(R.id.btnDefault).setOnClickListener {
            requestDefaultHome()
        }

        // 恢复系统桌面：取消本应用的默认桌面，回到手机原来的桌面
        findViewById<MaterialButton>(R.id.btnRestoreHome).setOnClickListener {
            restoreSystemHome()
        }

        // 导出诊断日志：生成一份可读文本，直接分享给开发者排查
        findViewById<MaterialButton>(R.id.btnExportLog).setOnClickListener {
            exportDiagnosticLog()
        }

        // 播报测试：点一下就能听到实际效果，便于确认系统语音引擎是否正常
        findViewById<MaterialButton>(R.id.btnTestIncoming).setOnClickListener {
            testAnnounce(getString(R.string.ann_incoming, getString(R.string.test_caller_name)))
        }
        findViewById<MaterialButton>(R.id.btnTestLow).setOnClickListener {
            testAnnounce(getString(R.string.ann_low_battery))
        }
    }

    /** 缺权限时才申请；已授予则静默跳过。返回是否实际发起，便于单元测试驱动。 */
    internal fun requestAnnouncePermissionsIfNeeded(): Boolean {
        val missing = PhonePermissions.missing(this)
        if (missing.isEmpty()) return false
        announcePermissionLauncher.launch(missing)
        return true
    }

    /**
     * 「设为默认桌面」主流程。
     *
     * 历史：v1.0.9 ~ v1.2.2 一直直接跳 ACTION_HOME_SETTINGS，连续 5 个版本工作正常；
     * v1.2.3 改成优先 RoleManager 角色申请后，在 HyperOS 上因 ROM 静默拦截变成
     * 「点了没反应」（异常被空 catch 吞掉、兜底又退到系统设置首页）。
     *
     * 现在：
     *  1. 已是默认桌面 → 明确提示，不再发起申请；
     *  2. 每次点击都打开与老版本相同的系统「默认主屏幕」设置页（点一次就能选）；
     *  3. 该页不可解析时才顺延到应用详情页，并把实际分支写日志；
     *  4. 不再「二次点击换路」—— HyperOS 的「默认应用」页里没有桌面选项，
     *     换过去只会让用户更找不到；重复点击本按钮就是重新打开同一页重试；
     *  5. 启动成功给可操作引导；所有入口都不可用也明确提示手动路径，绝不静默失败。
     *
     * 参数可注入，便于单元测试驱动各分支。返回实际启动的分支 key，未启动返回 null。
     */
    internal fun requestDefaultHome(
        isDefault: Boolean = HomeRole.isDefaultHome(this),
        options: List<HomeRole.HomeSetupOption> = HomeRole.defaultHomeSetupOptions(this)
    ): String? {
        if (isDefault) {
            Toast.makeText(this, R.string.default_home_already, Toast.LENGTH_SHORT).show()
            CrashLogger.trace("设为默认桌面：当前已是默认桌面，无需操作")
            return null
        }

        // 每次都从首选开始：能勾选桌面的只有「默认主屏幕应用」页，重复点击即重试该页
        for (opt in options) {
            try {
                startActivity(opt.intent)
                CrashLogger.trace("设为默认桌面：已启动 ${opt.key} 分支")
                Toast.makeText(this, R.string.default_home_guide, Toast.LENGTH_LONG).show()
                return opt.key
            } catch (e: Exception) {
                CrashLogger.trace(
                    "设为默认桌面：${opt.key} 启动失败 ${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }

        Toast.makeText(this, R.string.default_home_manual, Toast.LENGTH_LONG).show()
        CrashLogger.trace("设为默认桌面：所有候选入口均不可用，已提示手动设置路径")
        return null
    }

    /**
     * 从系统设置返回时刷新默认桌面状态：由「非默认」变为「默认」时给出成功反馈。
     * 显式传参便于单元测试直接驱动，不依赖测试环境的 HOME 解析结果。
     */
    internal fun onDefaultHomeStateChanged(nowDefault: Boolean): Boolean {
        val becameDefault = !wasDefaultHome && nowDefault
        if (becameDefault) {
            Toast.makeText(this, R.string.default_home_success, Toast.LENGTH_LONG).show()
            CrashLogger.trace("设为默认桌面成功：检测到默认桌面角色已生效")
        }
        wasDefaultHome = nowDefault
        return becameDefault
    }

    /** 生成诊断日志并唤起系统分享面板，用户可直接发给开发者 */
    private fun exportDiagnosticLog() {
        Toast.makeText(this, R.string.export_log_doing, Toast.LENGTH_SHORT).show()
        CrashLogger.trace("用户手动导出诊断日志")
        val file = Diagnostics.exportDiagnostic(this)
        if (file == null) {
            Toast.makeText(this, R.string.export_log_failed, Toast.LENGTH_LONG).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_log))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.export_log_chooser)))
        } catch (e: Exception) {
            // 分享不可用时至少告诉用户文件在哪（同时已落一份到「下载」目录）
            CrashLogger.trace("分享诊断日志失败：${e.message}")
            Toast.makeText(
                this,
                getString(R.string.export_log_saved, file.absolutePath),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * 恢复系统桌面（改为引导式，不再尝试自动取消）。
     *
     * Android 10 (API 29) 起默认桌面由 RoleManager.ROLE_HOME 独占管理：
     *  - `clearPackagePreferredActivities()` 已弃用，官方明确「不再做任何事」，
     *    之前那行调用实际上从未生效，真正让状态改变的是用户去系统设置手动改；
     *  - 应用**无法自行放弃**已持有的 HOME 角色，只能由用户在系统设置里切换。
     *
     * 同时**不再主动发 ACTION_MAIN + CATEGORY_HOME intent**：本应用仍声明 HOME，
     * 该 intent 可能被解析回自己，导致切回系统桌面时界面闪回、Activity 反复重建
     * （诊断日志里两次「清除首选记录」之后都紧跟着额外的 onCreate）。
     *
     * 声明为 internal 便于单元测试直接驱动验证「不再发 HOME intent」。
     */
    internal fun restoreSystemHome(isDefaultHome: Boolean = HomeRole.isDefaultHome(this)) {
        if (isDefaultHome) {
            Toast.makeText(this, R.string.restore_system_home_guide, Toast.LENGTH_LONG).show()
            try {
                startActivity(HomeRole.homeSettingsIntent())
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (_: Exception) {
                }
            }
            CrashLogger.trace("恢复系统桌面：当前仍是默认，已引导至系统设置手动切换")
        } else {
            Toast.makeText(
                this, R.string.restore_system_home_already, Toast.LENGTH_SHORT
            ).show()
            CrashLogger.trace("恢复系统桌面：当前已不是默认，无需操作（未发 HOME intent）")
        }
    }

    /**
     * 播放一次示例播报。
     * 注意：TTS 初始化是异步的，HyperOS 等 ROM 首次冷启动可能耗时 3~5 秒，
     * 过早判定会误报「未检测到语音引擎」，所以这里给 5 秒宽限，并且失败时
     * 给出可操作的对话框（去系统设置 / 重试），而不是一句无从下手的 Toast。
     */
    private fun testAnnounce(text: String) {
        if (!TtsManager.hasEngine(this)) {
            showTtsDialog(getString(R.string.tts_no_engine), text)
            return
        }
        TtsManager.speakNow(this, text)
        Toast.makeText(this, R.string.test_playing, Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({
            if (!TtsManager.isReady()) {
                val msg = if (TtsManager.langMissing) {
                    getString(R.string.tts_no_lang)
                } else {
                    getString(R.string.tts_unavailable) +
                        (TtsManager.lastError?.let { "\n\n($it)" } ?: "")
                }
                showTtsDialog(msg, text)
            }
        }, 5000)
    }

    private fun showTtsDialog(message: String, retryText: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(R.string.tts_failed_title)
            .setMessage(message)
            .setPositiveButton(R.string.tts_go_settings) { _, _ -> openTtsSettings() }
            .setNeutralButton(R.string.tts_retry) { _, _ ->
                // 释放后重新初始化，避免上次 ERROR 后永久不可用
                TtsManager.shutdown()
                Toast.makeText(this, R.string.tts_retrying, Toast.LENGTH_SHORT).show()
                testAnnounce(retryText)
            }
            .setNegativeButton(R.string.tts_cancel, null)
            .show()
    }

    private fun openTtsSettings() {
        val candidates = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, R.string.tts_unavailable, Toast.LENGTH_LONG).show()
    }

    /** 交换联系人槽位（桌面按槽位顺序渲染，返回桌面即生效） */
    private fun moveContact(from: Int, to: Int) {
        if (to < 0 || to >= LocalContacts.COUNT) return
        LocalContacts.swap(this, from, to)
        buildContactRows()
    }

    /** 每次回到设置页都重建联系人列表，并检测默认桌面是否设置成功 */
    override fun onResume() {
        super.onResume()
        buildContactRows()
        onDefaultHomeStateChanged(HomeRole.isDefaultHome(this))
    }

    /** 在设置页渲染 3 个常用联系人行（头像 + 名称 + 排序 + 编辑） */
    private fun buildContactRows() {
        val container = findViewById<LinearLayout>(R.id.favContainer)
        container.removeAllViews()
        for (i in 0 until LocalContacts.COUNT) {
            val row = layoutInflater.inflate(R.layout.item_contact_row, container, false)
            val contact = LocalContacts.get(this, i)
            // 名字标签只显示名字本身（没有就显示“未添加”），避免“联系人N：名字”折行挤占头像空间
            val name = contact?.name?.takeIf { it.isNotEmpty() } ?: getString(R.string.fav_not_set)

            row.findViewById<TextView>(R.id.tvContactLabel).text = name

            val avatar = row.findViewById<ShapeableImageView>(R.id.imgContactAvatar)
            val bmp = LocalContacts.loadAvatar(this, i)
            if (bmp != null) {
                avatar.clearColorFilter()
                avatar.setImageBitmap(bmp)
            } else {
                avatar.setImageResource(R.drawable.ic_person)
                avatar.setColorFilter(
                    ContextCompat.getColor(this, R.color.colorOnSurfaceVariant),
                    PorterDuff.Mode.SRC_IN
                )
            }

            bindMoveButton(
                row.findViewById(R.id.btnMoveUp), R.string.move_up, i > 0
            ) { moveContact(i, i - 1) }
            bindMoveButton(
                row.findViewById(R.id.btnMoveDown), R.string.move_down, i < LocalContacts.COUNT - 1
            ) { moveContact(i, i + 1) }

            row.findViewById<MaterialButton>(R.id.btnEditContact).setOnClickListener {
                startActivity(
                    Intent(this, ContactEditActivity::class.java)
                        .putExtra(ContactEditActivity.EXTRA_SLOT, i)
                )
            }
            container.addView(row)
        }
    }

    /** 排序按钮的三个属性一起设：无障碍描述、可用状态、点击动作 */
    private fun bindMoveButton(
        btn: MaterialButton,
        labelRes: Int,
        enabled: Boolean,
        onClick: () -> Unit
    ) {
        btn.contentDescription = getString(labelRes)
        btn.isEnabled = enabled
        btn.setOnClickListener { onClick() }
    }

    private fun openAutostart() {
        val pm = packageManager
        val targets = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartActivity")
        )
        for (cn in targets) {
            val intent = Intent().setComponent(cn)
            if (intent.resolveActivity(pm) != null) {
                try {
                    startActivity(intent)
                    Toast.makeText(this, R.string.autostart_opened, Toast.LENGTH_LONG).show()
                    return
                } catch (_: Exception) {
                }
            }
        }
        // 回退：打开应用详情，手动开启自启动 / 后台运行
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
        }
        Toast.makeText(this, R.string.autostart_failed, Toast.LENGTH_LONG).show()
    }

    private fun ignoreBattery() {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, R.string.battery_done, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.autostart_failed, Toast.LENGTH_LONG).show()
        }
    }
}
