package com.example.elderlauncher

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.elderlauncher.core.CrashLogger
import com.example.elderlauncher.phone.TelecomHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 拨号降级回归：拒绝 / 未授予 CALL_PHONE，或 ACTION_CALL 无可用拨号器时，
 * 必须降级到免权限的 ACTION_DIAL（系统拨号盘预填号码），绝不静默失败。
 *
 * 用一个最小接缝覆写 [Context.startActivity]：让 ACTION_CALL 抛异常，
 * 真实走一遍 [TelecomHelper.placeCall] → catch → [TelecomHelper.dial] 的链路，
 * 而不是注入 lambda 测纯函数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DialFallbackTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        CrashLogger.init(app)
        java.io.File(CrashLogger.dirFor(app), "trace.log").delete()
    }

    /** ACTION_CALL 发起失败（如无拨号器 / 权限被撤），其余 intent 只记录不真正启动 */
    private class CallFailsContext(base: Context) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) {
            if (intent.action == Intent.ACTION_CALL) {
                throw android.content.ActivityNotFoundException("no ACTION_CALL handler")
            }
            started += intent
        }
    }

    @Test
    fun placeCall_whenActionCallFails_fallsBackToActionDial() {
        val ctx = CallFailsContext(app)
        TelecomHelper.placeCall(ctx, "10086")

        assertEquals("应降级发起一次 ACTION_DIAL", 1, ctx.started.size)
        val dial = ctx.started.single()
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals("拨号盘应预填原号码", "tel:10086", dial.data.toString())
    }

    @Test
    fun dial_opensDialerWithoutAnyPermission() {
        val ctx = CallFailsContext(app)
        TelecomHelper.dial(ctx, "10086")

        val dial = ctx.started.single()
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals("tel:10086", dial.data.toString())
    }

    @Test
    fun fallback_neverLogsFullNumber() {
        val ctx = CallFailsContext(app)
        TelecomHelper.placeCall(ctx, "13800138000")
        val text = java.io.File(CrashLogger.dirFor(app), "trace.log").readText()
        assertFalse("降级链路不得把完整号码写进日志", text.contains("13800138000"))
        assertTrue(text.contains("****8000"))
    }
}
