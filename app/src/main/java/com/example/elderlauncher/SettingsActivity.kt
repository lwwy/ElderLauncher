package com.example.elderlauncher

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val swHourly = findViewById<Switch>(R.id.swHourly)
        val swIncoming = findViewById<Switch>(R.id.swIncoming)
        val swLow = findViewById<Switch>(R.id.swLow)
        val swSms = findViewById<Switch>(R.id.swSms)

        swHourly.isChecked = Prefs.enabled(this, Prefs.ANN_HOURLY)
        swIncoming.isChecked = Prefs.enabled(this, Prefs.ANN_INCOMING)
        swLow.isChecked = Prefs.enabled(this, Prefs.ANN_LOW)
        swSms.isChecked = Prefs.enabled(this, Prefs.ANN_SMS)

        swHourly.setOnCheckedChangeListener { _, v -> Prefs.set(this, Prefs.ANN_HOURLY, v) }
        swIncoming.setOnCheckedChangeListener { _, v -> Prefs.set(this, Prefs.ANN_INCOMING, v) }
        swLow.setOnCheckedChangeListener { _, v -> Prefs.set(this, Prefs.ANN_LOW, v) }
        swSms.setOnCheckedChangeListener { _, v -> Prefs.set(this, Prefs.ANN_SMS, v) }

        findViewById<Button>(R.id.btnDefault).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }
}
