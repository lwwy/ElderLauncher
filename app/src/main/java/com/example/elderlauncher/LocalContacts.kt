package com.example.elderlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONObject
import java.io.File

/**
 * 桌面常用联系人（本地可编辑）：姓名 + 电话 + 头像。
 * 头像以文件形式保存在应用私有目录 avatars/contact_N.jpg，路径固定，
 * 因此 Contact 本身只存姓名与电话；显示时按槽位读取头像文件。
 */
object LocalContacts {
    const val COUNT = 3

    data class Contact(val name: String, val phone: String)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("elder_prefs", Context.MODE_PRIVATE)
    private fun key(i: Int) = "contact_$i"

    fun get(ctx: Context, i: Int): Contact? {
        val s = sp(ctx).getString(key(i), null) ?: return null
        return try {
            val j = JSONObject(s)
            Contact(j.optString("name").orEmpty(), j.optString("phone").orEmpty())
        } catch (_: Exception) { null }
    }

    fun set(ctx: Context, i: Int, c: Contact) {
        val j = JSONObject().apply {
            put("name", c.name)
            put("phone", c.phone)
        }
        sp(ctx).edit().putString(key(i), j.toString()).apply()
    }

    fun delete(ctx: Context, i: Int) {
        sp(ctx).edit().remove(key(i)).apply()
        avatarFile(ctx, i).delete()
    }

    fun avatarFile(ctx: Context, i: Int): File {
        val dir = File(ctx.filesDir, "avatars")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "contact_$i.jpg")
    }

    fun hasAvatar(ctx: Context, i: Int): Boolean = avatarFile(ctx, i).exists()

    fun loadAvatar(ctx: Context, i: Int): Bitmap? {
        val f = avatarFile(ctx, i)
        if (!f.exists()) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    /** 保存头像位图到该联系人固定路径（JPEG 压缩） */
    fun saveAvatar(ctx: Context, i: Int, bmp: Bitmap) {
        avatarFile(ctx, i).outputStream().use {
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, it)
        }
    }

    /** 从相册/拍照得到的 URI 解码为缩放后的位图，避免大图 OOM */
    fun decodeUri(ctx: Context, uri: Uri, maxPx: Int = 512): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            opts.inSampleSize = computeSample(opts.outWidth, opts.outHeight, maxPx)
            opts.inJustDecodeBounds = false
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun computeSample(w: Int, h: Int, max: Int): Int {
        var s = 1
        while (w / s > max || h / s > max) s *= 2
        return s
    }
}
