package com.example.elderlauncher.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.elderlauncher.R
import com.example.elderlauncher.core.CrashLogger
import com.example.elderlauncher.core.Diagnostics
import com.example.elderlauncher.core.TtsManager
import com.example.elderlauncher.data.LocalContacts
import com.example.elderlauncher.data.Prefs
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 来电 / 低电量 播报
        val swIncoming = findViewById<SwitchMaterial>(R.id.swIncoming)
        val swLow = findViewById<SwitchMaterial>(R.id.swLow)
        swIncoming.isChecked = Prefs.enabled(this, Prefs.ANN_INCOMING)
        swLow.isChecked = Prefs.enabled(this, Prefs.ANN_LOW)
        swIncoming.setOnCheckedChangeListener { _, v -> Prefs.set(this, Prefs.ANN_INCOMING, v) }
        swLow.setOnCheckedChangeListener { _, v -> Prefs.set(this, Prefs.ANN_LOW, v) }

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

        // 设为默认桌面
        findViewById<MaterialButton>(R.id.btnDefault).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
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

    /** 取消老人桌面的默认桌面设置，回到系统桌面 */
    private fun restoreSystemHome() {
        try {
            // 清除本应用作为 HOME 的首选记录（对绝大多数 ROM 生效）
            packageManager.clearPackagePreferredActivities(packageName)
            CrashLogger.trace("已清除本应用的默认桌面首选记录")
        } catch (e: Exception) {
            CrashLogger.trace("清除默认桌面首选失败：${e.message}")
        }

        val stillDefault = try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            resolved?.activityInfo?.packageName == packageName
        } catch (e: Exception) {
            false
        }

        if (stillDefault) {
            // 部分 ROM（含 HyperOS）用角色管理默认桌面，App 无法自行放弃角色，只能引导用户改
            Toast.makeText(this, R.string.restore_system_home_failed, Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (_: Exception) {
                }
            }
        } else {
            Toast.makeText(this, R.string.restore_system_home_ok, Toast.LENGTH_LONG).show()
            // 直接打开系统桌面，让用户立刻看到效果
            try {
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                CrashLogger.trace("打开系统桌面失败：${e.message}")
            }
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

    /** 每次回到设置页都重建联系人列表，确保编辑/保存后实时刷新头像与姓名 */
    override fun onResume() {
        super.onResume()
        buildContactRows()
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
