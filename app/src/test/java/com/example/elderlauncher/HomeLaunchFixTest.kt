package com.example.elderlauncher

import android.annotation.SuppressLint
import android.app.Application
import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.elderlauncher.core.HomeRole
import com.example.elderlauncher.ui.HomeActivity
import com.example.elderlauncher.ui.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 桌面切换动画修复（方案 A）的回归测试。
 *
 * 环境说明：Robolectric 里 HOME intent 会解析到本应用（manifest 声明了 HOME），
 * 所以「当前是否默认桌面」的绝对值由测试环境决定，不能写死。测试改为验证
 * 「策略确实按 HomeRole 判定接线」+「显式驱动非默认桌面分支」，
 * 与真机上系统桌面被设为默认时的行为一致。
 *
 * 守护的不变量：
 *  1. 默认桌面判定口径统一（HOME intent 的 resolveActivity 结果）；
 *  2. 非默认桌面时返回键交还系统，不再一刀切吞掉；
 *  3. singleTask 复用走 onNewIntent，不得重启 Activity / 重播转场；
 *  4. onDestroy 必须取消残留的 TTS 延迟预热；
 *  5. 「恢复系统桌面」不再调用失效 API、尤其**绝不能再发 CATEGORY_HOME intent**
 *     （本应用仍声明 HOME，该 intent 会被解析回自己，是切桌面时界面闪回的根因）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeLaunchFixTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    /** 从 HomeActivity 取私有字段（用声明类而非运行时类，避免 Robolectric 子类干扰） */
    private fun homeField(any: Any, name: String): Any {
        val f = HomeActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        return f.get(any)!!
    }

    /** 弹出并记录 Activity 启动过的所有 Intent */
    private fun drainIntents(a: Activity): List<Intent> {
        val out = mutableListOf<Intent>()
        var i = shadowOf(a).nextStartedActivity
        while (i != null) {
            out.add(i)
            i = shadowOf(a).nextStartedActivity
        }
        return out
    }

    // ---------------------------------------------------------- HomeRole 判定

    @Test
    fun homeRole_nullResolvedPackageIsNotDefault() {
        assertFalse(HomeRole.isDefaultHome(null, "com.example.elderlauncher"))
    }

    @Test
    fun homeRole_otherPackageIsNotDefault() {
        assertFalse(HomeRole.isDefaultHome("com.miui.home", "com.example.elderlauncher"))
    }

    @Test
    fun homeRole_selfPackageIsDefault() {
        assertTrue(
            HomeRole.isDefaultHome("com.example.elderlauncher", "com.example.elderlauncher")
        )
    }

    @Test
    fun homeRole_roleQueriesDoNotCrashWithoutRoleManager() {
        // Robolectric 环境没有真实 RoleManager 服务，两条路径都必须安全降级
        assertFalse(HomeRole.holdsHomeRole(app))
        // 可能返回 null（无角色服务），也可能返回系统 intent，只要求不抛异常
        HomeRole.requestDefaultHomeIntent(app)
    }

    // ------------------------------------------- HomeActivity 返回键 / 复用 / 预热

    /** 接线级：onCreate 真的按 HomeRole 判定设置了拦截（不写死绝对值） */
    @Test
    fun homeActivity_backInterceptFollowsHomeRoleOnCreate() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val a = controller.get()
        assertEquals(
            "onCreate 必须按 HomeRole 判定设置返回键拦截",
            a.shouldInterceptBack(), a.isBackInterceptEnabled()
        )
        controller.close()
    }

    /** 核心回归：显式驱动「非默认桌面」与「是默认桌面」两种角色 */
    @Test
    fun homeActivity_nonDefaultHomeDisablesBackIntercept() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val a = controller.get()

        a.applyBackIntercept(false)
        assertFalse(
            "非默认桌面时必须关闭拦截，把返回键交还系统默认行为（否则动画不连贯）",
            a.isBackInterceptEnabled()
        )

        a.applyBackIntercept(true)
        assertTrue(
            "是默认桌面时必须拦截，避免老人误退到陌生系统桌面",
            a.isBackInterceptEnabled()
        )
        controller.close()
    }

    @Test
    fun homeActivity_onResumeRefreshesBackIntercept() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val a = controller.get()
        val expected = a.shouldInterceptBack()

        // 人为置反，模拟「角色在系统设置里被改过」；onResume 必须重新判定
        a.applyBackIntercept(!expected)
        assertEquals(!expected, a.isBackInterceptEnabled())

        controller.pause().resume()

        assertEquals(
            "从设置/系统桌面返回时，返回键拦截必须按当前角色即时刷新",
            expected, a.isBackInterceptEnabled()
        )
        controller.close()
    }

    @Test
    fun homeActivity_onNewIntentReusesInstanceWithoutRelaunch() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val a = controller.get()
        drainIntents(a) // 清空冷启动阶段（权限申请等）产生的启动记录

        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        a.onNewIntent(intent)

        assertEquals("复用实例应更新 intent", Intent.ACTION_MAIN, a.intent.action)
        assertTrue("复用时应带上 HOME category", a.intent.hasCategory(Intent.CATEGORY_HOME))
        assertTrue(
            "onNewIntent 不得启动任何新 Activity（否则会重播转场动画）",
            drainIntents(a).isEmpty()
        )
        assertEquals(
            "复用后返回键策略仍须与当前角色一致",
            a.shouldInterceptBack(), a.isBackInterceptEnabled()
        )
        controller.close()
    }

    @SuppressLint("NewApi")
    @Test
    fun homeActivity_destroyCancelsPendingTtsWarmup() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val a = controller.get()
        val handler = homeField(a, "handler") as Handler
        val warmup = homeField(a, "ttsWarmupRunnable") as Runnable
        assertTrue("onCreate 应排入一次 TTS 延迟预热", handler.hasCallbacks(warmup))

        controller.pause().stop().destroy()

        assertFalse(
            "onDestroy 必须取消待执行的预热任务，避免实例重建后重复初始化",
            handler.hasCallbacks(warmup)
        )
    }

    // -------------------------------- SettingsActivity 恢复系统桌面（核心）

    @Test
    fun settings_restoreWhenDefaultOpensHomeSettingsOnly() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val a = controller.get()
        drainIntents(a)

        a.restoreSystemHome(true)
        val intents = drainIntents(a)

        assertTrue(
            "是默认桌面时应引导到系统「默认主屏幕」设置",
            intents.any { it.action == Settings.ACTION_HOME_SETTINGS }
        )
        assertFalse(
            "绝不能再发 CATEGORY_HOME intent（会被解析回自己、导致界面闪回）",
            intents.any { it.hasCategory(Intent.CATEGORY_HOME) }
        )
        controller.close()
    }

    @Test
    fun settings_restoreWhenNotDefaultStartsNothing() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val a = controller.get()
        drainIntents(a)

        a.restoreSystemHome(false)

        assertTrue("已不是默认桌面时不应启动任何 Activity", drainIntents(a).isEmpty())
        controller.close()
    }

    @Test
    fun settings_restoreDefaultArgumentUsesHomeRole() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val a = controller.get()
        drainIntents(a)

        val isDefault = HomeRole.isDefaultHome(a)
        a.restoreSystemHome()
        val intents = drainIntents(a)

        if (isDefault) {
            assertTrue(
                "默认参数应依据 HomeRole 走「引导系统设置」分支",
                intents.any { it.action == Settings.ACTION_HOME_SETTINGS }
            )
        } else {
            assertTrue(
                "默认参数应依据 HomeRole 走「无需操作」分支",
                intents.isEmpty()
            )
        }
        assertFalse(
            "任何分支都不得发出 CATEGORY_HOME intent",
            intents.any { it.hasCategory(Intent.CATEGORY_HOME) }
        )
        controller.close()
    }

    /** 两个分支合起来复查一次：一次都不许出现 HOME intent */
    @Test
    fun settings_neverEmitsHomeCategoryIntent() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val a = controller.get()
        drainIntents(a) // 清空 onCreate 阶段的权限申请等启动记录

        a.restoreSystemHome(true)
        a.restoreSystemHome(false)
        val intents = drainIntents(a)

        assertTrue(
            "应至少产生一次系统主屏幕设置引导",
            intents.any { it.action == Settings.ACTION_HOME_SETTINGS }
        )
        assertFalse(
            "恢复系统桌面的任何分支都不得发出 CATEGORY_HOME intent",
            intents.any { it.hasCategory(Intent.CATEGORY_HOME) }
        )
        assertFalse(
            "也不得发出 ACTION_MAIN 的桌面启动 intent",
            intents.any { it.action == Intent.ACTION_MAIN }
        )
        controller.close()
    }
}
