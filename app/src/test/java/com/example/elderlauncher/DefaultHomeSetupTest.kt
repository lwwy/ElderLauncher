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
 * 「设为默认桌面」点击无反应修复的回归测试。
 *
 * 根因：v1.2.3 把原本一直正常的 ACTION_HOME_SETTINGS 换成优先 RoleManager 角色申请，
 * 而该 intent 在 HyperOS 上「能启动却不弹框」，且能骗过 resolveActivity 校验，
 * 导致「取首个可启动入口」永远停在它上面。守护的不变量：
 *  1. 候选链以 home_settings 打头、顺序固定，且**绝不含 role**；
 *  2. 首次点击就走 home_settings（与 v1.0.9 ~ v1.2.2 行为一致），并有可操作引导；
 *  3. 已是默认桌面时不启动任何入口，但给出明确提示；
 *  4. 首个入口不可用时顺延；所有入口不可用也绝不静默失败；
 *  5. 上次入口没生效时，二次点击换一条分支（优先 default_apps），不重复同一条；
 *  6. 从系统设置返回检测到角色变为默认，成功反馈只提示一次并重置分支状态。
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
    fun setupOptionsStartWithHomeSettingsAndNeverIncludeRole() {
        // 全部候选都可解析时，顺序必须固定为老版本的稳定路径，且不能混入 RoleManager
        val options = HomeRole.defaultHomeSetupOptions(app) { true }

        assertEquals(
            listOf("home_settings", "default_apps", "app_details"),
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
    fun secondClickSwitchesAwayFromFirstEntryAndPrefersDefaultApps() {
        val a = activity()
        drainIntents(a)
        val full = listOf(homeSettings, defaultApps, appDetails)

        // 第一次：打开 HOME_SETTINGS（模拟该页没有桌面选项，用户返回后仍未成为默认）
        a.requestDefaultHome(false, full)
        drainIntents(a)
        a.onDefaultHomeStateChanged(false)
        ShadowToast.reset()

        // 第二次：必须换一条路，优先国产 ROM 里真正能选桌面的「默认应用」设置
        val used = a.requestDefaultHome(false, full)

        assertEquals("default_apps", used)
        val started = drainIntents(a)
        assertTrue(
            "二次点击应打开系统「默认应用」设置",
            started.any { it.action == "test.action.DEFAULT_APPS" }
        )
        assertFalse(
            "二次点击不得重复上次那条没生效的 HOME_SETTINGS",
            started.any { it.action == "test.action.HOME_SETTINGS" }
        )
    }

    @Test
    fun secondClickNeverRepeatsTheSameBranch() {
        val a = activity()
        drainIntents(a)

        // 首次只给出 default_apps（模拟 HOME_SETTINGS 在这台 ROM 上不可解析）
        a.requestDefaultHome(false, listOf(defaultApps, appDetails))
        drainIntents(a)
        a.onDefaultHomeStateChanged(false)

        // 二次点击即使拿回完整候选链，也不能再走上次那条
        val used = a.requestDefaultHome(false, listOf(homeSettings, defaultApps, appDetails))

        assertEquals("home_settings", used)
        assertFalse(
            "二次点击不得重复上次的 default_apps",
            drainIntents(a).any { it.action == "test.action.DEFAULT_APPS" }
        )
    }

    @Test
    fun firstCandidateUnavailableFallsThroughToNext() {
        val a = activity()
        drainIntents(a)

        // 只给后两级候选，验证仍会启动其中的第一项（不会因缺首选而静默）
        val used = a.requestDefaultHome(false, listOf(defaultApps, appDetails))

        assertEquals("default_apps", used)
        assertTrue(drainIntents(a).any { it.action == "test.action.DEFAULT_APPS" })
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
    fun successFeedbackResetsBranchForFreshStart() {
        val a = activity()
        drainIntents(a)
        val full = listOf(homeSettings, defaultApps, appDetails)

        a.onDefaultHomeStateChanged(false)
        a.requestDefaultHome(false, full)
        drainIntents(a)
        a.onDefaultHomeStateChanged(true) // 从系统设置返回，设置成功 → 重置分支
        a.onDefaultHomeStateChanged(false) // 之后又被改回非默认
        ShadowToast.reset()
        drainIntents(a)

        // 新一轮点击应从首选重新开始，而不是停留在上次的 secondary 分支
        assertEquals("home_settings", a.requestDefaultHome(false, full))
    }

    @Test
    fun setupOptionsFilterKeepsOnlyResolvableEntries() {
        // 注入 resolvable，验证候选链会剔除系统打不开的入口
        val options = HomeRole.defaultHomeSetupOptions(app) { intent ->
            intent.action == Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
        }
        assertEquals(1, options.size)
        assertEquals("default_apps", options.first().key)
    }
}
