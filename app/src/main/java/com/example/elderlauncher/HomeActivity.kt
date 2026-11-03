package com.example.elderlauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts

class HomeActivity : AppCompatActivity() {

    private val neededPermissions: List<String>
        get() = listOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS
        )

    private val requestLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        TtsManager.init(this)
        requestPermissionsIfNeeded()
        setupUi()
        updateClock()
    }

    private fun requestPermissionsIfNeeded() {
        val toRequest = neededPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            requestLauncher.launch(toRequest.toTypedArray())
        }
    }

    private fun setupUi() {
        findViewById<Button>(R.id.btnDialer).setOnClickListener {
            startActivity(Intent(this, DialerActivity::class.java))
        }
        findViewById<Button>(R.id.btnContacts).setOnClickListener {
            startActivity(Intent(this, ContactsActivity::class.java))
        }
        findViewById<Button>(R.id.btnSms).setOnClickListener {
            startActivity(Intent(this, SmsActivity::class.java))
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun updateClock() {
        val tv = findViewById<TextView>(R.id.tvClock)
        tv.text = android.text.format.DateFormat.format("HH:mm", java.util.Date())
    }

    override fun onResume() {
        super.onResume()
        updateClock()
    }
}
