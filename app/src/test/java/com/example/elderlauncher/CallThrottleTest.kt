package com.example.elderlauncher

import com.example.elderlauncher.phone.CallThrottle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 拨号防连点回归测试。
 *
 * 守护的不变量：1 秒窗口内的重复点击必须被挡，窗口外要能正常拨号；
 * 被挡的那次点击不能刷新计时（否则会无限续期，让老人一直拨不出去）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CallThrottleTest {

    @Before
    fun setUp() = CallThrottle.resetForTest()

    @Test
    fun firstTapIsAllowed() {
        assertTrue("首次点击必须放行", CallThrottle.tryAcquire(10_000L))
    }

    @Test
    fun secondTapWithinWindowIsBlocked() {
        assertTrue(CallThrottle.tryAcquire(10_000L))
        assertFalse("1 秒内的重复点击必须被挡", CallThrottle.tryAcquire(10_500L))
    }

    @Test
    fun tapExactlyAfterWindowIsAllowed() {
        assertTrue(CallThrottle.tryAcquire(10_000L))
        assertTrue("距上次正好 1000ms 应放行", CallThrottle.tryAcquire(11_000L))
    }

    @Test
    fun blockedTapDoesNotExtendTheWindow() {
        assertTrue(CallThrottle.tryAcquire(10_000L))
        assertFalse("窗口内点击被挡", CallThrottle.tryAcquire(10_500L))
        assertTrue(
            "被挡的点击不应续期，窗口按首次点击计时",
            CallThrottle.tryAcquire(11_000L)
        )
    }

    @Test
    fun backwardsClockDoesNotLockForever() {
        assertTrue(CallThrottle.tryAcquire(10_000L))
        assertTrue("时间回拨时应放行而不是永久锁死", CallThrottle.tryAcquire(5_000L))
    }
}
