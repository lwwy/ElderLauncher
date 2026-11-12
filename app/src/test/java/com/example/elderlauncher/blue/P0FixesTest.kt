package com.example.elderlauncher.blue

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.example.elderlauncher.blue.core.LowBatteryAnnouncer
import com.example.elderlauncher.blue.core.TtsManager
import com.example.elderlauncher.blue.data.LocalContacts
import com.example.elderlauncher.blue.data.Prefs
import com.example.elderlauncher.blue.phone.receiver.BatteryReceiver
import com.example.elderlauncher.blue.ui.HomeActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import org.robolectric.shadows.ShadowToast
import java.util.Locale

/**
 * 第一批（P0）修复的回归测试。全部在 JVM 上跑（Robolectric），不需要真机/模拟器。
 *
 * 覆盖：
 * 1. 首页返回键被拦截，不会退出桌面（P0-1）
 * 2. TTS「中文数据缺失」能被真实检测到，不再被重置（P0-2）
 * 3. 低电量播报文案来自 strings.xml（P0-3；来电播报文案随功能下架已移除）
 * 4. 头像内存缓存命中与失效（P0-4）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class P0FixesTest {

    private lateinit var app: Application

    private companion object {
        /**
         * 1x1 透明 PNG。
         * Robolectric 的 BitmapFactory 会真实解析文件内容，随便写的字节解不出位图，
         * 所以这里放一份最小合法图片来模拟"磁盘上的头像文件"。
         */
        val PNG_1X1 = intArrayOf(
            0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
            0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41,
            0x54, 0x78, 0xDA, 0x63, 0xFC, 0xCF, 0xC0, 0x50,
            0x0F, 0x00, 0x04, 0x85, 0x01, 0x80, 0x84, 0xA9,
            0x8C, 0x21, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45,
            0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82
        ).map { it.toByte() }.toByteArray()
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        TtsManager.shutdown()      // object 单例，避免用例间互相污染
        LowBatteryAnnouncer.resetForTest()  // 低电量去重状态同样跨用例，需复位
        ShadowTextToSpeech.reset()
        // 让 TtsManager 能拿到一个"默认引擎"，否则它会判定系统没装引擎而不创建实例
        Settings.Secure.putString(
            app.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH, "com.test.tts"
        )
    }

    @After
    fun tearDown() {
        TtsManager.shutdown()
    }

    /** Robolectric 下 TTS 的初始化回调是投递到主线程队列的，需要手动跑一次 */
    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * 驱动一次 TTS 初始化回调。
     * Robolectric 不会真的去绑定系统语音引擎服务，所以初始化回调不会自动到达，
     * 这里拿到引擎实例后手动触发 onInit(SUCCESS)，正好覆盖 TtsManager 初始化回调里的逻辑。
     */
    private fun driveTtsInit() {
        val instance = ShadowTextToSpeech.getLastTextToSpeechInstance()
        assertNotNull("应已创建 TTS 引擎实例，lastError=${TtsManager.lastError}", instance)
        shadowOf(instance).onInitListener.onInit(TextToSpeech.SUCCESS)
        idleMain()
    }

    private fun lastSpoken(): String? {
        val instance = ShadowTextToSpeech.getLastTextToSpeechInstance() ?: return null
        return shadowOf(instance).lastSpokenText
    }

    // ---------- P0-1 返回键 ----------

    @Test
    fun backPress_onHome_doesNotFinishAndShowsHint() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val activity = controller.get()
        assertFalse("首页初始不应处于结束状态", activity.isFinishing)

        activity.onBackPressedDispatcher.onBackPressed()

        assertFalse("按返回键不应退出桌面", activity.isFinishing)
        assertEquals(
            "按返回键应给一句提示",
            app.getString(R.string.home_back_blocked),
            ShadowToast.getTextOfLatestToast()
        )
        controller.close()
    }

    // ---------- P0-2 TTS 中文数据状态 ----------

    @Test
    fun tts_marksLangMissing_whenEngineHasNoChinese() {
        // 未注册任何语言 → 引擎把中文判为 LANG_NOT_SUPPORTED，与"没装中文数据包"同路径
        TtsManager.init(app)
        driveTtsInit()

        assertTrue("引擎应初始化成功，lastError=${TtsManager.lastError}", TtsManager.isReady())
        assertTrue(
            "中文不可用时必须把 langMissing 置为 true（旧实现会被下一行重置成 false）",
            TtsManager.langMissing
        )
    }

    @Test
    fun tts_langMissingFalse_whenChineseAvailable() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.SIMPLIFIED_CHINESE)
        TtsManager.init(app)
        driveTtsInit()

        assertTrue("引擎应初始化成功，lastError=${TtsManager.lastError}", TtsManager.isReady())
        assertFalse("中文可用时不应误报缺数据", TtsManager.langMissing)
    }

    // ---------- P0-3 播报文案 ----------

    @Test
    fun announceStrings_areFormattedFromOneSource() {
        assertEquals("电量不足，请插上充电器", app.getString(R.string.ann_low_battery))
    }

    @Test
    fun lowBattery_announcesResourceText() {
        Prefs.set(app, Prefs.ANN_LOW, true)

        BatteryReceiver().onReceive(app, Intent(Intent.ACTION_BATTERY_LOW))
        driveTtsInit()

        assertEquals(
            app.getString(R.string.ann_low_battery),
            lastSpoken()
        )
    }

    // ---------- P0-4 头像缓存 ----------

    @Test
    fun avatarCache_hitsUntilInvalidated() {
        val slot = 2
        LocalContacts.avatarFile(app, slot).writeBytes(PNG_1X1)
        LocalContacts.invalidateAvatarCache()

        val first = LocalContacts.loadAvatar(app, slot)
        assertNotNull("有头像文件时应解出位图", first)
        val second = LocalContacts.loadAvatar(app, slot)
        assertSame("第二次读取应命中内存缓存，不再解码", first, second)

        // 覆盖头像后缓存必须失效，否则桌面会一直显示旧照片
        val newBmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        LocalContacts.saveAvatar(app, slot, newBmp)
        val third = LocalContacts.loadAvatar(app, slot)
        assertNotSame("保存新头像后应重新解码", first, third)

        LocalContacts.delete(app, slot)
        assertNull("删除后不应再读到头像", LocalContacts.loadAvatar(app, slot))
    }

    @Test
    fun avatarCache_invalidatedBySwap() {
        val a = 0
        val b = 1
        LocalContacts.avatarFile(app, a).writeBytes(PNG_1X1)
        LocalContacts.invalidateAvatarCache()

        val beforeA = LocalContacts.loadAvatar(app, a)
        assertNotNull(beforeA)
        assertSame("缓存应命中", beforeA, LocalContacts.loadAvatar(app, a))

        LocalContacts.set(app, a, LocalContacts.Contact("甲", "10010"))
        LocalContacts.set(app, b, LocalContacts.Contact("乙", "10086"))
        LocalContacts.swap(app, a, b)

        assertEquals("交换后电话应互换", "10086", LocalContacts.get(app, a)?.phone)
        val afterA = LocalContacts.loadAvatar(app, a)
        assertNotSame("交换头像后缓存应失效", beforeA, afterA)

        // 复原，避免影响其它用例
        LocalContacts.delete(app, a)
        LocalContacts.delete(app, b)
    }
}
