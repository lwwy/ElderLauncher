package com.example.elderlauncher.blue

import android.app.Application
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.example.elderlauncher.blue.data.Prefs
import com.example.elderlauncher.blue.ui.HomeActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * “隐藏设置入口”开关回归测试。
 *
 * 守护的不变量：桌面是常驻 Activity，从设置页返回只走 onResume、不重新 onCreate。
 * 显隐必须在 onResume 重新读取开关，否则“设置里打开开关 → 返回桌面”图标不会消失。
 *
 * 测试用 ActivityController.pause().resume() 模拟「离开桌面再回来」但不重建 Activity，
 * 精确复现真机上 HomeActivity 只 onResume 的场景（诊断日志：onCreate 1 次 / onResume 8 次）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HideHomeSettingsTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // SharedPreferences 在用例间共享，先复位，避免互相污染
        Prefs.set(app, Prefs.HIDE_HOME_SETTINGS, false)
    }

    @After
    fun tearDown() {
        Prefs.set(app, Prefs.HIDE_HOME_SETTINGS, false)
    }

    private fun gear(a: HomeActivity): View = a.findViewById(R.id.btnSettings)

    /** 默认（开关关）：冷启动后齿轮应可见 */
    @Test
    fun switchOff_gearVisibleOnColdStart() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        assertEquals(View.VISIBLE, gear(controller.get()).visibility)
        controller.close()
    }

    /** 开关已开时冷启动：onCreate 里也应直接隐藏 */
    @Test
    fun switchOn_gearHiddenOnColdStart() {
        Prefs.set(app, Prefs.HIDE_HOME_SETTINGS, true)
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        assertEquals(View.GONE, gear(controller.get()).visibility)
        controller.close()
    }

    /**
     * 核心回归：桌面已显示（齿轮可见）→ 在设置里打开开关 → 返回桌面（只 onResume，不重建）。
     * 修复前：齿轮仍 VISIBLE（bug）；修复后：齿轮必须立刻变 GONE。
     */
    @Test
    fun turningSwitchOn_hidesGearOnResumeWithoutRecreate() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val activity = controller.get()
        assertEquals("初始应为可见", View.VISIBLE, gear(activity).visibility)

        // 模拟用户在设置页把开关拨到“开”（写入持久化开关）
        Prefs.set(app, Prefs.HIDE_HOME_SETTINGS, true)

        // 模拟离开桌面 → 返回桌面：只经历 pause/resume，Activity 不重建
        controller.pause().resume()

        assertEquals(
            "打开隐藏开关后返回桌面，齿轮必须即时隐藏（不得要求重启 App）",
            View.GONE, gear(activity).visibility
        )
        controller.close()
    }

    /** 反向验证：隐藏状态下把开关关掉，返回桌面后齿轮要立即恢复 */
    @Test
    fun turningSwitchOff_restoresGearOnResume() {
        Prefs.set(app, Prefs.HIDE_HOME_SETTINGS, true)
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val activity = controller.get()
        assertEquals("初始应为隐藏", View.GONE, gear(activity).visibility)

        Prefs.set(app, Prefs.HIDE_HOME_SETTINGS, false)
        controller.pause().resume()

        assertEquals(
            "关掉隐藏开关后返回桌面，齿轮必须即时恢复显示",
            View.VISIBLE, gear(activity).visibility
        )
        controller.close()
    }
}
