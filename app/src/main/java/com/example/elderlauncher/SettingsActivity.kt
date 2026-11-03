package com.example.elderlauncher

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.widget.Button
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val swIncoming = findViewById<Switch>(R.id.swIncoming)
        val swLow = findViewById<Switch>(R.id.swLow)
        swIncoming.isChecked = Prefs.enabled(this, Prefs.ANN_INCOMING)
        swLow.isChecked = Prefs.enabled(this, Prefs.ANN_LOW)
        swIncoming.setOnCheckedChangeListener { _, v -> Prefs.set(this, Prefs.ANN_INCOMING, v) }
        swLow.setOnCheckedChangeListener { _, v -> Prefs.set(this, Prefs.ANN_LOW, v) }

        findViewById<Button>(R.id.btnDefault).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        // Android 10+ 上，设为默认电话应用可保证来电大卡片与接听/挂断可靠生效
        findViewById<Button>(R.id.btnDefaultDialer).setOnClickListener {
            try {
                val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                    .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }
}
