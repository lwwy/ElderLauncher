package com.example.elderlauncher.blue

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import com.example.elderlauncher.blue.core.LowBatteryAnnouncer
import com.example.elderlauncher.blue.core.TtsManager
import com.example.elderlauncher.blue.data.Prefs
import com.example.elderlauncher.blue.ui.HomeActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech

/**
 * 接线级回归：确认桌面动态注册的电量接收器真的把 ACTION_BATTERY_CHANGED
 * 转发给 [LowBatteryAnnouncer]，即「真实电量变化 → 播报」这条链路是通的。
 * 单元测只覆盖状态机，这里补上 HomeActivity 的接线。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeLowBatteryIntegrationTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        TtsManager.shutdown()
        ShadowTextToSpeech.reset()
        android.provider.Settings.Secure.putString(
            app.contentResolver,
            android.provider.Settings.Secure.TTS_DEFAULT_SYNTH,
            "com.test.tts"
        )
        Prefs.set(app, Prefs.ANN_LOW, true)
    }

    @After
    fun tearDown() {
        TtsManager.shutdown()
        LowBatteryAnnouncer.resetForTest()
    }

    /** 反射取到桌面私有的电量接收器，直接投递一次电量广播 */
    private fun deliverToHomeReceiver(activity: HomeActivity, pct: Int, plugged: Int) {
        val field = HomeActivity::class.java.getDeclaredField("batteryReceiver").apply {
            isAccessible = true
        }
        val receiver = field.get(activity) as BroadcastReceiver
        val intent = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, pct)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
        }
        receiver.onReceive(activity, intent)
    }

    @Test
    fun lowBatteryChanged_onHome_triggersAnnouncement() {
        LowBatteryAnnouncer.resetForTest()
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val activity = controller.get()
        // 清掉 setup() 过程中粘性广播可能带来的干扰，只测这一次投递
        LowBatteryAnnouncer.resetForTest()

        deliverToHomeReceiver(activity, 15, 0)   // 15% 且未充电
        assertEquals("桌面收到低电量广播应触发一次播报", 1, LowBatteryAnnouncer.announceCount)

        deliverToHomeReceiver(activity, 14, 0)   // 继续下降，不应重复播
        assertEquals("同一轮不应重复播报", 1, LowBatteryAnnouncer.announceCount)
        controller.close()
    }

    @Test
    fun chargingLowBattery_onHome_doesNotAnnounce() {
        LowBatteryAnnouncer.resetForTest()
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val activity = controller.get()
        LowBatteryAnnouncer.resetForTest()

        deliverToHomeReceiver(activity, 15, 1)   // 15% 但正在充电
        assertEquals("充电中不应播「请插上充电器」", 0, LowBatteryAnnouncer.announceCount)
        controller.close()
    }
}
