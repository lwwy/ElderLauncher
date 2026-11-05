package com.example.elderlauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.elderlauncher.ui.DialerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * 当用户在权限弹窗点“允许”后，系统回调 onRequestPermissionsResult 必须**自动**发起拨号（ACTION_CALL），
 * 不应要求用户再点一次。拒绝授权则任何情况下都不拨号。
 *
 * 注：HomeActivity 的来电联系人头像拨号使用与此完全相同的修复逻辑，
 * 二者仅调用入口不同（首页卡片 vs 拨号盘），重放机制一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CallPermissionReplayTest {

    private fun launch(): DialerActivity {
        return Robolectric.buildActivity(DialerActivity::class.java)
            .create().visible().get()
    }

    // Robolectric 默认把权限当作已授予，需显式拒绝以模拟“尚未授权”的初始状态
    private fun denyCall(a: DialerActivity) {
        shadowOf(RuntimeEnvironment.getApplication())
            .denyPermissions(Manifest.permission.CALL_PHONE)
        assertEquals(
            "deny 后应处于 DENIED（否则 call() 会直接拨号，失去测试意义）",
            PackageManager.PERMISSION_DENIED,
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

    private fun nextAction(a: DialerActivity): String? =
        shadowOf(a).nextStartedActivity?.action

    @Test
    fun firstTapRequestsPermissionThenGrantAutoPlacesCall() {
        val a = launch()
        denyCall(a)
        setNumber(a, "10086")

        // 首次点击：权限未授予 → 仅拉起系统权限框，不应真正拨号
        invokeCall(a)
        assertEquals(
            "首次点击应拉起权限请求框",
            "android.content.pm.action.REQUEST_PERMISSIONS",
            nextAction(a)
        )

        // 用户在权限弹窗点“允许”后系统回调 → 应立即拨号，无需再次点击
        a.onRequestPermissionsResult(
            1,
            arrayOf(Manifest.permission.CALL_PHONE),
            intArrayOf(PackageManager.PERMISSION_GRANTED)
        )
        val call = shadowOf(a).nextStartedActivity
        assertNotNull("授权后必须自动拨号（无需二次点击）", call)
        assertEquals("应走 ACTION_CALL 发起呼出", Intent.ACTION_CALL, call!!.action)
        assertEquals("拨出号码应为 10086", "10086", call.data?.schemeSpecificPart)
        a.finish()
    }

    @Test
    fun deniedPermissionNeverPlacesCall() {
        val a = launch()
        denyCall(a)
        setNumber(a, "10086")

        // 首次点击：仅拉起权限框
        invokeCall(a)
        assertEquals(
            "首次点击应拉起权限请求框",
            "android.content.pm.action.REQUEST_PERMISSIONS",
            nextAction(a)
        )

        // 用户拒绝授权 → 绝不拨号
        a.onRequestPermissionsResult(
            1,
            arrayOf(Manifest.permission.CALL_PHONE),
            intArrayOf(PackageManager.PERMISSION_DENIED)
        )
        assertNull(
            "拒绝授权后不应拨号",
            shadowOf(a).nextStartedActivity
        )
        a.finish()
    }
}
