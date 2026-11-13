package com.example.elderlauncher

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.elderlauncher.core.LowBatteryAnnouncer
import com.example.elderlauncher.core.TtsManager
import com.example.elderlauncher.data.Prefs
import com.example.elderlauncher.phone.receiver.BatteryReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech

/**
 * 低电量播报回归测试。
 *
 * 背景：ACTION_BATTERY_LOW 不在 Android 8.0+ 隐式广播豁免清单内，静态接收器真机基本收不到。
 * 现改为桌面动态注册的 ACTION_BATTERY_CHANGED 驱动，这里验证状态机的阈值、迟滞与去重，
 * 以及静态兼容路径与动态路径共用去重、不会重复播报。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LowBatteryAnnouncerTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        TtsManager.shutdown()
        ShadowTextToSpeech.reset()
        // 给一个默认引擎，避免 speakNow 走「无引擎」异常分支干扰计数
        Settings.Secure.putString(
            app.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH, "com.test.tts"
        )
        Prefs.set(app, Prefs.ANN_LOW, true)
        LowBatteryAnnouncer.resetForTest()
    }

    @After
    fun tearDown() {
        TtsManager.shutdown()
        LowBatteryAnnouncer.resetForTest()
    }

    @Test
    fun dropsBelowThreshold_announcesOnce() {
        LowBatteryAnnouncer.onBatteryChanged(app, 18, false)
        assertEquals("低于阈值应播报一次", 1, LowBatteryAnnouncer.announceCount)
    }

    @Test
    fun staysLow_doesNotRepeat() {
        LowBatteryAnnouncer.onBatteryChanged(app, 18, false)
        LowBatteryAnnouncer.onBatteryChanged(app, 16, false)
        LowBatteryAnnouncer.onBatteryChanged(app, 12, false)
        assertEquals("同一轮低电量只播一次", 1, LowBatteryAnnouncer.announceCount)
    }

    @Test
    fun charging_suppressesAndResets() {
        LowBatteryAnnouncer.onBatteryChanged(app, 15, true)
        assertEquals("充电中不应播「请插上充电器」", 0, LowBatteryAnnouncer.announceCount)
        // 拔掉充电器后仍处于低电量，应能播报
        LowBatteryAnnouncer.onBatteryChanged(app, 15, false)
        assertEquals(1, LowBatteryAnnouncer.announceCount)
    }

    @Test
    fun recoversAboveReset_allowsNextRound() {
        LowBatteryAnnouncer.onBatteryChanged(app, 18, false)
        LowBatteryAnnouncer.onBatteryChanged(app, 30, false)   // 回升越过 25，复位
        LowBatteryAnnouncer.onBatteryChanged(app, 18, false)   // 再次跌破
        assertEquals("回升后应允许下一轮播报", 2, LowBatteryAnnouncer.announceCount)
    }

    @Test
    fun hysteresis_20to24DoesNotRetrigger() {
        LowBatteryAnnouncer.onBatteryChanged(app, 18, false)
        LowBatteryAnnouncer.onBatteryChanged(app, 22, false)   // 在 20~25 之间抖动，不复位
        LowBatteryAnnouncer.onBatteryChanged(app, 19, false)
        assertEquals("迟滞区间内不应重复播报", 1, LowBatteryAnnouncer.announceCount)
    }

    @Test
    fun switchOff_doesNotAnnounceNorConsumeRound() {
        Prefs.set(app, Prefs.ANN_LOW, false)
        LowBatteryAnnouncer.onBatteryChanged(app, 15, false)
        assertEquals("开关关闭不应播报", 0, LowBatteryAnnouncer.announceCount)

        Prefs.set(app, Prefs.ANN_LOW, true)
        LowBatteryAnnouncer.onBatteryChanged(app, 15, false)
        assertEquals("开关打开后本轮仍应能播报", 1, LowBatteryAnnouncer.announceCount)
    }

    @Test
    fun staticAndDynamicPaths_shareDedup() {
        LowBatteryAnnouncer.onBatteryChanged(app, 15, false)   // 动态主路径播了
        BatteryReceiver().onReceive(app, Intent(Intent.ACTION_BATTERY_LOW)) // 静态兼容路径
        assertEquals("两条路径不能各播一次", 1, LowBatteryAnnouncer.announceCount)

        BatteryReceiver().onReceive(app, Intent(Intent.ACTION_BATTERY_OKAY))
        BatteryReceiver().onReceive(app, Intent(Intent.ACTION_BATTERY_LOW))
        assertEquals("BATTERY_OKAY 复位后可再播一轮", 2, LowBatteryAnnouncer.announceCount)
    }

    @Test
    fun invalidPercentage_isIgnored() {
        LowBatteryAnnouncer.onBatteryChanged(app, -1, false)
        assertEquals(0, LowBatteryAnnouncer.announceCount)
    }
}
