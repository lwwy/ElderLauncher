package com.example.elderlauncher

import android.app.role.RoleManager
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
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val REQ_DIALER_ROLE = 1001
    }

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

        // 设为默认电话应用：API 29+ 直接弹系统角色选择框（需 DIAL 带 tel scheme 才有候选资格），
        // 其余情况回退到系统"默认应用"设置页由用户手动选择。
        findViewById<MaterialButton>(R.id.btnDefaultDialer).setOnClickListener {
            requestDialerRole()
        }
    }

    private fun isDefaultDialer(): Boolean = try {
        val tm = getSystemService(TELECOM_SERVICE) as android.telecom.TelecomManager
        tm.defaultDialerPackage == packageName
    } catch (e: Exception) {
        false
    }

    private fun requestDialerRole() {
        if (isDefaultDialer()) {
            Toast.makeText(this, R.string.set_default_dialer_already, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    startActivityForResult(
                        rm.createRequestRoleIntent(RoleManager.ROLE_DIALER),
                        REQ_DIALER_ROLE
                    )
                    return
                }
            }
        } catch (e: Exception) {
            CrashLogger.trace("请求拨号器角色失败: ${e.javaClass.name}: ${e.message}")
        }
        // 回退：打开系统默认应用设置页
        try {
            Toast.makeText(this, R.string.set_default_dialer_toast, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.set_default_dialer_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DIALER_ROLE) {
            val ok = isDefaultDialer()
            Toast.makeText(
                this,
                if (ok) R.string.set_default_dialer_ok else R.string.set_default_dialer_failed,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 每次回到设置页都重建联系人列表，确保编辑/保存后实时刷新头像与姓名 */
    override fun onResume() {
        super.onResume()
        buildContactRows()
    }

    /** 在设置页渲染 3 个常用联系人行（头像 + 姓名 + 编辑） */
    private fun buildContactRows() {
        val container = findViewById<LinearLayout>(R.id.favContainer)
        container.removeAllViews()
        for (i in 0 until LocalContacts.COUNT) {
            val contact = LocalContacts.get(this, i)
            val name = contact?.name?.takeIf { it.isNotEmpty() } ?: getString(R.string.fav_not_set)

            val avatar = ShapeableImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(56, 56).apply { marginEnd = 14 }
                shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel.builder()
                    .setAllCorners(
                        com.google.android.material.shape.CornerFamily.ROUNDED,
                        12 * resources.displayMetrics.density
                    )
                    .build()
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
