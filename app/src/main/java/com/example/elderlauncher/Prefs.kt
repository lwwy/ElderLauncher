package com.example.elderlauncher

import android.content.Context
import android.content.SharedPreferences

/** 播报开关的持久化存储 */
object Prefs {
    private const val NAME = "elder_prefs"

    const val ANN_INCOMING = "ann_incoming"   // 来电播报
    const val ANN_LOW = "ann_lowbattery"      // 低电量播报

    const val AUTO_ANSWER = "auto_answer"          // 无障碍：自动接听
    const val AUTO_ANSWER_DELAY = "auto_answer_delay" // 自动接听延迟（秒）

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun enabled(ctx: Context, key: String, def: Boolean = true): Boolean =
        sp(ctx).getBoolean(key, def)

    fun set(ctx: Context, key: String, value: Boolean) =
        sp(ctx).edit().putBoolean(key, value).apply()

    // 整型偏好（自动接听延迟等）
    fun getInt(ctx: Context, key: String, def: Int): Int = sp(ctx).getInt(key, def)
    fun setInt(ctx: Context, key: String, value: Int) =
        sp(ctx).edit().putInt(key, value).apply()

    // 无障碍：自动接听
    fun autoAnswer(ctx: Context): Boolean = enabled(ctx, AUTO_ANSWER, false)
    fun autoAnswerDelay(ctx: Context): Int = getInt(ctx, AUTO_ANSWER_DELAY, 5)

    // 首页 4 个常用联系人（收藏），存储格式 "name|number"，空串表示未设置
    private val FAV_KEYS = arrayOf("fav_0", "fav_1", "fav_2", "fav_3")

    fun getFavorite(ctx: Context, index: Int): String {
        val key = FAV_KEYS.getOrNull(index) ?: return ""
        return sp(ctx).getString(key, "") ?: ""
    }

    fun setFavorite(ctx: Context, index: Int, value: String) {
        if (index in FAV_KEYS.indices) sp(ctx).edit().putString(FAV_KEYS[index], value).apply()
    }

    fun clearFavorite(ctx: Context, index: Int) {
        if (index in FAV_KEYS.indices) sp(ctx).edit().putString(FAV_KEYS[index], "").apply()
    }
}
