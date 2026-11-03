package com.example.elderlauncher

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
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

        // 无障碍：自动接听
        val swAuto = findViewById<SwitchMaterial>(R.id.swAutoAnswer)
        val delayGroup = findViewById<MaterialButtonToggleGroup>(R.id.delayGroup)
        findViewById<MaterialButton>(R.id.btnDelay3).text = "3 秒"
        findViewById<MaterialButton>(R.id.btnDelay5).text = "5 秒"
        findViewById<MaterialButton>(R.id.btnDelay10).text = "10 秒"
        swAuto.isChecked = Prefs.autoAnswer(this)
        swAuto.setOnCheckedChangeListener { _, v -> Prefs.set(this, Prefs.AUTO_ANSWER, v) }
        when (Prefs.autoAnswerDelay(this)) {
            3 -> delayGroup.check(R.id.btnDelay3)
            10 -> delayGroup.check(R.id.btnDelay10)
            else -> delayGroup.check(R.id.btnDelay5)
        }
        delayGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val d = when (checkedId) {
                    R.id.btnDelay3 -> 3
                    R.id.btnDelay10 -> 10
                    else -> 5
                }
                Prefs.setInt(this, Prefs.AUTO_ANSWER_DELAY, d)
            }
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

        // 设为默认电话应用（跳系统默认应用设置页，由用户手动选择）
        findViewById<MaterialButton>(R.id.btnDefaultDialer).setOnClickListener {
            try {
                Toast.makeText(this, R.string.set_default_dialer_toast, Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, R.string.set_default_dialer_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 在设置页渲染 3 个常用联系人行（头像 + 姓名 + 编辑） */
    private fun buildContactRows() {
        val container = findViewById<LinearLayout>(R.id.favContainer)
        container.removeAllViews()
        for (i in 0 until LocalContacts.COUNT) {
            val contact = LocalContacts.get(this, i)
            val name = contact?.name?.takeIf { it.isNotEmpty() } ?: getString(R.string.fav_not_set)

            val avatarOverlay = com.google.android.material.shape.ShapeAppearanceModel
                .builder(this@SettingsActivity, R.style.AvatarThumb, 0)
                .build()
            val avatar = ShapeableImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(56, 56).apply { marginEnd = 14 }
                setShapeAppearanceOverlay(avatarOverlay)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setImageResource(R.drawable.ic_person)
                colorFilter = android.graphics.PorterDuffColorFilter(
                    ContextCompat.getColor(this@SettingsActivity, R.color.colorOnSurfaceVariant),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
                val bmp = LocalContacts.loadAvatar(this@SettingsActivity, i)
                if (bmp != null) {
                    clearColorFilter()
                    setImageBitmap(bmp)
                }
            }

            val tv = TextView(this).apply {
                text = String.format(getString(R.string.fav_slot), i + 1) + "：$name"
                textSize = 20f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.colorOnSurface))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val edit = MaterialButton(this).apply {
                text = getString(R.string.edit_contact)
                setOnClickListener {
                    startActivity(
                        Intent(this@SettingsActivity, ContactEditActivity::class.java)
                            .putExtra(ContactEditActivity.EXTRA_SLOT, i)
                    )
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 12 }
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 12 }
            }
            row.addView(avatar)
            row.addView(tv)
            row.addView(edit)
            container.addView(row)
        }
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
