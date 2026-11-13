package com.example.elderlauncher

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.elderlauncher.core.Diagnostics
import com.example.elderlauncher.ui.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 隐私整改回归测试：来电播报功能下架后，通讯录权限与来电相关代码必须彻底移除。
 *
 * 守护的不变量：
 *  1. Manifest 不再声明 READ_CONTACTS（通讯录权限），也不再注册 PHONE_STATE 接收器；
 *  2. 桌面冷启动不再弹「电话状态 + 通讯录」权限框；
 *  3. 设置页不再有「来电播报」开关 / 测试按钮；
 *  4. 诊断日志里不再出现来电播报开关与通讯录权限条目；
 *  5. CALL_PHONE 仍保留（拨号功能不受影响）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IncomingCallRemovalTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun manifest_noContactsPermissionAndNoPhoneStateReceiver() {
        val pi = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        val requested = pi.requestedPermissions?.toList() ?: emptyList()
        assertFalse(
            "不得再声明 READ_CONTACTS 通讯录权限",
            requested.contains(android.Manifest.permission.READ_CONTACTS)
        )
        assertTrue(
            "拨号必需的 CALL_PHONE 必须保留",
            requested.contains(android.Manifest.permission.CALL_PHONE)
        )
        assertFalse(
            "SIM 选卡死代码已删除，READ_PHONE_STATE 不应再声明（留着只会产生误导性轨迹）",
            requested.contains(android.Manifest.permission.READ_PHONE_STATE)
        )

        // PHONE_STATE 接收器（来电播报）已删除：静态注册不应再被解析出来
        val receivers = app.packageManager.queryBroadcastReceivers(
            android.content.Intent("android.intent.action.PHONE_STATE"), 0
        )
        assertTrue(
            "不应再注册任何 PHONE_STATE 来电广播接收器",
            receivers.none { it.activityInfo.packageName == app.packageName }
        )
    }

    @Test
    fun homeColdStart_doesNotRequestIncomingCallPermissions() {
        val controller = Robolectric.buildActivity(
            com.example.elderlauncher.ui.HomeActivity::class.java
        ).create()
        val a = controller.get()
        val actions = mutableListOf<String?>()
        var intent = org.robolectric.Shadows.shadowOf(a).nextStartedActivity
        while (intent != null) {
            actions.add(intent.action)
            intent = org.robolectric.Shadows.shadowOf(a).nextStartedActivity
        }
        assertFalse(
            "冷启动不得再发起权限申请弹窗",
            actions.contains("android.content.pm.action.REQUEST_PERMISSIONS")
        )
        controller.close()
    }

    @Test
    fun settings_noIncomingCallSwitchOrTestButton() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        // 资源名查 id：删除的控件 getIdentifier 返回 0。用名字查，避免引用已删除的 R.id 常量
        assertEquals(0, activity.resources.getIdentifier("swIncoming", "id", activity.packageName))
        assertEquals(0, activity.resources.getIdentifier("btnTestIncoming", "id", activity.packageName))
        assertNotNull(
            "低电量播报开关必须保留",
            activity.findViewById<android.view.View>(R.id.swLow)
        )
        controller.close()
    }

    @Test
    fun diagnostics_noIncomingCallSwitchOrContactsPermission() {
        val report = Diagnostics.buildReport(app)
        assertFalse("诊断日志不得再含来电播报开关", report.contains("来电播报:"))
        assertFalse("诊断日志不得再列 READ_CONTACTS", report.contains("READ_CONTACTS"))
        assertTrue("诊断日志仍应含低电量播报开关", report.contains("低电量播报:"))
        assertTrue("诊断日志仍应含权限状态分区", report.contains("权限状态"))
    }

    @Test
    fun strings_noIncomingCallCopyLeft() {
        // 删除的字符串资源不应再能取到（getIdentifier 返回 0）
        assertEquals(0, app.resources.getIdentifier("set_incoming", "string", app.packageName))
        assertEquals(0, app.resources.getIdentifier("ann_incoming", "string", app.packageName))
        assertEquals(0, app.resources.getIdentifier("phone_perm_denied", "string", app.packageName))
    }
}
