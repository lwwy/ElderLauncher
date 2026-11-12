package com.example.elderlauncher.blue

import com.example.elderlauncher.blue.ui.HomeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 时钟刷新对齐到「每分钟第 0 秒」的回归测试。
 *
 * 守护的不变量：延迟必须让下一次刷新正好落在整分边界，
 * 这样 12:01:00 一到就显示 12:01，而不是沿用固定 30 秒轮询导致最多慢半分钟。
 * [HomeActivity.delayToNextMinute] 是纯函数，直接驱动即可，无需构建 Activity。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClockAlignmentTest {

    private val minute = 60_000L

    @Test
    fun exactlyOnMinute_waitsFullMinute() {
        // 正好整分：应排到下一个整分，而不是 0 延迟忙循环
        assertEquals(minute, HomeActivity.delayToNextMinute(0L))
        assertEquals(minute, HomeActivity.delayToNextMinute(12 * minute))
    }

    @Test
    fun halfPastMinute_waitsRemainingHalf() {
        assertEquals(30_000L, HomeActivity.delayToNextMinute(30_000L))
    }

    @Test
    fun oneSecondBeforeNextMinute_waitsOneSecond() {
        assertEquals(1_000L, HomeActivity.delayToNextMinute(59_000L))
    }

    @Test
    fun arbitraryOffset_landsExactlyOnNextMinuteBoundary() {
        // 任意时刻 now + delay 都必须整除 60000（即落在整分）
        val samples = longArrayOf(1L, 999L, 12_345L, 37_001L, 59_999L, 123_456_789L)
        for (now in samples) {
            val delay = HomeActivity.delayToNextMinute(now)
            assertTrue("延迟必须为正，now=$now delay=$delay", delay > 0)
            assertTrue("延迟不应超过一分钟，now=$now delay=$delay", delay <= minute)
            assertEquals(
                "now=$now 时应恰好对齐到下一整分",
                0L,
                (now + delay) % minute
            )
        }
    }

    @Test
    fun negativeTime_doesNotProduceNegativeDelay() {
        // Math.floorMod 保证负时间也落在 (0, 60000]，不会出现负延迟
        val delay = HomeActivity.delayToNextMinute(-1L)
        assertTrue(delay > 0 && delay <= minute)
    }
}
