package com.example.elderlauncher

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.elderlauncher.core.HomeRole
import com.example.elderlauncher.ui.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * 「设为默认桌面」入口的回归测试。
 *
 * 守护的不变量：
 *  1. 候选链以 home_settings 打头，且**绝不含 role / default_apps** —— 前者会被
 *     HyperOS 静默拦截，后者打开的「默认应用」页实测只有电话/短信/浏览器等条目、
 *     完全没有桌面选项，作为兜底会把用户带进死路；
 *  2. 每次点击（首次、二次、三次）都打开同一个「默认主屏幕」设置页，不再换路；
 *  3. 已是默认桌面时不启动任何入口，但给出明确提示；
 *  4. 首选不可解析时顺延；所有入口不可用也绝不静默失败；
 *  5. 引导/手动文案指向真正能勾选桌面的页面，不再叫用户「换一个设置页」；
 *  6. 从系统设置返回检测到角色变为默认，成功反馈只提示一次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DefaultHomeSetupTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ShadowToast.reset()
    }

    private fun activity(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

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

    private val homeSettings =
        HomeRole.HomeSetupOption("home_settings", Intent("test.action.HOME_SETTINGS"))
    private val defaultApps =
        HomeRole.HomeSetupOption("default_apps", Intent("test.action.DEFAULT_APPS"))
    private val appDetails =
        HomeRole.HomeSetupOption("app_details", Intent("test.action.APP_DETAILS"))

    @Test
    fun setupOptionsStartWithHomeSettingsAndExcludeDeadEnds() {
        // 全部候选都可解析时，顺序固定，且不能混入 RoleManager 或无效的「默认应用」页
        val options = HomeRole.defaultHomeSetupOptions(app) { true }

        assertEquals(
            listOf("home_settings", "app_details"),
            options.map { it.key }
        )
        assertEquals(
            "首选必须是系统「默认主屏幕」设置页",
            Settings.ACTION_HOME_SETTINGS,
            options.first().intent.action
        )
        assertFalse(
            "候选链不得包含会被 ROM 静默拦截的 RoleManager 入口",
            options.any { it.key == "role" }
        )
        assertFalse(
            "候选链不得包含没有桌面选项的 default_apps 入口",
            options.any { it.key == "default_apps" }
        )
    }

    @Test
    fun firstClickOpensHomeSettingsAndGuidesUser() {
        val a = activity()
        drainIntents(a)
        ShadowToast.reset()

        val used = a.requestDefaultHome(false, listOf(homeSettings, defaultApps, appDetails))

        assertEquals("首次点击应直接打开系统「默认主屏幕」设置页", "home_settings", used)
        assertTrue(
            drainIntents(a).any { it.action == "test.action.HOME_SETTINGS" }
        )
        assertFalse(
            "首次点击不得启动任何 RoleManager 入口",
            drainIntents(a).any { it.action == "test.action.REQUEST_ROLE" }
        )
        assertNotNull("启动后必须给出可操作的引导提示", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun alreadyDefaultOnlyShowsHintAndStartsNothing() {
        val a = activity()
        drainIntents(a)
        ShadowToast.reset()

        val used = a.requestDefaultHome(true, listOf(homeSettings, defaultApps))

        assertNull("已是默认桌面时不应启动任何入口", used)
        assertTrue("已是默认桌面时不应启动 Activity", drainIntents(a).isEmpty())
        assertEquals(
            app.getString(R.string.default_home_already),
            ShadowToast.getTextOfLatestToast()
        )
    }

    @Test
    fun repeatedClicksAlwaysRetryHomeSettingsAndNeverSwitchPage() {
        val a = activity()
        drainIntents(a)
        // 即使调用方仍传入含 default_apps 的候选链，也永远只走首选那一个设置页
        val options = listOf(homeSettings, defaultApps, appDetails)

        repeat(3) { round ->
            val used = a.requestDefaultHome(false, options)
            assertEquals("第 ${round + 1} 次点击仍应打开「默认主屏幕」设置页", "home_settings", used)
            val started = drainIntents(a)
            assertTrue(
                "第 ${round + 1} 次点击必须启动 home_settings",
                started.any { it.action == "test.action.HOME_SETTINGS" }
            )
            assertFalse(
                "任何一次点击都不得把用户带到没有桌面选项的「默认应用」页",
                started.any { it.action == "test.action.DEFAULT_APPS" }
            )
        }
    }

    @Test
    fun secondClickDoesNotOpenAnotherSettingsPage() {
        val a = activity()
        drainIntents(a)

        a.requestDefaultHome(false, listOf(homeSettings, appDetails))
        assertEquals(
            "首次点击只应启动 1 个 Activity",
            1,
            drainIntents(a).size
        )
        a.onDefaultHomeStateChanged(false) // 从设置页返回，仍未成为默认
        ShadowToast.reset()

        val used = a.requestDefaultHome(false, listOf(homeSettings, appDetails))
        assertEquals("二次点击仍是同一条路径", "home_settings", used)
        val started = drainIntents(a)
        assertEquals("二次点击也只启动 1 个页面", 1, started.size)
        assertEquals("test.action.HOME_SETTINGS", started.first().action)
    }

    @Test
    fun firstCandidateUnavailableFallsThroughToNext() {
        val a = activity()
        drainIntents(a)

        // 只给最后兜底，验证仍会启动（不会因缺首选而静默）
        val used = a.requestDefaultHome(false, listOf(appDetails))

        assertEquals("app_details", used)
        assertTrue(drainIntents(a).any { it.action == "test.action.APP_DETAILS" })
    }

    @Test
    fun noUsableEntryStillGivesVisibleFeedback() {
        val a = activity()
        drainIntents(a)
        ShadowToast.reset()

        val used = a.requestDefaultHome(false, emptyList())

        assertNull(used)
        assertEquals(
            app.getString(R.string.default_home_manual),
            ShadowToast.getTextOfLatestToast()
        )
    }

    @Test
    fun becomingDefaultShowsSuccessFeedbackOnlyOnce() {
        val a = activity()
        a.onDefaultHomeStateChanged(false)
        ShadowToast.reset()

        assertTrue(a.onDefaultHomeStateChanged(true))
        assertEquals(
            app.getString(R.string.default_home_success),
            ShadowToast.getTextOfLatestToast()
        )

        // 状态没变化时不重复提示
        ShadowToast.reset()
        assertFalse(a.onDefaultHomeStateChanged(true))
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun guideTextPointsToTheRealSettingPageAndNoLongerSuggestsSwitching() {
        val guide = app.getString(R.string.default_home_guide)
        val manual = app.getString(R.string.default_home_manual)

        assertTrue("引导文案应提示在列表中勾选", guide.contains("勾选"))
        assertTrue("引导文案应提示重试而非换页", guide.contains("重试"))
        assertFalse("引导文案不得再叫用户换一个设置页", guide.contains("换一个设置页"))

        assertTrue("手动文案应指向真正能设桌面的页面", manual.contains("默认主屏幕"))
        assertFalse(
            "手动文案不得再指向没有桌面选项的「默认应用 → 桌面」",
            manual.contains("默认应用 → 桌面")
        )
    }

    @Test
    fun setupOptionsFilterKeepsOnlyResolvableEntries() {
        // 注入 resolvable，验证候选链会剔除系统打不开的入口
        val options = HomeRole.defaultHomeSetupOptions(app) { intent ->
            intent.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        }
        assertEquals(1, options.size)
        assertEquals("app_details", options.first().key)
    }
}
