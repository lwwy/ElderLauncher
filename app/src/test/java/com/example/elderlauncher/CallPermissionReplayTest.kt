package com.example.elderlauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.elderlauncher.phone.CallThrottle
import com.example.elderlauncher.ui.DialerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 拨号权限“二次确认/重放”回归测试。
 *
 * 守护的不变量（即本次修复的割裂体验）：
 * 首次点击拨号时若 CALL_PHONE 未授予，只会拉起系统权限框（REQUEST_PERMISSIONS），不会真正拨号；
 * 当用户在权限弹窗点“允许”后，授权结果回调必须**自动**发起拨号（ACTION_CALL），
 * 不应要求用户再点一次。拒绝授权则任何情况下都不拨号。
 *
 * 说明：ActivityResult 框架在 Robolectric 下无法通过 receiveResult 可靠地触发回调，
 * 因此这里直接驱动回调方法 [DialerActivity.onCallPermissionResult]，等价验证“授权后自动重拨”的逻辑；
 * launcher → 该方法的接线是标准 AndroidX 写法、由平台保证。
 * 注：HomeActivity 的来电联系人头像拨号使用与此完全相同的修复逻辑，二者仅调用入口不同。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CallPermissionReplayTest {

    // CallThrottle 是进程级单例，用例间会残留；每个用例先复位，
    // 否则「上一个用例刚点过」会让本用例首次拨号被误挡。
    @Before
    fun setUp() {
        CallThrottle.resetForTest()
    }

    private fun launch(): DialerActivity {
        return Robolectric.buildActivity(DialerActivity::class.java)
            .create().visible().get()
    }

    // 显式把 CALL_PHONE 设为“已拒绝”，模拟“尚未授权”的初始状态。
    // 权限状态挂在共享的 Application 阴影上、跨用例会残留，故每个用例都显式设一遍，避免互相污染。
    private fun denyCall(a: DialerActivity) {
        shadowOf(RuntimeEnvironment.getApplication())
            .denyPermissions(Manifest.permission.CALL_PHONE)
        assertEquals(
            "deny 后应处于 DENIED（否则 call() 会直接拨号，失去测试意义）",
            PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(a, Manifest.permission.CALL_PHONE)
        )
    }

    // 显式把 CALL_PHONE 设为“已授予”，模拟“已经授权”的初始状态。
    private fun grantCall(a: DialerActivity) {
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.CALL_PHONE)
        assertEquals(
            "grant 后应处于 GRANTED（否则 call() 会去拉权限框而不是拨号）",
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(a, Manifest.permission.CALL_PHONE)
        )
    }

    private fun setNumber(a: DialerActivity, n: String) {
        DialerActivity::class.java.getDeclaredField("number").apply {
            isAccessible = true
            set(a, n)
        }
    }

    private fun invokeCall(a: DialerActivity) {
        DialerActivity::class.java.getDeclaredMethod("call").apply {
            isAccessible = true
            invoke(a)
        }
    }

    /** 依次弹出（消费）所有已启动的 Activity Intent，返回它们的 action 列表 */
    private fun drainActions(a: DialerActivity): List<String?> {
        val actions = mutableListOf<String?>()
        var intent = shadowOf(a).nextStartedActivity
        while (intent != null) {
            actions.add(intent.action)
            intent = shadowOf(a).nextStartedActivity
        }
        return actions
    }

    /** 弹出所有已启动的 Intent，返回列表（便于检查某个具体 Intent 的 data） */
    private fun drainIntents(a: DialerActivity): List<Intent> {
        val list = mutableListOf<Intent>()
        var intent = shadowOf(a).nextStartedActivity
        while (intent != null) {
            list.add(intent)
            intent = shadowOf(a).nextStartedActivity
        }
        return list
    }

    @Test
    fun firstTapRequestsPermissionThenGrantAutoPlacesCall() {
        val a = launch()
        denyCall(a)
        setNumber(a, "10086")

        // 首次点击：权限未授予 → 仅拉起系统权限框，不应真正拨号
        invokeCall(a)
        val afterTap = drainActions(a)
        assertTrue(
            "首次点击应拉起权限请求框",
            afterTap.contains("android.content.pm.action.REQUEST_PERMISSIONS")
        )
        assertFalse("首次点击不应直接拨号", afterTap.contains(Intent.ACTION_CALL))

        // 用户在权限弹窗点“允许” → 授权回调必须自动拨号，无需再次点击
        a.onCallPermissionResult(true)
        val call = drainIntents(a).firstOrNull { it.action == Intent.ACTION_CALL }
        assertNotNull("授权后必须自动拨号（无需二次点击）", call)
        assertEquals("拨出号码应为 10086", "10086", call!!.data?.schemeSpecificPart)
        a.finish()
    }

    @Test
    fun deniedPermissionNeverPlacesCall() {
        val a = launch()
        denyCall(a)
        setNumber(a, "10086")

        // 首次点击：仅拉起权限框
        invokeCall(a)
        assertTrue(
            "首次点击应拉起权限请求框",
            drainActions(a).contains("android.content.pm.action.REQUEST_PERMISSIONS")
        )

        // 用户拒绝授权 → 绝不拨号
        a.onCallPermissionResult(false)
        assertTrue(
            "拒绝授权后不应拨号",
            drainActions(a).none { it == Intent.ACTION_CALL }
        )
        a.finish()
    }

    @Test
    fun alreadyGrantedPlacesCallImmediately() {
        // 已授权：call() 应直接拨号、不弹权限框
        val a = launch()
        grantCall(a)
        setNumber(a, "10086")
        invokeCall(a)
        val call = drainIntents(a).firstOrNull { it.action == Intent.ACTION_CALL }
        assertNotNull("已授权时应直接拨号", call)
        assertEquals("拨出号码应为 10086", "10086", call!!.data?.schemeSpecificPart)
        a.finish()
    }

    @Test
    fun emptyNumberNeverPlacesCall() {
        val a = launch()
        denyCall(a)
        setNumber(a, "")
        invokeCall(a)
        // 空号码：连权限请求都不应发起
        assertTrue("空号码不应发起任何拨号或权限请求", drainActions(a).isEmpty())
        a.finish()
    }
}
