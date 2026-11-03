package com.example.elderlauncher

import android.content.Context
import android.provider.ContactsContract
import android.util.Log

/** 本地通讯录查询（均不出网） */
object ContactsHelper {
    private fun normalize(n: String): String = n.replace("[^0-9]".toRegex(), "")

    /** 返回通讯录中所有 (姓名, 号码) 列表 */
    fun listContacts(context: Context): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        try {
            context.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: ""
                    val num = c.getString(1) ?: ""
                    if (name.isNotEmpty() && num.isNotEmpty()) {
                        result.add(Pair(name, num))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ContactsHelper", "list failed", e)
        }
        return result
    }

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
