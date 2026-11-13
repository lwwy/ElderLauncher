package com.example.elderlauncher

import android.app.Application
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.example.elderlauncher.core.Diagnostics
import com.example.elderlauncher.data.LocalContacts
import com.example.elderlauncher.ui.SettingsActivity
import com.google.android.material.button.MaterialButton
import org.junit.After
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
 * 第二、三批（结构优化 + M3 整改）的回归测试。
 * 重点覆盖「布局被重写」这类最容易在真机才炸的改动：
 * 设置页联系人行改成 XML item、诊断日志拆类。
 * 注：原「拨号页 MaterialButton」用例随无入口拨号盘删除一并移除。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StructureRefactorTest {

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

    // ---------- 设置页：联系人行 ----------

    @Test
    fun settings_inflatesThreeContactRows() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val container = activity.findViewById<LinearLayout>(R.id.favContainer)

        assertEquals("设置页应渲染 3 个联系人槽位", 3, container.childCount)
        controller.close()
    }

    @Test
    fun settings_moveButtons_followPositionAndTouchTarget() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val container = activity.findViewById<LinearLayout>(R.id.favContainer)

        val first = container.getChildAt(0)
        val last = container.getChildAt(2)

        assertFalse("第一行不能再上移", first.findViewById<MaterialButton>(R.id.btnMoveUp).isEnabled)
        assertTrue("第一行可以下移", first.findViewById<MaterialButton>(R.id.btnMoveDown).isEnabled)
        assertTrue("最后一行可以上移", last.findViewById<MaterialButton>(R.id.btnMoveUp).isEnabled)
        assertFalse("最后一行不能再下移", last.findViewById<MaterialButton>(R.id.btnMoveDown).isEnabled)

        // M3 无障碍要求触摸目标 ≥ 48dp
        val expected = activity.resources.getDimensionPixelSize(R.dimen.touchTarget)
        assertEquals(
            "排序按钮触摸目标应为 48dp",
            expected,
            first.findViewById<MaterialButton>(R.id.btnMoveUp).layoutParams.width
        )
        controller.close()
    }

    @Test
    fun settings_moveDown_swapsContactsAndRebuildsRows() {
        LocalContacts.set(app, 0, LocalContacts.Contact("甲", "10010"))
        LocalContacts.set(app, 1, LocalContacts.Contact("乙", "10086"))

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val container = activity.findViewById<LinearLayout>(R.id.favContainer)

        container.getChildAt(0).findViewById<MaterialButton>(R.id.btnMoveDown).performClick()

        assertEquals("下移后第一个槽位应变成乙", "乙", LocalContacts.get(app, 0)?.name)
        assertEquals("下移后第二个槽位应变成甲", "甲", LocalContacts.get(app, 1)?.name)

        // 列表应已重建（不是只改了数据）
        val firstLabel = (container.getChildAt(0) as LinearLayout)
            .findViewById<android.widget.TextView>(R.id.tvContactLabel).text.toString()
        assertTrue("重建后的第一行应显示乙，实际：$firstLabel", firstLabel.contains("乙"))
        controller.close()
    }

    // ---------- 诊断日志（CrashLogger 拆分后） ----------

    @Test
    fun diagnostics_reportStillBuiltAfterSplit() {
        val file = Diagnostics.exportDiagnostic(app)
        assertNotNull("拆分后导出诊断日志仍应成功", file)
        val text = file!!.readText()
        assertTrue("应包含标题", text.contains("诊断日志"))
        assertTrue("应包含语音播报分区", text.contains("语音播报"))
        assertTrue("应包含权限状态分区", text.contains("权限状态"))
        assertTrue("应包含运行轨迹分区", text.contains("运行轨迹"))
    }
}
