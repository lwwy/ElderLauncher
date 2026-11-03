package com.example.elderlauncher

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import android.view.inputmethod.EditorInfo
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class SmsActivity : AppCompatActivity() {

    private val threads = ArrayList<String>() // "address|body"
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        findViewById<TextView>(R.id.tvTitle).text = getString(R.string.sms_title)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvEmpty.text = getString(R.string.sms_empty)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, threads)
        val lv = findViewById<ListView>(R.id.listView)
        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, pos, _ ->
            val addr = threads[pos].substringBefore("|")
            openCompose(addr)
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            load()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS), 3)
        }
    }

    private fun load() {
        threads.clear()
        val uri = Uri.parse("content://sms/inbox")
        val proj = arrayOf("address", "body")
        contentResolver.query(uri, proj, null, null, "date DESC")?.use { c ->
            val seen = HashSet<String>()
            while (c.moveToNext()) {
                val addr = c.getString(0) ?: continue
                val body = c.getString(1) ?: ""
                if (!seen.contains(addr)) {
                    seen.add(addr)
                    threads.add("$addr|$body")
                }
            }
        }
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (threads.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openCompose(addr: String) {
        val et = EditText(this).apply {
            hint = getString(R.string.sms_hint)
            inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this)
            .setTitle(addr)
            .setView(et)
            .setPositiveButton(getString(R.string.sms_send)) { _, _ ->
                val text = et.text.toString().trim()
                if (text.isNotEmpty() && ActivityCompat.checkSelfPermission(
                        this, Manifest.permission.SEND_SMS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    try {
                        SmsManager.getDefault().sendTextMessage(addr, null, text, null, null)
                    } catch (e: Exception) {
                        // 发送失败静默忽略
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 3 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            load()
        }
    }
}
