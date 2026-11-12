package com.example.elderlauncher.blue

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.elderlauncher.blue.phone.receiver.BootReceiver
import com.example.elderlauncher.blue.ui.HomeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 删除保活服务后的「重启自启」回归测试。
 *
 * 守护的不变量：手机重启 / 应用更新后，仍会拉起桌面 HomeActivity；
 * 且不再启动任何前台服务（KeepAliveService 已删除）。
 * 低电量播报由静态广播接收器（豁免清单）按需触发，不依赖常驻进程。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BootReceiverTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun assertHomeLaunched(action: String) {
        BootReceiver().onReceive(app, Intent(action))
        val started = shadowOf(app).nextStartedActivity
        assertNotNull("$action 后应拉起桌面", started)
        assertEquals(
            "应拉起 HomeActivity",
            HomeActivity::class.java.name,
            started!!.component?.className
        )
        // 关键：删除保活后不得再启动任何服务
        assertNull(
            "$action 后不应再启动常驻服务",
            shadowOf(app).nextStartedService
        )
    }

    @Test
    fun bootCompleted_launchesHome_withoutStartingAnyService() {
        assertHomeLaunched(Intent.ACTION_BOOT_COMPLETED)
    }

    @Test
    fun quickBootPowerOn_launchesHome() {
        assertHomeLaunched("android.intent.action.QUICKBOOT_POWERON")
    }

    @Test
    fun packageReplaced_launchesHome() {
        assertHomeLaunched(Intent.ACTION_MY_PACKAGE_REPLACED)
    }

    @Test
    fun unrelatedAction_doesNothing() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_TIME_TICK))
        assertNull(shadowOf(app).nextStartedActivity)
        assertNull(shadowOf(app).nextStartedService)
    }
}
