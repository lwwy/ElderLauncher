package com.example.elderlauncher.core

import android.content.Context
import com.example.elderlauncher.R
import com.example.elderlauncher.data.Prefs

/**
 * 低电量播报的统一判定与去重。
 *
 * 背景：ACTION_BATTERY_LOW 不在 Android 8.0+ 的「隐式广播豁免清单」内，
 * Manifest 静态注册的接收器在多数 ROM 上会被系统拦截（Background execution not allowed），
 * 因此不能只靠静态广播触发播报。
 *
 * 两条触发路径共用这里的去重状态，避免同一次低电量被播多次：
 * 1. 主路径：桌面动态注册的 ACTION_BATTERY_CHANGED（粘性广播，注册即回调当前电量），
 *    由 [onBatteryChanged] 按百分比 + 充电状态判定；
 * 2. 兼容路径：少数仍会投递 ACTION_BATTERY_LOW / BATTERY_OKAY 的 ROM，
 *    由 [onSystemLowBattery] / [onSystemBatteryOkay] 处理。
 *
 * 阈值带迟滞：低于 LOW_THRESHOLD 才播，回升到 RESET_THRESHOLD 以上才允许下一次播报，
 * 防止电量在临界值上下抖动时反复打扰老人；充电中不播（提示语是「请插上充电器」）。
 */
object LowBatteryAnnouncer {

    /** 与桌面电量卡片变红的阈值保持一致（updateBattery 用 pct < 20） */
    private const val LOW_THRESHOLD = 20
    private const val RESET_THRESHOLD = 25

    /** 本轮低电量是否已播报过；跨阈值或充电时复位 */
    private var announced = false

    /** 仅供单元测试观测播报次数（生产逻辑不依赖它） */
    internal var announceCount = 0
        private set

    /** 仅供单元测试观测本次播报实际走的音源分支（生产逻辑不依赖它） */
    internal var lastRoute: LowBatterySound.Route = LowBatterySound.Route.TTS
        private set

    /**
     * 由 ACTION_BATTERY_CHANGED 驱动。
     * @param pct 当前电量百分比，无法取得时为负
     * @param charging 是否正在充电（EXTRA_PLUGGED != 0）
     */
    @Synchronized
    fun onBatteryChanged(ctx: Context, pct: Int, charging: Boolean) {
        if (pct < 0) return
        if (charging || pct >= RESET_THRESHOLD) {
            announced = false
            return
        }
        if (pct < LOW_THRESHOLD && !announced) {
            if (speak(ctx)) announced = true
        }
    }

    /** 系统 ACTION_BATTERY_LOW（仅部分 ROM 会投递到静态接收器） */
    @Synchronized
    fun onSystemLowBattery(ctx: Context) {
        if (announced) return
        if (speak(ctx)) announced = true
    }

    /** 系统 ACTION_BATTERY_OKAY：电量已恢复，允许下一轮重新播报 */
    @Synchronized
    fun onSystemBatteryOkay() {
        announced = false
    }

    /**
     * @return 是否真的播报了（开关关闭时不播，也不占用本轮标记）
     *
     * 音源：默认仍是系统 TTS 念固定文案；用户在设置页选了本地音频后改播该文件，
     * 播放失败 / 文件失效自动回退 TTS。阈值、迟滞、去重状态一律不受影响 ——
     * 只要「播出了声音」（无论哪种音源），就照常占用本轮标记。
     */
    private fun speak(ctx: Context): Boolean {
        if (!Prefs.enabled(ctx, Prefs.ANN_LOW)) return false
        val hasCustom = LowBatterySound.hasCustom(ctx)
        val playOk = if (hasCustom) LowBatterySound.play(ctx) else false
        val route = LowBatterySound.resolveRoute(hasCustom, playOk)
        lastRoute = route
        if (route != LowBatterySound.Route.CUSTOM) {
            // 未选择自定义音频，或自定义播放失败 → 回退系统语音，绝不静默失败
            TtsManager.speakNow(ctx, ctx.getString(R.string.ann_low_battery))
        }
        CrashLogger.trace("低电量播报：音源=${route.name}")
        announceCount++
        return true
    }

    /** 仅供单元测试复位进程内状态，避免用例间互相污染 */
    internal fun resetForTest() {
        announced = false
        announceCount = 0
        lastRoute = LowBatterySound.Route.TTS
    }
}
