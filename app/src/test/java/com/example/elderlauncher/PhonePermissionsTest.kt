package com.example.elderlauncher

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.elderlauncher.phone.PhonePermissions
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
 * 来电播报运行时权限回归测试。
 *
 * 守护的不变量：ACTION_PHONE_STATE_CHANGED 虽在「隐式广播豁免清单」里，
 * 但系统投递时仍校验 READ_PHONE_STATE，只写在 Manifest 里不够，必须运行时申请。
 * 修复前全项目只在点拨号时申请 CALL_PHONE，这两个权限从未弹过窗，真机来电播报不响。
 *
 * 权限状态挂在共享 Application 阴影上、跨用例会残留，且 Robolectric 默认把所有权限
 * 视为已授予，因此每个用例开头都显式 deny 一遍。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PhonePermissionsTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).denyPermissions(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS
        )
    }

    private fun grantBoth() {
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS
        )
    }

    @Test
    fun requiredPermissionsContainPhoneStateAndContacts() {
        assertEquals(2, PhonePermissions.REQUIRED.size)
        assertTrue(PhonePermissions.REQUIRED.contains(Manifest.permission.READ_PHONE_STATE))
        assertTrue(PhonePermissions.REQUIRED.contains(Manifest.permission.READ_CONTACTS))
    }

    @Test
    fun missingReturnsBothWhenNothingGranted() {
        val missing = PhonePermissions.missing(app)
        assertEquals(2, missing.size)
        assertFalse(PhonePermissions.allGranted(app))
    }

    @Test
    fun allGrantedIsTrueWhenBothGranted() {
        grantBoth()
        assertTrue(PhonePermissions.allGranted(app))
        assertTrue(PhonePermissions.missing(app).isEmpty())
    }

    @Test
    fun missingReturnsOnlyTheStillDeniedPermission() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val missing = PhonePermissions.missing(app)
        assertEquals(1, missing.size)
        assertEquals(Manifest.permission.READ_CONTACTS, missing[0])
    }

    /** 核心回归：冷启动缺失权限必须发起申请；旋转/重建不能重复弹 */
    @Test
    fun homeColdStartRequestsPermission_butRecreateDoesNot() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).create()
        val a = controller.get()
        assertTrue("冷启动且权限缺失时必须申请来电播报权限", a.ensureAnnouncePermissions(true))
        assertFalse("旋转/重建不应重复弹权限框", a.ensureAnnouncePermissions(false))
        controller.close()
    }

    /** 弹出所有已启动的 Intent，检查是否发起过系统权限请求框 */
    private fun drainActions(a: android.app.Activity): List<String?> {
        val actions = mutableListOf<String?>()
        var intent = shadowOf(a).nextStartedActivity
        while (intent != null) {
            actions.add(intent.action)
            intent = shadowOf(a).nextStartedActivity
        }
        return actions
    }

    /** 接线级回归：onCreate 真的会为来电播报发起申请，不只是方法本身可用 */
    @Test
    fun homeOnCreateActuallyLaunchesPermissionRequest() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).create()
        val a = controller.get()
        assertTrue(
            "冷启动必须自动申请 READ_PHONE_STATE / READ_CONTACTS，否则真机来电播报仍不响",
            drainActions(a).contains("android.content.pm.action.REQUEST_PERMISSIONS")
        )
        controller.close()
    }

    @Test
    fun homeOnCreateSkipsRequestWhenAlreadyGranted() {
        grantBoth()
        val controller = Robolectric.buildActivity(HomeActivity::class.java).create()
        val a = controller.get()
        assertFalse(
            "已授权时冷启动不应再弹权限框",
            drainActions(a).contains("android.content.pm.action.REQUEST_PERMISSIONS")
        )
        controller.close()
    }

    @Test
    fun homeDoesNotRequestAgainWhenAlreadyGranted() {
        grantBoth()
        val controller = Robolectric.buildActivity(HomeActivity::class.java).create()
        val a = controller.get()
        assertFalse("已授权时不应再申请", a.ensureAnnouncePermissions(true))
        controller.close()
    }

    /** 设置页要提供补申请入口，避免冷启动被拒后功能永久失效 */
    @Test
    fun settingsRequestsPermissionWhenMissing() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create()
        val a = controller.get()
        assertTrue("设置页发现权限缺失时应发起申请", a.requestAnnouncePermissionsIfNeeded())
        controller.close()
    }

    @Test
    fun settingsSkipsRequestWhenAlreadyGranted() {
        grantBoth()
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create()
        val a = controller.get()
        assertFalse("已授权时设置页不应再申请", a.requestAnnouncePermissionsIfNeeded())
        controller.close()
    }
}
