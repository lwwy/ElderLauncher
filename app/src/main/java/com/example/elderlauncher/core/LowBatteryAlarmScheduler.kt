package com.example.elderlauncher.core

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.example.elderlauncher.core.CrashLogger
import com.example.elderlauncher.data.Prefs
import com.example.elderlauncher.phone.receiver.LowBatteryAlarmReceiver
import java.util.Calendar

/**
 * 低电量播报的「系统级定时哨兵」。
 *
 * 背景：原方案只在桌面 HomeActivity 前台（resumed）时，靠 Handler 时钟循环 + 动态电量广播
 * 驱动播报。一旦息屏、或用户在别的 App 前台，桌面 Activity 进入 onPause，时钟与广播全停，
 * 息屏期间（最该提醒「该充电了」的场景）反而完全不响，只有亮屏回到桌面才补播一次。
 *
 * 这里改用 AlarmManager 在系统层排程：
 * - 用 setExactAndAllowWhileIdle(RTC_WAKEUP) 排一次「精确、且 Doze 期间也能唤醒」的闹钟，
 *   闹钟 BroadcastReceiver 收到后读当前电量（粘性广播直接查，无需 Activity 生命周期）、
 *   走既有的 [LowBatteryAnnouncer] 状态机（30 分钟节奏 / 免打扰 / 补播全部复用），然后自重新排下一次，
 *   形成每 30 分钟一次的轻量哨兵；
 * - 这样息屏、别的 App 前台都能按时提醒，与桌面时钟路径共用同一状态机，不会重复播；
 * - 持有短暂 PARTIAL_WAKE_LOCK 保证出声期间 CPU 不睡；超时 + 延迟释放双兜底，绝不泄漏；
 * - 开机 / 应用更新后由 BootReceiver 重新排程；功能在设置里关闭时取消，不再唤醒。
 *
 * 功耗：哨兵每 30 分钟只唤醒约百毫秒读一次电量，代价可忽略；它换来的是
 * 「手机搁一边息屏低电量也提醒」，使低电量播报真正发挥「提醒充电」的作用。
 */
internal object LowBatteryAlarmScheduler {

    /** 闹钟 intent 的 action，避免与系统 / 其他广播混淆 */
    const val ACTION = "com.example.elderlauncher.LOW_BATTERY_ALARM"

    /** 复用的 30 分钟节奏（与 LowBatteryAnnouncer 同源，单一事实来源） */
    private val REPEAT_INTERVAL_MS = LowBatteryAnnouncer.REPEAT_INTERVAL_MS

    /** 出声时持有 wakelock 的时长上限（兜底释放，防止 CPU 在播报中途休眠） */
    private const val WAKE_SPOKE_MS = 15_000L

    /** 仅查询、未出声时的短暂持有（立即释放） */
    private const val WAKE_QUICK_MS = 1_000L

    private var wakeLock: PowerManager.WakeLock? = null

    private fun pendingIntent(ctx: Context): android.app.PendingIntent {
        val intent = Intent(ctx, LowBatteryAlarmReceiver::class.java).setAction(ACTION)
        return android.app.PendingIntent.getBroadcast(
            ctx,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 是否已排过程（用于 ensureScheduled 避免每次 onResume 都重置计时） */
    private fun isScheduled(ctx: Context): Boolean = try {
        android.app.PendingIntent.getBroadcast(
            ctx,
            0,
            Intent(ctx, LowBatteryAlarmReceiver::class.java).setAction(ACTION),
            android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
        ) != null
    } catch (_: Exception) {
        false
    }

    /**
     * Android 12+(API31) 起精确闹钟需用户授予 SCHEDULE_EXACT_ALARM；
     * 未授予时只能降级为 inexact（仍可 Doze 唤醒）。低版本恒为 true。
     */
    internal fun canScheduleExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    /**
     * 引导用户到系统设置开启「精确闹钟」权限（Android 12+）。
     * 仅当 [canScheduleExact] 为 false 时调用；调用方须为前台 Activity 上下文，
     * 内部已加 NEW_TASK 并做 resolveActivity 守卫，无法打开时静默失败不闪退。
     */
    internal fun openExactAlarmSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(ctx.packageManager) != null) {
                ctx.startActivity(intent)
            }
        } catch (_: Exception) {
        }
    }

    /** 安排下一次触发：功能关闭时直接取消；否则排到 [nextAlarmAt] 那一刻 */
    fun scheduleNext(ctx: Context) {
        if (!Prefs.enabled(ctx, Prefs.ANN_LOW)) {
            cancel(ctx)
            return
        }
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val trigger = nextAlarmAt(System.currentTimeMillis())
        // R1：精确闹钟权限被用户撤销（Android 12+）时，先记录降级原因，
        // 直接降级为仍可 Doze 唤醒的 inexact 方案，避免 SecurityException 与「完全不提醒」。
        if (!canScheduleExact(ctx)) {
            CrashLogger.trace(
                "精确闹钟权限(SCHEDULE_EXACT_ALARM)未授予，降级为 setAndAllowWhileIdle（用户未开启精确闹钟）"
            )
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent(ctx))
            } catch (_: Exception) {
            }
            return
        }
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent(ctx))
        } catch (_: SecurityException) {
            // 兜底：极端时序下权限在检查后被撤销，再降级一次
            CrashLogger.trace("setExactAndAllowWhileIdle 抛 SecurityException，降级为 setAndAllowWhileIdle")
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent(ctx))
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    /** 仅在尚未排程时排一次，避免每次亮屏 / 回到桌面都重置 30 分钟计时 */
    fun ensureScheduled(ctx: Context) {
        if (!Prefs.enabled(ctx, Prefs.ANN_LOW)) {
            cancel(ctx)
            return
        }
        if (!isScheduled(ctx)) scheduleNext(ctx)
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            val pi = pendingIntent(ctx)
            // 同时撤销 AlarmManager 里的闹钟与 PendingIntent 本身：
            // 仅 cancel 闹钟而不撤销 PendingIntent，会让旧 PendingIntent 残留，
            // 重新打开开关重新排程时可能复用到一个已失效的对象，造成「排了却永不触发」的死锁。
            am.cancel(pi)
            pi.cancel()
        } catch (_: Exception) {
        }
    }

    /**
     * 下一次触发时刻：取「现在 + 30 分钟」与「最近的免打扰边界」的较小值。
     * 这样既能每 30 分钟巡检，又能准时在 22:00 进入免打扰、07:00 离开后补播，
     * 不会因为只按 30 分钟跳而错过免打扰边界（最多延迟到下一个 30 分钟点）。
     */
    internal fun nextAlarmAt(now: Long): Long {
        val base = now + REPEAT_INTERVAL_MS
        val boundary = nextQuietBoundary(now)
        return if (boundary > 0) minOf(base, boundary) else base
    }

    /** 紧跟 now 之后的免打扰边界（07:00 离开 / 22:00 进入），用于准时切换免打扰状态 */
    private fun nextQuietBoundary(now: Long): Long {
        val cal = Calendar.getInstance()
        val cands = mutableListOf<Long>()
        for (day in 0..1) {
            for ((h, m) in listOf(7 to 0, 22 to 0)) {
                cal.timeInMillis = now
                cal.add(Calendar.DAY_OF_MONTH, day)
                cal.set(Calendar.HOUR_OF_DAY, h)
                cal.set(Calendar.MINUTE, m)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                if (cal.timeInMillis > now) cands += cal.timeInMillis
            }
        }
        return cands.minOrNull() ?: -1
    }

    /**
     * 闹钟唤醒后的「读电量 → 走状态机 → 出声 → 续排」统一处理。
     * @param spoke 是否真的播报了（出声时需要更久持有 wakelock，让音频播完）
     */
    internal fun tick(ctx: Context, pct: Int, plugged: Boolean) {
        if (!Prefs.enabled(ctx, Prefs.ANN_LOW)) {
            cancel(ctx)
            return
        }
        val before = LowBatteryAnnouncer.announceCount
        LowBatteryAnnouncer.onBatteryChanged(ctx.applicationContext, pct, plugged)
        val spoke = LowBatteryAnnouncer.announceCount > before
        holdWakeLock(ctx, spoke)
        scheduleNext(ctx)
    }

    /** 读取当前电量 / 充电状态（粘性 ACTION_BATTERY_CHANGED，无需任何 Activity 生命周期） */
    internal fun readBattery(ctx: Context): Pair<Int, Boolean> {
        val intent = try {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        }
        if (intent == null) return -1 to false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return pct to plugged
    }

    /**
     * 短暂持有 PARTIAL_WAKE_LOCK，保证读电量 / 出声期间 CPU 不被休眠。
     * 使用带超时的 acquire：即使后续释放路径未执行，系统也会在超时后自动释放，绝不泄漏。
     */
    internal fun holdWakeLock(ctx: Context, spoke: Boolean) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = wakeLock ?: pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "ElderLauncher:LowBattery"
            ).also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
            val ms = if (spoke) WAKE_SPOKE_MS else WAKE_QUICK_MS
            if (!lock.isHeld) lock.acquire(ms)
        } catch (_: Exception) {
        }
    }
}
