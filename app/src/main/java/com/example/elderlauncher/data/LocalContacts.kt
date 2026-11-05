package com.example.elderlauncher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import org.json.JSONObject

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
        removeRecord(ctx, i)
        avatarFile(ctx, i).delete()
        if (i in 0 until COUNT) avatarCache[i] = null
    }

    private fun removeRecord(ctx: Context, i: Int) {
        sp(ctx).edit().remove(key(i)).apply()
    }

    /**
     * 交换两个槽位（联系人 + 头像），用于设置页的排序。
     * 桌面按槽位 0/1/2 顺序渲染，交换后回到桌面即为新顺序。
     */
    fun swap(ctx: Context, a: Int, b: Int) {
        if (a == b) return
        val ca = get(ctx, a)
        val cb = get(ctx, b)
        swapAvatar(ctx, avatarFile(ctx, a), avatarFile(ctx, b))
        if (cb != null) set(ctx, a, cb) else removeRecord(ctx, a)
        if (ca != null) set(ctx, b, ca) else removeRecord(ctx, b)
        // 头像文件已互换，缓存的位图对应关系失效
        invalidateAvatarCache()
    }

    private fun swapAvatar(ctx: Context, fa: File, fb: File) {
        val tmp = File(ctx.cacheDir, "swap_avatar.tmp")
        val hasA = fa.exists()
        val hasB = fb.exists()
        try {
            if (hasA) fa.copyTo(tmp, overwrite = true)
            if (hasB) fb.copyTo(fa, overwrite = true) else fa.delete()
            if (hasA) tmp.copyTo(fb, overwrite = true) else fb.delete()
        } catch (_: Exception) {
            // 头像交换失败不影响姓名/电话的顺序
        } finally {
            tmp.delete()
        }
    }

    fun avatarFile(ctx: Context, i: Int): File {
        val dir = File(ctx.filesDir, "avatars")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "contact_$i.jpg")
    }

    /**
     * 头像解码结果的内存缓存（按槽位）。
     * 桌面每次 onResume（从设置页返回、通话结束、亮屏）都要重新贴 3 张头像，
     * 每次都从磁盘解码 512px JPEG 会在主线程上白等几十毫秒，桌面可能掉一帧。
     * 这里缓存解码后的位图，只在上传/删除/交换头像时失效。
     */
    private val avatarCache = arrayOfNulls<Bitmap>(COUNT)

    /** 清除头像缓存（不回收位图，交给 GC，避免界面上还在显示时被 recycle） */
    fun invalidateAvatarCache() {
        avatarCache.fill(null)
    }

    fun loadAvatar(ctx: Context, i: Int): Bitmap? {
        if (i < 0 || i >= COUNT) return null
        avatarCache[i]?.let { return it }
        val f = avatarFile(ctx, i)
        if (!f.exists()) return null
        val bmp = try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (_: Exception) {
            null
        }
        if (bmp != null) avatarCache[i] = bmp
        return bmp
    }

    /** 保存头像位图到该联系人固定路径（JPEG 压缩） */
    fun saveAvatar(ctx: Context, i: Int, bmp: Bitmap) {
        avatarFile(ctx, i).outputStream().use {
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, it)
        }
        avatarCache[i] = null
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
