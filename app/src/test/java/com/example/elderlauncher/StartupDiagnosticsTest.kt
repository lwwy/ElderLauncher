package com.example.elderlauncher

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.elderlauncher.core.CrashLogger
import com.example.elderlauncher.core.ElderApp
import com.example.elderlauncher.core.StartupDiagnostics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v1.2.16「无头闹钟唤醒误触发诊断轨迹导出」的回归测试。
 *
 * 守护的不变量：
 *  - 无头进程（只创建 ElderApp）不置 session_pending、不写启动轨迹、不导出诊断；
 *  - 真实 UI 启动（StartupDiagnostics.onUiStart）才写轨迹、置标记、读上次异常并导出；
 *  - 「先读旧值再置新值」的顺序不会把异常信息吃掉；
 *  - 桌面显示后 markSessionReady 清零，此后任意次无头唤醒都不留下误报。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StartupDiagnosticsTest {

    private lateinit var app: Application
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private fun traceFile(): File = File(CrashLogger.dirFor(app), "trace.log")
    private fun pending(): Boolean = CrashLogger.lastSessionAbnormal(app)

    /** 统计启动轨迹条数：用于验证无头进程不会追加刷屏，而不是断言一个恒不存在的字符串 */
    private fun startupTraceCount(): Int {
        val text = if (traceFile().exists()) traceFile().readText() else ""
        return text.split("==== 应用启动 ====").size - 1
    }

    private fun clearSessionPrefs() {
        app.getSharedPreferences("elder_crash_debug", Application.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        clearSessionPrefs()
        CrashLogger.init(app) // 安装 appContext，轨迹才能落盘
        traceFile().delete()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        clearSessionPrefs()
        traceFile().delete()
    }

    /** 模拟无头进程：系统拉起 Application，但从不创建任何 Activity */
    private fun runHeadlessProcess() {
        val elderApp = ElderApp()
        elderApp.attachBaseContext(app)
        elderApp.onCreate()
    }

    @Test
    fun headlessProcess_doesNotSetSessionPending() {
        runHeadlessProcess()
        assertFalse("无头进程不应标记 UI 启动尝试", pending())
    }

    @Test
    fun headlessProcess_doesNotWriteStartupTrace() {
        runHeadlessProcess()
        val text = if (traceFile().exists()) traceFile().readText() else ""
        assertFalse(
            "无头进程不应写启动轨迹，否则每次闹钟唤醒都会刷屏并挤掉崩溃信息",
            text.contains("==== 应用启动 ====")
        )
    }

    @Test
    fun uiStart_writesTraceAndSetsPending() {
        StartupDiagnostics.onUiStart(app)
        assertTrue("UI 启动应标记本次会话", pending())
        assertTrue("UI 启动应写入环境轨迹", traceFile().readText().contains("==== 应用启动 ===="))
    }

    @Test
    fun uiStart_thenHomeVisible_clearsPending() {
        StartupDiagnostics.onUiStart(app)
        assertTrue(pending())
        CrashLogger.markSessionReady()
        assertFalse("桌面显示后必须清零，否则下次启动会误判为异常", pending())
    }

    @Test
    fun uiStart_detectsPreviousAbnormalSessionAndRemarks() {
        // 模拟「上次 UI 启动没走到 onResume 就被强杀」
        CrashLogger.markSessionStart()
        assertTrue(pending())

        StartupDiagnostics.onUiStart(app)

        val text = traceFile().readText()
        assertTrue("应记录上次异常结束", text.contains("检测到上次启动未完成"))
        assertTrue("本次启动必须重新置位，不能把异常标记吃掉", pending())
    }

    @Test
    fun fullUiCycle_thenHeadlessWakeups_leaveNoFalsePositive() {
        // 一次完整的 UI 启动
        StartupDiagnostics.onUiStart(app)
        CrashLogger.markSessionReady()
        val before = startupTraceCount()
        // 之后闹钟唤醒若干次（无头进程）
        repeat(3) { runHeadlessProcess() }
        assertFalse("无头唤醒后仍应保持干净态，下次启动不应误报", pending())
        assertEquals("无头唤醒不应追加启动轨迹（把轨迹写回 init 会立刻被此用例抓到）",
            before, startupTraceCount())
    }
}
