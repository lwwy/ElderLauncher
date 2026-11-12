package com.example.elderlauncher.blue

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.elderlauncher.blue.core.CrashLogger
import com.example.elderlauncher.blue.core.Diagnostics
import com.example.elderlauncher.blue.core.LowBatterySound
import com.example.elderlauncher.blue.phone.TelecomHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * P1 隐私整改回归：日志里「绝对不能出现完整电话号码」。
 *
 * 守护的不变量：
 *  - TelecomHelper 写拨号轨迹时只留号码末 4 位（****1234）；
 *  - CrashLogger 在写入 / 读出 / 导出边界统一兜底脱敏，覆盖
 *    异常 message 里的 tel: URI、以及覆盖安装后旧版已落盘的完整号码；
 *  - 脱敏不误伤时间戳、日期、版本号、机型型号等排查信息，且幂等；
 *  - 诊断日志不写用户所选音频的原始文件名。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PrivacyRedactionTest {

    private lateinit var app: Application
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private fun traceFile(): File = File(CrashLogger.dirFor(app), "trace.log")

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        CrashLogger.init(app) // 安装 appContext，才能验证 trace() 的落盘路径
        LowBatterySound.clear(app)
        traceFile().delete()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        LowBatterySound.clear(app)
        traceFile().delete()
    }

    // ---------------------------------------------------------- 号码脱敏纯函数

    @Test
    fun maskNumber_plainMobile_keepsOnlyLast4() {
        assertEquals("****8000", TelecomHelper.maskNumber("13800138000"))
        assertEquals("****1234", TelecomHelper.maskNumber("13912341234"))
    }

    @Test
    fun maskNumber_formattedNumber_keepsOnlyLast4() {
        // 联系人里可能存成带国际区号 / 空格 / 横线的形式
        assertEquals("****8000", TelecomHelper.maskNumber("+86 138 0013 8000"))
        assertEquals("****8000", TelecomHelper.maskNumber("138-0013-8000"))
        assertEquals("****8000", TelecomHelper.maskNumber("+8613800138000"))
    }

    @Test
    fun maskNumber_neverReturnsFullNumber() {
        for (raw in listOf("13800138000", "+86 13800138000", "010-88886666", "123")) {
            val masked = TelecomHelper.maskNumber(raw)
            assertFalse("脱敏结果不得含完整号码：$raw", masked.contains(raw.filter { it.isDigit() }))
            assertTrue("必须带 **** 前缀", masked.startsWith("****"))
        }
        assertEquals("****", TelecomHelper.maskNumber(""))
    }

    @Test
    fun redact_plainMobile_isMasked() {
        val out = CrashLogger.redactPhoneNumbers("发起：13800138000 成功")
        assertFalse(out.contains("13800138000"))
        assertTrue(out.contains("****8000"))
    }

    @Test
    fun redact_telUri_isMasked() {
        // 系统异常 message 常带 dat=tel:138... 这样的完整 URI
        val msg = "ActivityNotFoundException: No Activity found to handle " +
            "Intent { act=android.intent.action.CALL dat=tel:13800138000 }"
        val out = CrashLogger.redactPhoneNumbers(msg)
        assertFalse("tel: URI 里的完整号码必须被脱敏", out.contains("13800138000"))
        assertTrue(out.contains("tel:****8000"))

        val withPlus = CrashLogger.redactPhoneNumbers("dat=tel:+8613800138000 flg=0x1")
        assertFalse(withPlus.contains("8613800138000"))
        assertTrue(withPlus.contains("tel:****8000"))
    }

    @Test
    fun redact_separatedNumber_isMasked() {
        val out = CrashLogger.redactPhoneNumbers("旧版轨迹：138-0013-8000")
        assertFalse(out.contains("138-0013-8000"))
        assertTrue(out.contains("****8000"))
    }

    @Test
    fun redact_preservesTimestampsVersionsAndModelNames() {
        // 这些是排查问题必需的信息，不能被误伤
        val samples = listOf(
            "导出时间: 2026-11-07 14:42:01",
            "[11-07 14:42:01.123] 启动完成",
            "diag-20261107-144201.txt",
            "版本: 1.2.10 (24) / 系统: Android 14 (API 34)",
            "设备: Xiaomi 22081212C",
            "低电量提示音：单个文件不超过 10MB"
        )
        for (s in samples) {
            assertEquals("不得误伤排查信息：$s", s, CrashLogger.redactPhoneNumbers(s))
        }
    }

    @Test
    fun redact_isIdempotent() {
        val once = CrashLogger.redactPhoneNumbers("拨打号码：13800138000，tail=10086")
        val twice = CrashLogger.redactPhoneNumbers(once)
        assertEquals("重复脱敏不得改变结果", once, twice)
    }

    // ---------------------------------------------------------- 写入端

    @Test
    fun trace_writtenToDisk_neverContainsFullNumber() {
        CrashLogger.trace("placeCall(SIM) 发起：13800138000")
        val text = traceFile().readText()
        assertFalse("落盘的 trace.log 不得含完整号码", text.contains("13800138000"))
        assertTrue(text.contains("****8000"))
    }

    @Test
    fun placeCall_endToEnd_neverLogsFullNumber() {
        // 无论走 SIM / ACTION_CALL 兜底 / 异常分支，写日志时都不带完整号码
        TelecomHelper.placeCall(app, "13800138000")
        val text = traceFile().readText()
        assertFalse("端到端拨号不得把完整号码写进日志", text.contains("13800138000"))
    }

    // ---------------------------------------------------------- 读出 / 导出端兜底

    @Test
    fun historicalTraceLog_isRedactedWhenRead() {
        // 模拟「用户装过旧版本」：磁盘上早已存在含完整号码的轨迹
        traceFile().writeText(
            "[11-07 14:42:01.123] placeCall(SIM) 发起：13800138000\n" +
                "[11-07 14:42:02.456] dat=tel:13900001111 失败\n"
        )
        val tail = CrashLogger.traceTail(app, 400)
        assertFalse("读出历史轨迹时必须兜底脱敏", tail.contains("13800138000"))
        assertFalse(tail.contains("13900001111"))

        val report = Diagnostics.buildReport(app)
        assertFalse("诊断报告不得带出历史完整号码", report.contains("13800138000"))
        assertFalse(report.contains("13900001111"))
        assertTrue("运行轨迹分区仍须保留", report.contains("运行轨迹"))
    }

    // ---------------------------------------------------------- 音频原始文件名

    @Test
    fun statusLabel_neverLeaksOriginalFileName() {
        assertEquals("系统语音", LowBatterySound.statusLabel(app))
        LowBatterySound.saveFromStream(app, ByteArrayInputStream(ByteArray(64)), "张三生日提醒.wav")
        assertEquals("自定义音频.mp3", LowBatterySound.statusLabel(app))
        assertFalse(LowBatterySound.statusLabel(app).contains("张三生日提醒.wav"))
    }

    @Test
    fun diagnostics_neverLeaksOriginalFileName_evenInTrace() {
        LowBatterySound.saveFromStream(app, ByteArrayInputStream(ByteArray(64)), "张三生日提醒.wav")
        val report = Diagnostics.buildReport(app)
        assertFalse("诊断报告不得出现用户音频原始文件名", report.contains("张三生日提醒.wav"))
        assertTrue(report.contains("自定义音频.mp3"))
        assertFalse("运行轨迹里也不得写真实文件名", traceFile().readText().contains("张三生日提醒.wav"))
    }
}
