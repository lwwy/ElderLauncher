package com.example.elderlauncher.phone

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

/**
 * 通讯录辅助：仅用于在来电时反查对方姓名（不出网）。
 *
 * 早期实现是 query(Phone.CONTENT_URI) 把整本通讯录读进游标再逐行做字符串比对，
 * 通讯录有几百人时一次来电就要遍历几百行——而这发生在响铃瞬间的主线程上。
 * 现在改用系统提供的 PhoneLookup.CONTENT_FILTER_URI，由系统按号码索引直接命中。
 */
object ContactsHelper {

    /** 按来电号码反查通讯录中的姓名；查不到、无权限或异常时返回 null */
    fun lookupName(context: Context, number: String): String? {
        if (number.isBlank()) return null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
        )
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotEmpty() } else null
            }
        } catch (e: Exception) {
            Log.e("ContactsHelper", "lookup failed", e)
            null
        }
    }
}
