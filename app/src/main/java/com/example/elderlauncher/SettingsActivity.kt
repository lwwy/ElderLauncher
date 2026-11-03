package com.example.elderlauncher

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {
    private var pendingSlot = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val swIncoming = findViewById<SwitchMaterial>(R.id.swIncoming)
        val swLow = findViewById<SwitchMaterial>(R.id.swLow)
        swIncoming.isChecked = Prefs.enabled(this, Prefs.ANN_INCOMING)
        swLow.isChecked = Prefs.enabled(this, Prefs.ANN_LOW)
        swIncoming.setOnCheckedChangeListener { _, v -> Prefs.set(this, Prefs.ANN_INCOMING, v) }
        swLow.setOnCheckedChangeListener { _, v -> Prefs.set(this, Prefs.ANN_LOW, v) }

        // 常用联系人管理
        buildFavRows()

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

    /** 在设置页渲染 4 个常用联系人槽位（选择 / 清除） */
    private fun buildFavRows() {
        val container = findViewById<LinearLayout>(R.id.favContainer)
        container.removeAllViews()
        for (i in 0 until 4) {
            val fav = Prefs.getFavorite(this, i)
            val name = if (fav.isEmpty()) getString(R.string.fav_not_set) else fav.substringBefore("|")

            val tv = TextView(this).apply {
                text = String.format(getString(R.string.fav_slot), i + 1) + "：$name"
                textSize = 20f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.colorOnSurface))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val choose = MaterialButton(this).apply {
                text = getString(R.string.fav_choose)
                setOnClickListener { pickContact(i) }
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
            row.addView(tv)
            row.addView(choose)

            if (fav.isNotEmpty()) {
                val clear = MaterialButton(this).apply {
                    text = getString(R.string.fav_clear)
                    setOnClickListener {
                        Prefs.clearFavorite(this@SettingsActivity, i)
                        buildFavRows()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = 12 }
                }
                row.addView(clear)
            }
            container.addView(row)
        }
    }

    private fun pickContact(slot: Int) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSlot = slot
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), 100)
            return
        }
        showPicker(slot)
    }

    private fun showPicker(slot: Int) {
        val contacts = ContactsHelper.listContacts(this)
        if (contacts.isEmpty()) {
            Toast.makeText(this, R.string.contacts_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val names = contacts.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.contact_pick_title)
            .setItems(names) { _, which ->
                val (name, number) = contacts[which]
                Prefs.setFavorite(this, slot, "$name|$number")
                buildFavRows()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            && pendingSlot >= 0
        ) {
            val slot = pendingSlot
            pendingSlot = -1
            showPicker(slot)
        }
    }
}
