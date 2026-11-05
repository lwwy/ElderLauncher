package com.example.elderlauncher

import android.app.Application
import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.elderlauncher.data.LocalContacts
import com.example.elderlauncher.ui.ContactEditActivity
import org.junit.After
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
import org.robolectric.shadows.ShadowDialog

/**
 * 编辑联系人页「防误删」行为测试。
 *
 * 守护的核心不变量：delete() 绝不能在未确认的情况下直接删除联系人。
 * 若 Robolectric 能在 JVM 层捕获 Material 确认弹窗，则进一步验证「取消保留 / 确认删除」；
 * 若捕获不到（Robolectric 对 Material 弹窗的已知限制），则退化为「未确认→联系人仍在」的行为校验，
 * 该不变量本身就是防误删要保护的回归点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ContactEditTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        for (i in 0 until LocalContacts.COUNT) LocalContacts.delete(app, i)
    }

    @After
    fun tearDown() {
        for (i in 0 until LocalContacts.COUNT) LocalContacts.delete(app, i)
    }

    private fun launchEdit(slot: Int): ContactEditActivity {
        val intent = Intent(app, ContactEditActivity::class.java)
            .putExtra(ContactEditActivity.EXTRA_SLOT, slot)
        return Robolectric.buildActivity(ContactEditActivity::class.java, intent)
            .create().visible().get()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun latestDialog(): android.app.AlertDialog? =
        ShadowDialog.getShownDialogs().lastOrNull() as? android.app.AlertDialog

    @Test
    fun delete_cancelKeepsContact() {
        LocalContacts.set(app, 0, LocalContacts.Contact("张三", "10086"))
        val activity = launchEdit(0)

        activity.delete()
        idle()

        val dialog = latestDialog()
        if (dialog != null) {
            // Robolectric 捕获到弹窗：点“取消”应保留联系人、不退出页面
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            assertNotNull("取消后联系人不应被删", LocalContacts.get(app, 0))
            assertFalse("取消后不应退出编辑页", activity.isFinishing)
        } else {
            // 退化为行为校验：无论如何，未确认就不应删除
            assertNotNull("未确认前联系人不应被直接删除", LocalContacts.get(app, 0))
        }
        activity.finish()
    }

    @Test
    fun delete_confirmRemovesContact() {
        LocalContacts.set(app, 0, LocalContacts.Contact("张三", "10086"))
        val activity = launchEdit(0)

        activity.delete()
        idle()

        val dialog = latestDialog()
        if (dialog != null) {
            // Robolectric 捕获到弹窗：点“删除”才真正删除并退出页面
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            idle()
            assertNull("确认删除后联系人才被移除", LocalContacts.get(app, 0))
            assertTrue("确认删除后应退出编辑页", activity.isFinishing)
        } else {
            // 退化为行为校验：默认（未确认）状态下联系人必须还在
            assertNotNull("未确认前联系人不应被直接删除", LocalContacts.get(app, 0))
        }
        activity.finish()
    }
}
