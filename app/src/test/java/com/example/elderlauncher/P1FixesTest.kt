package com.example.elderlauncher

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.elderlauncher.core.Diagnostics
import com.example.elderlauncher.core.HomeRole
import com.example.elderlauncher.ui.HomeActivity
import com.example.elderlauncher.ui.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v1.2.6 四个 P1 + P2-5 的回归测试。
 *
 * 守护的不变量：
 *  P1-1 默认桌面判定为「ROLE_HOME 持有 或 HOME 解析到自身」任一成立，纯放宽不倒退；
 *       返回键拦截仍只走解析口径（1B，功能行为不变）；诊断日志补两条原始口径。
 *  P1-2 / P1-4：无入口的 InCallService / 拨号盘整套已删除，
 *       保留「tel: 的 DIAL/CALL 不得再解析回本应用」这条断言。
 *  P1-3 电池优化：现恢复历史版本的省电策略直达入口（带包名的 REQUEST_ action，
 *       v1.2.6 曾误改为不带包名的全局列表页），保留真状态判断、日志埋点、
 *       电池专属文案与失败兜底；Manifest 仍不声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 *       权限（只发 intent，不申请权限）。
 *  P2-5 设置页常驻提示不再写「系统弹窗」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class P1FixesTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    // ---------------------------------------------------------- P1-1 判定口径

    /** 核心修复：角色持有但解析结果是别的桌面时，仍必须判为「是默认桌面」 */
    @Test
    fun defaultHome_roleHeldButResolveOtherIsStillDefault() {
        assertTrue(
            "ROLE_HOME 持有即应判为默认桌面（修复 HyperOS 上的假阴性）",
            HomeRole.isDefaultHome(true, "com.miui.home", SELF)
        )
    }

    @Test
    fun defaultHome_resolveSelfIsDefaultWithoutRole() {
        assertTrue(HomeRole.isDefaultHome(false, SELF, SELF))
    }

    @Test
    fun defaultHome_neitherHeldNorSelfIsNotDefault() {
        assertFalse(HomeRole.isDefaultHome(false, "com.miui.home", SELF))
        assertFalse(HomeRole.isDefaultHome(false, null, SELF))
    }

    /** 纯放宽：合并口径只可能把「否」改成「是」，绝不能把「是」改成「否」 */
    @Test
    fun defaultHome_orLogicIsPureWidening() {
        assertFalse("旧解析口径：其它桌面 → 否", HomeRole.isDefaultHome("com.miui.home", SELF))
        assertTrue("新合并口径：角色持有 → 是", HomeRole.isDefaultHome(true, "com.miui.home", SELF))
        assertTrue("旧口径为真的场景，新口径必须仍为真", HomeRole.isDefaultHome(true, SELF, SELF))
        assertTrue("旧口径为真的场景，新口径必须仍为真", HomeRole.isDefaultHome(false, SELF, SELF))
    }

    /** 1B 不变量：返回键拦截仍绑定解析口径，与合并后的 isDefaultHome 解耦 */
    @Test
    fun backIntercept_stillWiredToResolveOnly() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val a = controller.get()
        assertEquals(
            "返回键拦截必须与解析口径一致，不受 RoleManager 合并判定影响",
            HomeRole.resolveHomeMatchesSelf(a), a.shouldInterceptBack()
        )
        controller.close()
    }

    /** 1B 纯逻辑：即使合并口径判为默认，解析口径仍判为非默认 → 返回键行为不变 */
    @Test
    fun backIntercept_resolveOnlyIsNotWidenedByRole() {
        assertTrue(HomeRole.isDefaultHome(true, "com.miui.home", SELF))
        assertFalse(
            "角色持有但解析为其它桌面时，解析口径仍须为否（返回键不因此改变）",
            HomeRole.isDefaultHome("com.miui.home", SELF)
        )
    }

    /** 诊断日志补两条原始口径，便于区分「真没设上」与「某个判定口径失效」 */
    @Test
    fun diagnostics_containsRoleAndResolveFields() {
        val report = Diagnostics.buildReport(app)
        assertTrue("诊断日志须含 ROLE_HOME 持有", report.contains("ROLE_HOME 持有:"))
        assertTrue("诊断日志须含 HOME 解析结果", report.contains("HOME 解析结果:"))
    }

    // ---------------------------------------------------------- P1-3 电池优化

    private fun settings(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

    private fun drainIntents(a: SettingsActivity): List<Intent> {
        val out = mutableListOf<Intent>()
        var i = shadowOf(a).nextStartedActivity
        while (i != null) {
            out.add(i)
            i = shadowOf(a).nextStartedActivity
        }
        return out
    }

    @Test
    fun battery_alreadyWhitelistedStartsNothing() {
        val a = settings()
        drainIntents(a)
        assertNull("已在白名单时不应再打开设置页", a.requestIgnoreBattery(alreadyIgnoring = true))
        assertTrue("已在白名单时不应启动任何 Activity", drainIntents(a).isEmpty())
    }

    @Test
    fun battery_opensPowerStrategyPageWithPackageUri() {
        val a = settings()
        drainIntents(a)
        val action = a.requestIgnoreBattery(alreadyIgnoring = false, resolvable = { true })
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, action)
        val intents = drainIntents(a)
        val strategy = intents.firstOrNull {
            it.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        }
        assertTrue("应打开本应用的省电策略页（历史版本写法）", strategy != null)
        assertEquals(
            "必须带 package: 包名，否则只会落到全局应用列表页",
            "package:${a.packageName}",
            strategy!!.data?.toString()
        )
        assertFalse(
            "首选可用时不应再退到全局列表页",
            intents.any { it.action == Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS }
        )
    }

    @Test
    fun battery_fallsBackToListPageWhenStrategyUnavailable() {
        val a = settings()
        drainIntents(a)
        val action = a.requestIgnoreBattery(
            alreadyIgnoring = false,
            resolvable = { it.action != Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS }
        )
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, action)
    }

    @Test
    fun battery_fallsBackToAppDetailsWhenNoSystemPageAvailable() {
        val a = settings()
        drainIntents(a)
        val action = a.requestIgnoreBattery(alreadyIgnoring = false, resolvable = { false })
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, action)
        assertTrue(
            drainIntents(a).any { it.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS }
        )
    }

    @Test
    fun battery_launchFailureReturnsNullAndUsesBatteryOnlyText() {
        val a = settings()
        drainIntents(a)
        val action = a.requestIgnoreBattery(
            alreadyIgnoring = false,
            resolvable = { true },
            launch = { throw android.content.ActivityNotFoundException("no page") }
        )
        assertNull("启动失败应返回 null 而非假成功", action)
        assertFalse(
            "失败文案必须是电池专属，不能错用「自启动设置」",
            app.getString(R.string.battery_failed).contains("自启动")
        )
        assertFalse(
            "引导文案不能再写「弹窗」（现在是省电策略页）",
            app.getString(R.string.battery_done).contains("弹窗")
        )
        assertFalse(
            "引导文案不能再谎称「已请求」",
            app.getString(R.string.battery_done).contains("已请求")
        )
    }

    @Test
    fun battery_manifestDeclaresNoRestrictedPermission() {
        val pi = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        val requested = pi.requestedPermissions?.toList() ?: emptyList()
        assertFalse(
            "不得声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS（补权限会违反 Play 政策）",
            requested.contains(android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        )
    }

    // ------------------------------------------------ P1-4 tel: 不得解析回本应用

    @Test
    fun manifest_telDialAndCallNeverResolveBackToSelf() {
        val pm = app.packageManager
        for (action in listOf(Intent.ACTION_DIAL, Intent.ACTION_CALL)) {
            val resolved = pm.resolveActivity(
                Intent(action, Uri.parse("tel:10086")), PackageManager.MATCH_DEFAULT_ONLY
            )
            assertFalse(
                "$action + tel 不得再解析回本应用（否则外部号码丢失、兜底绕回自己）",
                resolved?.activityInfo?.packageName == app.packageName
            )
        }
    }

    // ------------------------------------------------------------ P2-5 文案

    @Test
    fun defaultTip_noLongerMentionsSystemDialog() {
        val tip = app.getString(R.string.set_default_tip)
        assertFalse("设置页常驻提示不得再写「系统弹窗」", tip.contains("弹窗"))
        assertTrue("应明确指向系统设置页", tip.contains("系统设置"))
        assertTrue("应说明是勾选操作", tip.contains("勾选"))
    }

    companion object {
        private const val SELF = "com.example.elderlauncher"
    }
}
