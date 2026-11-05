package com.example.elderlauncher.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * 把文本写入公共「下载」目录，方便用文件管理器直接取出。
 *
 * Android 10+ 走 MediaStore，不需要任何存储权限；
 * 更低版本保持原有行为（不导出，返回 false），避免额外申请 WRITE_EXTERNAL_STORAGE。
 */
internal object FileExporter {

    fun writeToDownloads(ctx: Context, name: String, text: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                false
            } else {
                val os = ctx.contentResolver.openOutputStream(uri)
                if (os == null) {
                    false
                } else {
                    os.use { it.write(text.toByteArray()) }
                    true
                }
            }
        } else {
            false
        }
    } catch (_: Throwable) {
        false
    }
}
