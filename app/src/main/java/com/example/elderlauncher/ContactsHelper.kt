package com.example.elderlauncher

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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

    /** 按号码加载联系人头像（圆形裁剪前的原始位图），无头像返回 null */
    fun getPhotoBitmap(context: Context, number: String): Bitmap? {
        val norm = normalize(number)
        if (norm.isEmpty()) return null
        return try {
            val lookup = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
                .appendPath(Uri.encode(number))
                .build()
            val id = context.contentResolver.query(
                lookup,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            } ?: return null
            val contactUri = ContentUris.withAppendedId(
                ContactsContract.Contacts.CONTENT_URI, id
            )
            val stream = ContactsContract.Contacts.openContactPhotoInputStream(
                context.contentResolver, contactUri, true
            )
            stream?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Log.e("ContactsHelper", "photo failed", e)
            null
        }
    }
}
