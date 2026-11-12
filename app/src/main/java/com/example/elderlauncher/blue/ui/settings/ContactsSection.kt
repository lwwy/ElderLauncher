package com.example.elderlauncher.blue.ui.settings

import android.content.Intent
import android.graphics.PorterDuff
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.elderlauncher.blue.R
import com.example.elderlauncher.blue.data.LocalContacts
import com.example.elderlauncher.blue.ui.ContactEditActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView

/**
 * 设置页「常用联系人」分区：渲染 3 个槽位的联系人行（头像 + 名称 + 排序 + 编辑）。
 *
 * 从 [com.example.elderlauncher.blue.ui.SettingsActivity] 拆出的独立职责块，行为与原实现一致。
 * 每次回到设置页都会重建列表，保证从编辑页修改的姓名/头像即时显示。
 */
internal class ContactsSection(private val activity: AppCompatActivity) {

    /** 在设置页渲染 3 个常用联系人行（头像 + 名称 + 排序 + 编辑） */
    fun buildRows() {
        val container = activity.findViewById<LinearLayout>(R.id.favContainer)
        container.removeAllViews()
        for (i in 0 until LocalContacts.COUNT) {
            val row = activity.layoutInflater.inflate(R.layout.item_contact_row, container, false)
            val contact = LocalContacts.get(activity, i)
            // 名字标签只显示名字本身（没有就显示"未添加"），避免折行挤占头像空间
            val name = contact?.name?.takeIf { it.isNotEmpty() }
                ?: activity.getString(R.string.fav_not_set)

            row.findViewById<TextView>(R.id.tvContactLabel).text = name

            val avatar = row.findViewById<ShapeableImageView>(R.id.imgContactAvatar)
            val bmp = LocalContacts.loadAvatar(activity, i)
            if (bmp != null) {
                avatar.clearColorFilter()
                avatar.setImageBitmap(bmp)
            } else {
                avatar.setImageResource(R.drawable.ic_person)
                avatar.setColorFilter(
                    ContextCompat.getColor(activity, R.color.colorOnSurfaceVariant),
                    PorterDuff.Mode.SRC_IN
                )
            }

            bindMoveButton(
                row.findViewById(R.id.btnMoveUp), R.string.move_up, i > 0
            ) { moveContact(i, i - 1) }
            bindMoveButton(
                row.findViewById(R.id.btnMoveDown), R.string.move_down, i < LocalContacts.COUNT - 1
            ) { moveContact(i, i + 1) }

            row.findViewById<MaterialButton>(R.id.btnEditContact).setOnClickListener {
                activity.startActivity(
                    Intent(activity, ContactEditActivity::class.java)
                        .putExtra(ContactEditActivity.EXTRA_SLOT, i)
                )
            }
            container.addView(row)
        }
    }

    /** 交换联系人槽位（桌面按槽位顺序渲染，返回桌面即生效） */
    private fun moveContact(from: Int, to: Int) {
        if (to < 0 || to >= LocalContacts.COUNT) return
        LocalContacts.swap(activity, from, to)
        buildRows()
    }

    /** 排序按钮的三个属性一起设：无障碍描述、可用状态、点击动作 */
    private fun bindMoveButton(
        btn: MaterialButton,
        labelRes: Int,
        enabled: Boolean,
        onClick: () -> Unit
    ) {
        btn.contentDescription = activity.getString(labelRes)
        btn.isEnabled = enabled
        btn.setOnClickListener { onClick() }
    }
}
