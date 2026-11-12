package com.example.elderlauncher.blue

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.elderlauncher.blue.core.LowBatteryAlarmScheduler
import com.example.elderlauncher.blue.core.LowBatteryAnnouncer
import com.example.elderlauncher.blue.data.Prefs
import com.example.elderlauncher.blue.phone.receiver.LowBatteryAlarmReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.util.Calendar

/**
 * 低电量播报「系统级定时哨兵」回归测试：
 * - nextAlarmAt 既要每 30 分钟巡检，又要准时对齐免打扰边界（22:00 进 / 07:00 出）；
 * - scheduleNext 用 RTC_WAKEUP 排到本应用闹钟；ensureScheduled 不重复排；
 * - tick 在低电量时驱动状态机播报并续排下一次；功能关闭时立即取消，不再唤醒；
 * - 接收器把电量交给状态机、并续排闹钟。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LowBatteryAlarmSchedulerTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Prefs.set(app, Prefs.ANN_LOW, true)
        LowBatteryAnnouncer.resetForTest()
    }

    @After
    fun tearDown() {
        LowBatteryAlarmScheduler.cancel(app)
        LowBatteryAnnouncer.resetForTest()
        // 复位 Robolectric 静态闹钟状态（setCanScheduleExactAlarms 是全局静态），
        // 避免用例间因执行顺序导致 canScheduleExact 取值串味。
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
    }

    private fun at(dayOffset: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.JANUARY, 1 + dayOffset, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /**
     * 本测试套件里只有 LowBatteryAlarmScheduler 会排闹钟，因此 scheduledAlarms 即为「我们的闹钟」。
     * 直接断言数量 / 通道类型，避免依赖 PendingIntent.getIntent() 在测试桩上的可用性。
     */
    private fun scheduledAlarms(): List<ShadowAlarmManager.ScheduledAlarm> =
        shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms

    // ---------------------------------------------------------------- 触发时刻对齐

    @Test
    fun nextAlarmAt_alignsToNearestQuietBoundary() {
        // 白天 12:00 → 边界还远，取 30 分钟后
        assertEquals(at(0, 12, 30), LowBatteryAlarmScheduler.nextAlarmAt(at(0, 12, 0)))
        // 21:45 → 边界 22:00 更近，应排在 22:00 准时进入免打扰
        assertEquals(at(0, 22, 0), LowBatteryAlarmScheduler.nextAlarmAt(at(0, 21, 45)))
        // 06:45 → 边界 07:00 更近，应排在 07:00 准时补播
        assertEquals(at(0, 7, 0), LowBatteryAlarmScheduler.nextAlarmAt(at(0, 6, 45)))
        // 22:30（免打扰中）→ 30 分钟后的 23:00 更近，正常巡检
        assertEquals(at(0, 23, 0), LowBatteryAlarmScheduler.nextAlarmAt(at(0, 22, 30)))
    }

    // ---------------------------------------------------------------- 排程行为

    @Test
    fun scheduleNext_schedulesRtcWakeupWithOurAction() {
        LowBatteryAlarmScheduler.scheduleNext(app)
        val alarm = scheduledAlarms().firstOrNull()
        assertTrue("应排到本应用闹钟", alarm != null)
        assertEquals("必须用 RTC_WAKEUP 才能在息屏/Doze 下唤醒", AlarmManager.RTC_WAKEUP, alarm!!.getType())
    }

    @Test
    fun ensureScheduled_onlySchedulesOnce() {
        LowBatteryAlarmScheduler.ensureScheduled(app)
        LowBatteryAlarmScheduler.ensureScheduled(app) // 第二次不应新增
        assertEquals(1, scheduledAlarms().size)
    }

    // ---------------------------------------------------------------- 关闭 -> 重新打开 恢复排程（C2）

    @Test
    fun cancel_thenReschedule_recoversAlarm() {
        // 打开：排一个闹钟
        LowBatteryAlarmScheduler.ensureScheduled(app)
        assertEquals(1, scheduledAlarms().size)

        // 关闭（功能关掉）：cancel 必须连 PendingIntent 一起撤销，不留残留
        Prefs.set(app, Prefs.ANN_LOW, false)
        LowBatteryAlarmScheduler.cancel(app)
        assertEquals("关闭后不应残留任何闹钟", 0, scheduledAlarms().size)

        // 重新打开：应能重新创建并排程新的 PendingIntent（无死锁、无残留导致永不触发）
        Prefs.set(app, Prefs.ANN_LOW, true)
        LowBatteryAlarmScheduler.ensureScheduled(app)
        assertEquals("关闭后重新打开应能恢复排程", 1, scheduledAlarms().size)
        assertTrue(
            "重新排程的应为 RTC_WAKEUP 精确闹钟",
            scheduledAlarms().first().getType() == AlarmManager.RTC_WAKEUP
        )
    }

    @Test
    fun canScheduleExact_followsSystemPermission() {
        // 系统授予精确闹钟权限：应允许（真实设备首次安装默认即此状态）
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue("系统授予精确闹钟权限时应允许", LowBatteryAlarmScheduler.canScheduleExact(app))
        // 用户撤销后：canScheduleExact 应返回 false
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse("系统撤销精确闹钟权限时应不允许", LowBatteryAlarmScheduler.canScheduleExact(app))
    }

    @Test
    fun scheduleNext_withoutExactPermission_degradesButStillSchedules() {
        // R1：精确闹钟权限被撤销时，scheduleNext 降级为 inexact，但仍在排程（不漏提醒）
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        LowBatteryAlarmScheduler.scheduleNext(app)
        assertTrue(
            "无精确闹钟权限时仍应排程（降级 inexact，仍可 Doze 唤醒）",
            scheduledAlarms().isNotEmpty()
        )
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    // ---------------------------------------------------------------- tick 驱动状态机

    @Test
    fun tick_whenLow_announcesAndReschedules() {
        LowBatteryAlarmScheduler.tick(app, 18, false)
        assertEquals("低电量应立即播报一次", 1, LowBatteryAnnouncer.announceCount)
        assertTrue("tick 后应续排下一次闹钟", scheduledAlarms().isNotEmpty())
    }

    @Test
    fun tick_whenCharging_resetsAndStillReschedules() {
        LowBatteryAlarmScheduler.tick(app, 18, false)
        assertEquals(1, LowBatteryAnnouncer.announceCount)
        // 充电中：本轮清零，不再播报，但哨兵继续巡检（不取消）
        LowBatteryAlarmScheduler.tick(app, 18, plugged = true)
        assertEquals("充电时不重复播报", 1, LowBatteryAnnouncer.announceCount)
        assertTrue("哨兵仍应存活以监测后续掉电", scheduledAlarms().isNotEmpty())
    }

    @Test
    fun tick_whenDisabled_cancelsAlarm() {
        LowBatteryAlarmScheduler.ensureScheduled(app)
        assertTrue("先排程一个闹钟", scheduledAlarms().isNotEmpty())
        Prefs.set(app, Prefs.ANN_LOW, false)
        LowBatteryAlarmScheduler.tick(app, 18, false) // 功能已关 → 取消
        assertFalse("功能关闭后不应再有闹钟", scheduledAlarms().isNotEmpty())
    }

    // ---------------------------------------------------------------- 接收器

    @Test
    fun receiver_forwardsBatteryToAnnouncer_andSchedules() {
        val receiver = LowBatteryAlarmReceiver()
        receiver.batteryReader = { 15 to false } // 注入电量，不依赖真实广播
        receiver.onReceive(app, Intent(LowBatteryAlarmScheduler.ACTION))
        assertEquals("接收器应把电量交给状态机并播报", 1, LowBatteryAnnouncer.announceCount)
        assertTrue("接收器处理后应续排闹钟", scheduledAlarms().isNotEmpty())
    }

    @Test
    fun receiver_ignoresUnknownAction() {
        val receiver = LowBatteryAlarmReceiver()
        receiver.batteryReader = { 15 to false }
        receiver.onReceive(app, Intent("com.example.elderlauncher.blue.SOMETHING_ELSE"))
        assertEquals("非本闹钟 action 不应触发播报", 0, LowBatteryAnnouncer.announceCount)
        assertFalse("非本闹钟 action 不应排程", scheduledAlarms().isNotEmpty())
    }
}
