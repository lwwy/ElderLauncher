package com.example.elderlauncher.blue

import android.app.Application
import android.media.AudioAttributes
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.example.elderlauncher.blue.core.AnnounceAudio
import com.example.elderlauncher.blue.core.LowBatteryAnnouncer
import com.example.elderlauncher.blue.core.LowBatterySound
import com.example.elderlauncher.blue.core.TtsManager
import com.example.elderlauncher.blue.data.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.io.ByteArrayInputStream
import java.util.Calendar

/**
 * 用户拍板的低电量播报节奏回归：
 * - 统一闹钟通道（USAGE_ALARM），默认生效、无开关；
 * - 每 30 分钟提醒一次，插上充电器 / 电量回升即停止本轮；
 * - 固定免打扰 22:00–07:00 内不播，结束后补播一次；时段写死在代码里，不进设置页。
 *
 * 时间通过 LowBatteryAnnouncer.clock 注入，既保证任何时刻跑测试结果一致，
 * 也能精确驱动「29 分钟 / 30 分钟」与 22:00 / 07:00 这类边界。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LowBatteryScheduleTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        TtsManager.shutdown()
        ShadowTextToSpeech.reset()
        Settings.Secure.putString(
            app.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH, "com.test.tts"
        )
        Prefs.set(app, Prefs.ANN_LOW, true)
        LowBatterySound.clear(app)
        LowBatteryAnnouncer.resetForTest()
    }

    @After
    fun tearDown() {
        TtsManager.shutdown()
        LowBatterySound.clear(app)
        LowBatteryAnnouncer.resetForTest()
    }

    /** 构造本地时区的确定时间戳；dayOffset 用于跨午夜 */
    private fun at(dayOffset: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.JANUARY, 1 + dayOffset, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun clockAt(t: Long) {
        LowBatteryAnnouncer.clock = { t }
    }

    private fun battery(pct: Int, charging: Boolean = false) =
        LowBatteryAnnouncer.onBatteryChanged(app, pct, charging)

    private fun count() = LowBatteryAnnouncer.announceCount

    // ---------------------------------------------------------------- 闹钟通道

    @Test
    fun announceAudio_isAlarmChannel() {
        val attrs = AnnounceAudio.alarm()
        assertEquals("播报必须走闹钟流", AudioAttributes.USAGE_ALARM, attrs.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, attrs.contentType)
    }

    @Test
    fun tts_initializationAdoptsAlarmChannel() {
        TtsManager.init(app)
        val instance = ShadowTextToSpeech.getLastTextToSpeechInstance()
        assertNotNull("应已创建 TTS 引擎实例，lastError=${TtsManager.lastError}", instance)
        shadowOf(instance!!).onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("引擎应初始化成功", TtsManager.isReady())
        val attrs = TtsManager.appliedAudioAttributes
        assertNotNull("TTS 播报必须应用闹钟通道", attrs)
        assertEquals(AudioAttributes.USAGE_ALARM, attrs!!.usage)
    }

    @Test
    fun customTone_playUsesAlarmChannel() {
        LowBatterySound.saveFromStream(app, ByteArrayInputStream(ByteArray(128)), "tone.mp3")
        // Robolectric 下 MediaPlayer 未必能播放假数据，但通道属性在播放前就已应用
        LowBatterySound.play(app)
        val attrs = LowBatterySound.lastAppliedAudioAttributes
        assertNotNull("自定义提示音必须应用闹钟通道", attrs)
        assertEquals(AudioAttributes.USAGE_ALARM, attrs!!.usage)
    }

    // ---------------------------------------------------------------- 写死的常量

    @Test
    fun scheduleConstants_matchUserDecision() {
        assertEquals(22 * 60, LowBatteryAnnouncer.QUIET_START_MIN)
        assertEquals(7 * 60, LowBatteryAnnouncer.QUIET_END_MIN)
        assertEquals(30L * 60L * 1000L, LowBatteryAnnouncer.REPEAT_INTERVAL_MS)
    }

    @Test
    fun quietWindow_boundaries_acrossMidnight() {
        assertFalse("21:59 不在免打扰", LowBatteryAnnouncer.isInQuietWindow(21 * 60 + 59))
        assertTrue("22:00 起进入免打扰", LowBatteryAnnouncer.isInQuietWindow(22 * 60))
        assertTrue("23:59 在免打扰", LowBatteryAnnouncer.isInQuietWindow(23 * 60 + 59))
        assertTrue("00:00 跨夜后仍在免打扰", LowBatteryAnnouncer.isInQuietWindow(0))
        assertTrue("06:59 仍在免打扰", LowBatteryAnnouncer.isInQuietWindow(6 * 60 + 59))
        assertFalse("07:00 结束免打扰", LowBatteryAnnouncer.isInQuietWindow(7 * 60))
        assertFalse("白天 12:00 不在免打扰", LowBatteryAnnouncer.isInQuietWindow(12 * 60))
    }

    // ---------------------------------------------------------------- 30 分钟节奏

    @Test
    fun repeatsEveryThirtyMinutes_whileStillLowAndUnplugged() {
        val t0 = at(0, 12, 0)
        clockAt(t0)
        battery(18)
        assertEquals("跌破阈值应立即播一次", 1, count())

        clockAt(t0 + 29 * 60_000L)
        battery(18)
        assertEquals("未满 30 分钟不重复", 1, count())

        clockAt(t0 + 30 * 60_000L)
        battery(18)
        assertEquals("满 30 分钟应再播一次", 2, count())

        clockAt(t0 + 60 * 60_000L)
        battery(18)
        assertEquals("再满 30 分钟应继续提醒", 3, count())
    }

    @Test
    fun charging_stopsRepeatsAndResetsCycle() {
        val t0 = at(0, 12, 0)
        clockAt(t0)
        battery(18)
        assertEquals(1, count())

        // 充电期间即便时间推进也不再播报
        clockAt(t0 + 30 * 60_000L)
        battery(18, charging = true)
        assertEquals("插上充电器应停止重复提醒", 1, count())
        clockAt(t0 + 60 * 60_000L)
        battery(18, charging = true)
        assertEquals(1, count())
        clockAt(t0 + 90 * 60_000L)
        battery(18, charging = true)
        assertEquals(1, count())

        // 拔掉后开启新一轮，立即提醒一次
        clockAt(t0 + 90 * 60_000L)
        battery(18, charging = false)
        assertEquals("拔掉充电器后应重新提醒", 2, count())
    }

    @Test
    fun recoveryAbove25_stopsRepeats() {
        val t0 = at(0, 12, 0)
        clockAt(t0)
        battery(18)
        assertEquals(1, count())

        clockAt(t0 + 30 * 60_000L)
        battery(30)   // 回升越过 25，本轮结束
        assertEquals(1, count())

        clockAt(t0 + 31 * 60_000L)
        battery(18)   // 再次跌破，新一轮立即播
        assertEquals(2, count())
    }

    // ---------------------------------------------------------------- 免打扰 + 补播

    @Test
    fun quietHours_suppressThenMakeUpExactlyOnce() {
        clockAt(at(0, 22, 0))
        battery(18)
        clockAt(at(0, 23, 30))
        battery(18)
        clockAt(at(1, 3, 0))
        battery(18)
        assertEquals("免打扰时段内一次都不应播", 0, count())

        clockAt(at(1, 7, 0))
        battery(18)
        assertEquals("07:00 结束后应补播一次", 1, count())

        clockAt(at(1, 7, 1))
        battery(18)
        assertEquals("补播不应重复", 1, count())

        clockAt(at(1, 7, 30))
        battery(18)
        assertEquals("补播后回到 30 分钟节奏", 2, count())
    }

    @Test
    fun quietHours_chargingStillSuppressesMakeUp() {
        clockAt(at(0, 22, 0))
        battery(18)
        clockAt(at(0, 23, 0))
        battery(18, charging = true)   // 免打扰期间插上充电器，本轮清零
        clockAt(at(1, 7, 0))
        battery(18, charging = true)
        assertEquals("仍在充电时不补播，充电优先", 0, count())
    }

    @Test
    fun staticCompatPath_alsoRespectsQuietHoursAndMakeUp() {
        clockAt(at(0, 23, 0))
        LowBatteryAnnouncer.onSystemLowBattery(app)
        assertEquals("静态兼容路径在免打扰时段也应静音", 0, count())

        clockAt(at(1, 7, 0))
        LowBatteryAnnouncer.onSystemLowBattery(app)
        assertEquals("静态兼容路径同样补播一次", 1, count())
    }
}
