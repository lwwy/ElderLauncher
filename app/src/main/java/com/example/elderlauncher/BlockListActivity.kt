package com.example.elderlauncher

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class BlockListActivity : AppCompatActivity() {

    private val items = ArrayList<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_block)
        tvEmpty = findViewById(R.id.tvEmpty)
        val et = findViewById<EditText>(R.id.etNumber)

        findViewById<Button>(R.id.btnAdd).setOnClickListener {
            val n = et.text.toString().trim()
            if (n.isNotEmpty()) {
                val set = Prefs.blockSet(this)
                set.add(n)
                Prefs.saveBlockSet(this, set)
                refresh()
                et.text.clear()
            }
        }

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        val lv = findViewById<ListView>(R.id.listBlock)
        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, pos, _ ->
            AlertDialog.Builder(this)
                .setTitle("删除拦截号码？")
                .setMessage(items[pos])
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val set = Prefs.blockSet(this)
                    set.remove(items[pos])
                    Prefs.saveBlockSet(this, set)
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        refresh()
    }

    private fun refresh() {
        items.clear()
        items.addAll(Prefs.blockSet(this))
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }
}
