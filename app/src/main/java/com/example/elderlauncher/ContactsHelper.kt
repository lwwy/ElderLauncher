package com.example.elderlauncher

import android.content.Context
import android.provider.ContactsContract
import android.util.Log

/** 通讯录辅助：仅用于在来电时反查对方姓名（不出网） */
object ContactsHelper {
    private fun normalize(n: String): String = n.replace("[^0-9]".toRegex(), "")

    /** 按来电号码反查通讯录中的姓名；查不到或异常时返回 null */
    fun lookupName(context: Context, number: String): String? {
        val norm = normalize(number)
        if (norm.isEmpty()) return null
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        return try {
            context.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: ""
                    val num = normalize(c.getString(1) ?: "")
                    if (num.isNotEmpty() && (num.endsWith(norm) || norm.endsWith(num) || num == norm)) {
                        return@use name
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.e("ContactsHelper", "lookup failed", e)
            null
        }
    }
}
