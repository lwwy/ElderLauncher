package com.example.elderlauncher

import android.content.Context
import android.content.SharedPreferences

/** 开关与拦截号码的持久化存储 */
object Prefs {
    private const val NAME = "elder_prefs"

    const val ANN_HOURLY = "ann_hourly"
    const val ANN_INCOMING = "ann_incoming"
    const val ANN_LOW = "ann_lowbattery"
    const val ANN_SMS = "ann_sms"
    const val BLOCKLIST = "blocklist"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun enabled(ctx: Context, key: String, def: Boolean = true): Boolean =
        sp(ctx).getBoolean(key, def)

    fun set(ctx: Context, key: String, value: Boolean) =
        sp(ctx).edit().putBoolean(key, value).apply()

    fun blockSet(ctx: Context): MutableSet<String> =
        HashSet(sp(ctx).getStringSet(BLOCKLIST, emptySet()))

    fun saveBlockSet(ctx: Context, set: Set<String>) =
        sp(ctx).edit().putStringSet(BLOCKLIST, set).apply()
}
