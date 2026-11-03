package com.example.elderlauncher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

class HomeActivity : AppCompatActivity() {

    private lateinit var tvTime: TextView
    private lateinit var tvAmPm: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvLunar: TextView
    private lateinit var tvBattery: TextView

    private val cardIds = intArrayOf(
        R.id.cardContact1, R.id.cardContact2, R.id.cardContact3, R.id.cardContact4
    )
    private val nameIds = intArrayOf(
        R.id.tvName1, R.id.tvName2, R.id.tvName3, R.id.tvName4
    )
    private var pendingSlot = -1

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

        // 2x2 收藏联系人卡片
        for (i in cardIds.indices) {
            val card = findViewById<View>(cardIds[i])
            card.setOnClickListener { onCardClick(i) }
            card.setOnLongClickListener { onCardLongClick(i); true }
        }
        // 底部加号 -> 选择联系人填入第一个空位
        findViewById<View>(R.id.btnAdd).setOnClickListener {
            val empty = (0 until cardIds.size).firstOrNull {
                Prefs.getFavorite(this, it).isEmpty()
            } ?: 0
            showPicker(empty)
        }

        updateClock()
    }

    private fun onCardClick(i: Int) {
        val fav = Prefs.getFavorite(this, i)
        if (fav.isEmpty()) {
            showPicker(i)
        } else {
            call(fav.substringAfter("|"))
        }
    }

    private fun onCardLongClick(i: Int) {
        if (Prefs.getFavorite(this, i).isNotEmpty()) {
            Prefs.clearFavorite(this, i)
            refreshContacts()
            Toast.makeText(this, R.string.fav_removed, Toast.LENGTH_SHORT).show()
        }
    }

    /** 应用内联系人选择弹窗，选定后写入指定收藏位（避免 singleTask 主页用 startActivityForResult 的兼容问题） */
    private fun showPicker(slot: Int) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSlot = slot
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), 3)
            return
        }
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
                refreshContacts()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshContacts() {
        for (i in nameIds.indices) {
            val fav = Prefs.getFavorite(this, i)
            val tv = findViewById<TextView>(nameIds[i])
            tv.text = if (fav.isEmpty()) getString(R.string.contact_add) else fav.substringBefore("|")
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
        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 3 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            && pendingSlot >= 0
        ) {
            val slot = pendingSlot
            pendingSlot = -1
            showPicker(slot)
        }
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
        refreshContacts()
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
