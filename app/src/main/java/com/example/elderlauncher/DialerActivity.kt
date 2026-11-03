package com.example.elderlauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class DialerActivity : AppCompatActivity() {

    private var number = ""
    private lateinit var tvNumber: TextView
    private val keys = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dialer)
        tvNumber = findViewById(R.id.tvNumber)

        val grid = findViewById<GridView>(R.id.gridPad)
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = keys.size
            override fun getItem(i: Int) = keys[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convertView: View?, parent: ViewGroup?): View {
                val b = Button(this@DialerActivity)
                b.text = keys[i]
                b.textSize = 32f
                b.setOnClickListener { append(keys[i]) }
                return b
            }
        }

        findViewById<Button>(R.id.btnDelete).setOnClickListener {
            if (number.isNotEmpty()) {
                number = number.substring(0, number.length - 1)
                tvNumber.text = number
            }
        }
        findViewById<Button>(R.id.btnCall).setOnClickListener { call() }
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
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 1)
            return
        }
        val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        startActivity(i)
    }
}
