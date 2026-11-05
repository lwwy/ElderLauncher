package com.example.elderlauncher.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.elderlauncher.R
import com.example.elderlauncher.phone.TelecomHelper
import com.google.android.material.button.MaterialButton

class DialerActivity : AppCompatActivity() {

    private var number = ""
    private lateinit var tvNumber: TextView
    private val keys = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")

    companion object {
        // 用于 onSaveInstanceState 暂存拨号盘当前号码的 key
        private const val KEY_NUMBER = "dialer_number"
    }

    // 用 ActivityResult API 替代已废弃的 requestPermissions / onRequestPermissionsResult：
    // 注册表回调随 Activity 生命周期存活，天然抗配置变更（如旋转屏幕）导致的丢失。
    // 授权后自动重拨（number 由 onSaveInstanceState 恢复），避免“弹权限框→再点一次才拨”的割裂体验。
    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onCallPermissionResult(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dialer)
        number = savedInstanceState?.getString(KEY_NUMBER) ?: ""
        tvNumber = findViewById(R.id.tvNumber)
        tvNumber.text = number

        val grid = findViewById<GridView>(R.id.gridPad)
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = keys.size
            override fun getItem(i: Int) = keys[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convertView: View?, parent: ViewGroup?): View {
                // 复用 GridView 回收的按钮，避免每次滑动都新建 View
                val b = (convertView as? MaterialButton) ?: MaterialButton(this@DialerActivity).apply {
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.textKeypad))
                }
                b.text = keys[i]
                b.setOnClickListener { append(keys[i]) }
                return b
            }
        }

        findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener {
            if (number.isNotEmpty()) {
                number = number.substring(0, number.length - 1)
                tvNumber.text = number
            }
        }
        findViewById<MaterialButton>(R.id.btnCall).setOnClickListener { call() }
    }

    private fun append(s: String) {
        number += s
        tvNumber.text = number
    }

    private fun call() {
        if (number.isEmpty()) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // 权限未授予：发起授权；授权结果由 callPermissionLauncher 回调处理
            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
            return
        }
        TelecomHelper.placeCall(this, number)
    }

    /**
     * 拨号权限授权结果的统一处理（由 callPermissionLauncher 回调调用，也可被单元测试直接驱动）。
     * 授权后自动用当前号码重拨，无需用户再点一次；拒绝则提示且不拨。
     */
    internal fun onCallPermissionResult(granted: Boolean) {
        if (granted && number.isNotEmpty()) {
            TelecomHelper.placeCall(this, number)
        } else if (!granted && number.isNotEmpty()) {
            Toast.makeText(this, R.string.call_perm_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_NUMBER, number)
    }
}
