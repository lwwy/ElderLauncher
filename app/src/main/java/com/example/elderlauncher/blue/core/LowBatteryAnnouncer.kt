package com.example.elderlauncher.blue.core

import android.content.Context
import com.example.elderlauncher.blue.R
import com.example.elderlauncher.blue.data.Prefs
import java.util.Calendar

/**
 * 低电量播报的统一判定与节奏控制。
 *
 * 背景：ACTION_BATTERY_LOW 不在 Android 8.0+ 的「隐式广播豁免清单」内，
 * Manifest 静态注册的接收器在多数 ROM 上会被系统拦截（Background execution not allowed），
 * 因此不能只靠静态广播触发播报。
 *
 * 两条触发路径共用这里的状态，避免同一次低电量被播多次：
 * 1. 主路径：桌面动态注册的 ACTION_BATTERY_CHANGED，由 [onBatteryChanged] 按
 *    百分比 + 充电状态判定；桌面时钟每整分 tick 也会带着缓存电量再调用一次
 *    （见 HomeActivity.clockRunnable），用来把「每 30 分钟」和免打扰边界按点触发，
 *    不依赖系统恰好在那一刻投递电量广播；
 * 2. 兼容路径：少数仍会投递 ACTION_BATTERY_LOW / BATTERY_OKAY 的 ROM，
 *    由 [onSystemLowBattery] / [onSystemBatteryOkay] 处理。
 *
 * 用户拍板的节奏：
 * - 跌破 20% 立即播一次，之后**每 30 分钟**再提醒一次，直到条件解除；
 * - 插上充电器 / 电量回升到 25% 以上 → 本轮清零、停止重复提醒；
 * - 固定免打扰时段 22:00–07:00（跨午夜）内不播，且**不占用次数**；
 *   离开时段后若仍低电量、未充电，**补播一次**，之后回到 30 分钟节奏；
 * - 免打扰时段写死在代码里，不进设置页、不提供开关、不需要任何权限。
 *
 * 阈值带迟滞：低于 LOW_THRESHOLD 才播，回升到 RESET_THRESHOLD 以上才复位，
 * 防止电量在临界值上下抖动时反复打扰老人。
 */
object LowBatteryAnnouncer {

    /** 与桌面电量卡片变红的阈值保持一致（updateBattery 用 pct < 20） */
    private const val LOW_THRESHOLD = 20
    private const val RESET_THRESHOLD = 25

    /** 低电量期间的重复提醒间隔：30 分钟 */
    internal const val REPEAT_INTERVAL_MS = 30L * 60L * 1000L

    /** 固定免打扰时段 22:00–07:00（距零点的分钟数，跨午夜） */
    internal const val QUIET_START_MIN = 22 * 60
    internal const val QUIET_END_MIN = 7 * 60

    /**
     * 可替换的时钟，仅供单元测试精确驱动时间与模拟跨夜。
     * 生产永远是系统时间；[resetForTest] 会把它固定到白天 12:00，
     * 避免测试恰好在免打扰时段运行而全部被跳过。
     */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /** 上次真正播出声音的时间戳（0 = 本轮还没播过） */
    private var lastAnnounceAt: Long = 0L

    /** 免打扰时段内本该播但被跳过，离开时段后补播一次 */
    private var quietPending = false

    /** 仅供单元测试观测播报次数（生产逻辑不依赖它） */
    internal var announceCount = 0
        private set

    /** 仅供单元测试观测本次播报实际走的音源分支（生产逻辑不依赖它） */
    internal var lastRoute: LowBatterySound.Route = LowBatterySound.Route.TTS
        private set

    /**
     * 当前分钟是否落在固定免打扰窗口内。
     * 跨午夜（start > end）时命中「22:00 之后」或「07:00 之前」两段。
     */
    internal fun isInQuietWindow(minuteOfDay: Int): Boolean {
        val m = Math.floorMod(minuteOfDay, 24 * 60)
        return if (QUIET_START_MIN < QUIET_END_MIN) {
            m in QUIET_START_MIN until QUIET_END_MIN
        } else {
            m >= QUIET_START_MIN || m < QUIET_END_MIN
        }
    }

    private fun minuteOfDay(nowMillis: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = nowMillis
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** 本轮还没播过，或距上次播报已满 30 分钟 */
    private fun shouldAnnounce(now: Long): Boolean =
        lastAnnounceAt == 0L || now - lastAnnounceAt >= REPEAT_INTERVAL_MS

    /** 结束本轮：充电、电量回升、系统 BATTERY_OKAY 时调用 */
    private fun resetCycle() {
        lastAnnounceAt = 0L
        quietPending = false
    }

    /**
     * 由 ACTION_BATTERY_CHANGED（以及桌面整分 tick）驱动。
     * @param pct 当前电量百分比，无法取得时为负
     * @param charging 是否正在充电（EXTRA_PLUGGED != 0）
     */
    @Synchronized
    fun onBatteryChanged(ctx: Context, pct: Int, charging: Boolean) {
        if (pct < 0) return
        val now = clock()
        if (charging || pct >= RESET_THRESHOLD) {
            // 插上充电器 / 电量回升：停止重复提醒，本轮清零
            resetCycle()
            return
        }
        if (pct >= LOW_THRESHOLD) return   // 20~25 迟滞带：不新播、也不复位
        evaluate(ctx, now)
    }

    /** 系统 ACTION_BATTERY_LOW（仅部分 ROM 会投递到静态接收器） */
    @Synchronized
    fun onSystemLowBattery(ctx: Context) = evaluate(ctx, clock())

    /** 系统 ACTION_BATTERY_OKAY：电量已恢复，本轮结束 */
    @Synchronized
    fun onSystemBatteryOkay() = resetCycle()

    /** 免打扰 + 补播 + 30 分钟节奏的统一判定 */
    private fun evaluate(ctx: Context, now: Long) {
        if (isInQuietWindow(minuteOfDay(now))) {
            // 时段内静音：不播、不占用时间戳；记下待补播，离开后补一次
            if (shouldAnnounce(now)) quietPending = true
            return
        }
        if (quietPending) {
            // 免打扰结束后补播一次，补完回到正常 30 分钟节奏
            quietPending = false
            if (speak(ctx)) lastAnnounceAt = now
            return
        }
        if (shouldAnnounce(now) && speak(ctx)) lastAnnounceAt = now
    }

    /**
     * @return 是否真的播报了（开关关闭时不播，也不占用本轮标记）
     *
     * 音源：默认仍是系统 TTS 念固定文案；用户在设置页选了本地音频后改播该文件，
     * 播放失败 / 文件失效自动回退 TTS。阈值、迟滞、节奏状态一律不受影响 ——
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
        lastAnnounceAt = 0L
        quietPending = false
        announceCount = 0
        lastRoute = LowBatterySound.Route.TTS
        // 固定到本地白天 12:00：让既有断言与免打扰窗口解耦，任何时刻跑测试结果一致
        val fixed = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        clock = { fixed }
    }
}
