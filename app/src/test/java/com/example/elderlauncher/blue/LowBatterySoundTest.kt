package com.example.elderlauncher.blue

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.elderlauncher.blue.core.Diagnostics
import com.example.elderlauncher.blue.core.LowBatteryAnnouncer
import com.example.elderlauncher.blue.core.LowBatterySound
import com.example.elderlauncher.blue.core.TtsManager
import com.example.elderlauncher.blue.data.Prefs
import com.example.elderlauncher.blue.ui.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * v1.2.7「低电量播报支持本地音频」回归测试。
 *
 * 守护的不变量：
 *  - 未选择文件时行为与老版本完全一致：系统 TTS、去重计数不变；
 *  - 选了文件走自定义音源；播放失败 / 文件失效自动回退 TTS，绝不静默失败；
 *  - 单个音频 10MB 上限，超限拒绝且不覆盖已有的好文件；
 *  - SAF 选文件免存储权限，Manifest 不得新增任何存储/受限权限；
 *  - 提示音状态进入诊断日志；设置页新增控件与文案正确接线。
 *
 * 说明：Robolectric 不能真播 MediaPlayer，音源决策用纯逻辑 [LowBatterySound.resolveRoute]
 * 覆盖，真机靠试听按钮验证声音。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LowBatterySoundTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        LowBatterySound.clear(app)
        LowBatteryAnnouncer.resetForTest()
        TtsManager.shutdown()
        android.provider.Settings.Secure.putString(
            app.contentResolver,
            android.provider.Settings.Secure.TTS_DEFAULT_SYNTH,
            "com.test.tts"
        )
        Prefs.set(app, Prefs.ANN_LOW, true)
    }

    @After
    fun tearDown() {
        LowBatterySound.clear(app)
        LowBatteryAnnouncer.resetForTest()
        TtsManager.shutdown()
    }

    private fun saveBytes(bytes: ByteArray, name: String? = "提示音.mp3") =
        LowBatterySound.saveFromStream(app, ByteArrayInputStream(bytes), name)

    // ---------------------------------------------------------- 音源分支纯逻辑

    @Test
    fun route_noCustom_alwaysTts() {
        assertEquals(LowBatterySound.Route.TTS, LowBatterySound.resolveRoute(false, false))
        assertEquals(
            "没有自定义文件时 playOk 无意义，仍必须走 TTS",
            LowBatterySound.Route.TTS, LowBatterySound.resolveRoute(false, true)
        )
    }

    @Test
    fun route_customPlayOk_usesCustom() {
        assertEquals(LowBatterySound.Route.CUSTOM, LowBatterySound.resolveRoute(true, true))
    }

    @Test
    fun route_customPlayFailed_fallsBackToTts() {
        assertEquals(
            "自定义播放失败必须回退 TTS，绝不静默失败",
            LowBatterySound.Route.FALLBACK, LowBatterySound.resolveRoute(true, false)
        )
    }

    // ---------------------------------------------------------- 复制与大小上限

    @Test
    fun copy_smallFile_isOk() {
        val dest = File(app.cacheDir, "copy_small.tmp")
        val outcome = LowBatterySound.copyWithLimit(
            ByteArrayInputStream(ByteArray(1024)), dest, 10_000
        )
        assertEquals(LowBatterySound.CopyOutcome.OK, outcome)
        assertEquals(1024L, dest.length())
        dest.delete()
    }

    @Test
    fun copy_overLimit_isRejected() {
        val dest = File(app.cacheDir, "copy_large.tmp")
        val outcome = LowBatterySound.copyWithLimit(
            ByteArrayInputStream(ByteArray(200)), dest, 100
        )
        assertEquals(LowBatterySound.CopyOutcome.TOO_LARGE, outcome)
        dest.delete()
    }

    @Test
    fun copy_emptyInput_isFailed() {
        val dest = File(app.cacheDir, "copy_empty.tmp")
        assertEquals(
            LowBatterySound.CopyOutcome.FAILED,
            LowBatterySound.copyWithLimit(ByteArrayInputStream(ByteArray(0)), dest, 100)
        )
        dest.delete()
    }

    @Test
    fun save_overMaxBytes_isRejectedAndKeepsExistingFile() {
        val first = ByteArray(64) { 7 }
        assertEquals(LowBatterySound.SaveResult.OK, saveBytes(first, "原提示音.mp3"))

        // 造一个刚好超过 10MB 上限的输入，确认被拒绝且不会覆盖已有的好文件
        val tooBig = ByteArray((LowBatterySound.MAX_BYTES + 1).toInt())
        assertEquals(
            "超过 10MB 必须拒绝保存",
            LowBatterySound.SaveResult.TOO_LARGE,
            LowBatterySound.saveFromStream(app, ByteArrayInputStream(tooBig), "超大.mp3")
        )
        assertTrue("超限保存失败后原有的提示音必须还在", LowBatterySound.hasCustom(app))
        assertEquals("原提示音.mp3", LowBatterySound.displayName(app))
        assertEquals(
            "原文件内容不得被破坏",
            first.toList(), LowBatterySound.toneFile(app).readBytes().toList()
        )
    }

    // ---------------------------------------------------------- 保存 / 清除 / 展示

    @Test
    fun save_ok_hasCustomAndDisplayName() {
        assertEquals(LowBatterySound.SaveResult.OK, saveBytes(ByteArray(128), "生日提醒.m4a"))
        assertTrue(LowBatterySound.hasCustom(app))
        assertEquals("生日提醒.m4a", LowBatterySound.displayName(app))
    }

    @Test
    fun clear_restoresSystemVoice() {
        saveBytes(ByteArray(128))
        assertTrue(LowBatterySound.hasCustom(app))
        LowBatterySound.clear(app)
        assertFalse(LowBatterySound.hasCustom(app))
        assertNull(LowBatterySound.displayName(app))
        assertFalse(LowBatterySound.toneFile(app).exists())
        assertEquals("系统语音", LowBatterySound.statusLabel(app))
    }

    @Test
    fun statusLabel_defaultAndCustom_neverLeaksOriginalFileName() {
        assertEquals("系统语音", LowBatterySound.statusLabel(app))
        saveBytes(ByteArray(64), "我的提示音.wav")
        val label = LowBatterySound.statusLabel(app)
        assertFalse(
            "诊断日志用的状态描述不得写入用户所选音频的原始文件名",
            label.contains("我的提示音.wav")
        )
        assertEquals("自定义音频.mp3", label)
        // 设置页 UI 仍应能拿到真实文件名（本机可见，不导出）
        assertEquals("我的提示音.wav", LowBatterySound.displayName(app))
    }

    @Test
    fun prefs_stringRoundTrip() {
        assertNull(Prefs.getString(app, Prefs.LOW_TONE_NAME))
        Prefs.setString(app, Prefs.LOW_TONE_NAME, "abc.mp3")
        assertEquals("abc.mp3", Prefs.getString(app, Prefs.LOW_TONE_NAME))
        Prefs.setString(app, Prefs.LOW_TONE_NAME, null)
        assertNull(Prefs.getString(app, Prefs.LOW_TONE_NAME))
    }

    // ---------------------------------------------------------- LowBatteryAnnouncer 接线

    @Test
    fun announcer_noCustom_usesTtsAndCountsOnce() {
        LowBatteryAnnouncer.onBatteryChanged(app, 18, false)
        assertEquals(
            "未选择文件时必须仍走系统 TTS（老版本行为不变）",
            LowBatterySound.Route.TTS, LowBatteryAnnouncer.lastRoute
        )
        assertEquals(1, LowBatteryAnnouncer.announceCount)
    }

    @Test
    fun announcer_withCustom_neverSilentlyStaysOnTtsRoute() {
        saveBytes(ByteArray(128))
        LowBatteryAnnouncer.onBatteryChanged(app, 18, false)
        assertNotEquals(
            "有自定义文件时不得仍按「无自定义」的 TTS 分支处理",
            LowBatterySound.Route.TTS, LowBatteryAnnouncer.lastRoute
        )
        assertEquals(
            "无论用哪种音源，去重计数语义必须保持不变",
            1, LowBatteryAnnouncer.announceCount
        )
    }

    // ---------------------------------------------------------- 诊断日志 / 权限 / UI

    @Test
    fun diagnostics_includesToneStatus_butNotOriginalFileName() {
        assertTrue(
            "诊断日志必须能看到当前音源",
            Diagnostics.buildReport(app).contains("低电量提示音: 系统语音")
        )
        saveBytes(ByteArray(64), "自定义音.mp3")
        val report = Diagnostics.buildReport(app)
        assertTrue("诊断日志仍须标明当前是自定义音源", report.contains("自定义音频.mp3"))
        assertFalse(
            "诊断日志不得写入用户所选音频的原始文件名（可能含个人信息）",
            report.contains("自定义音.mp3")
        )
    }

    @Test
    fun manifest_noStorageOrRestrictedPermissionAdded() {
        val pi = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        val requested = pi.requestedPermissions?.toList() ?: emptyList()
        // SAF 的 OpenDocument 免存储权限，本功能不得引入任何新权限
        assertFalse(requested.contains(android.Manifest.permission.READ_EXTERNAL_STORAGE))
        assertFalse(requested.contains(android.Manifest.permission.WRITE_EXTERNAL_STORAGE))
        assertFalse(requested.contains("android.permission.READ_MEDIA_AUDIO"))
        assertFalse(requested.contains(android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS))
    }

    @Test
    fun settingsUi_hasPickAndResetButtonsAndDefaultLabel() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity.findViewById<android.view.View>(R.id.btnPickTone))
        assertNotNull(activity.findViewById<android.view.View>(R.id.btnResetTone))
        val label = activity.findViewById<android.widget.TextView>(R.id.tvLowToneStatus).text.toString()
        assertTrue(
            "默认应显示「系统语音」",
            label == app.getString(R.string.low_tone_status_system)
        )
        controller.close()
    }

    @Test
    fun strings_newCopyIsComplete() {
        assertTrue(app.getString(R.string.btn_pick_tone).isNotBlank())
        assertTrue(app.getString(R.string.btn_reset_tone).isNotBlank())
        assertTrue("提示语需说明上限", app.getString(R.string.low_tone_tip).contains("10MB"))
        assertTrue(app.getString(R.string.low_tone_too_large).contains("10MB"))
    }
}
