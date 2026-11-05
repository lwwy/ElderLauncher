package com.example.elderlauncher.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * 来电播报所需的运行时权限，集中在这里，避免桌面 / 设置页各写一份而漏项。
 *
 * 关键点：只在 AndroidManifest.xml 里声明是不够的。
 * ACTION_PHONE_STATE_CHANGED 虽属于「隐式广播豁免」（允许静态注册、来电时按需拉起进程），
 * 但系统投递该广播时仍会校验 READ_PHONE_STATE；未运行时授予会直接 Permission Denial，
 * 静态接收器根本收不到，来电播报也就不会响。
 * READ_CONTACTS 用于来电时反查通讯录姓名，缺了只会念「未知号码」。
 */
object PhonePermissions {

    val REQUIRED: Array<String> = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CONTACTS
    )

    /** 返回仍未授予的权限；全部已授予时返回空数组 */
    fun missing(context: Context): Array<String> = REQUIRED.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()

    fun allGranted(context: Context): Boolean = missing(context).isEmpty()
}
