package com.example.elderlauncher.data

import android.content.Context
import android.content.SharedPreferences

/** 应用偏好（播报开关等）的持久化存储 */
object Prefs {
    private const val NAME = "elder_prefs"

    const val ANN_INCOMING = "ann_incoming"   // 来电播报
    const val ANN_LOW = "ann_lowbattery"      // 低电量播报
    const val HIDE_HOME_SETTINGS = "hide_home_settings" // 隐藏首页右上角设置入口（默认 false=显示）
    const val LOW_TONE_NAME = "low_tone_name" // 低电量自定义提示音的原始文件名（null=用系统语音）

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun enabled(ctx: Context, key: String, def: Boolean = true): Boolean =
        sp(ctx).getBoolean(key, def)

    fun set(ctx: Context, key: String, value: Boolean) =
        sp(ctx).edit().putBoolean(key, value).apply()

    fun getString(ctx: Context, key: String): String? = sp(ctx).getString(key, null)

    fun setString(ctx: Context, key: String, value: String?) =
        sp(ctx).edit().putString(key, value).apply()
}
