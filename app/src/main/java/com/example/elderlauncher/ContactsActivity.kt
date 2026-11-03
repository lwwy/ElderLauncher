package com.example.elderlauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class ContactsActivity : AppCompatActivity() {

    private val list = ArrayList<String>() // "name|number"
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        findViewById<TextView>(R.id.tvTitle).text = getString(R.string.contacts_title)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvEmpty.text = getString(R.string.contacts_empty)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, list)
        val lv = findViewById<ListView>(R.id.listView)
        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, pos, _ ->
            val num = list[pos].substringAfter("|")
            call(num)
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            load()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), 2)
        }
    }

    private fun load() {
        list.clear()
        ContactsHelper.listContacts(this).forEach { (name, num) -> list.add("$name|$num") }
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun call(num: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 1)
            return
        }
        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$num")))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            load()
        }
    }
}
