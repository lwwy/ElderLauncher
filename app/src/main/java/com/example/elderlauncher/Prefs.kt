package com.example.elderlauncher

import android.content.Context
import android.content.SharedPreferences

/** 播报开关的持久化存储 */
object Prefs {
    private const val NAME = "elder_prefs"

    const val ANN_INCOMING = "ann_incoming"   // 来电播报
    const val ANN_LOW = "ann_lowbattery"      // 低电量播报

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun enabled(ctx: Context, key: String, def: Boolean = true): Boolean =
        sp(ctx).getBoolean(key, def)

    fun set(ctx: Context, key: String, value: Boolean) =
        sp(ctx).edit().putBoolean(key, value).apply()
}
