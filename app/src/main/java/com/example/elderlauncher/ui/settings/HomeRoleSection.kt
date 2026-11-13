package com.example.elderlauncher.ui.settings

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.elderlauncher.R
import com.example.elderlauncher.core.CrashLogger
import com.example.elderlauncher.core.HomeRole
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.example.elderlauncher.data.Prefs

/**
 * 设置页「桌面入口」+「默认应用」两个分区：隐藏设置齿轮开关、设为默认桌面、恢复系统桌面。
 *
 * 从 [com.example.elderlauncher.ui.SettingsActivity] 拆出的独立职责块，行为与原实现一致。
 * [wasDefaultHome] 等状态由本类持有，Activity 通过同名 internal 方法做薄委托，
 * 以保证既有的单元测试驱动入口不变。
 */
internal class HomeRoleSection(private val activity: AppCompatActivity) {

    /** 进入设置页时的默认桌面状态，用于从系统设置返回后判断是否设置成功 */
    private var wasDefaultHome = false

    /** 绑定隐藏入口开关、设为默认桌面、恢复系统桌面三个控件 */
    fun bind() {
        val swHideSettings = activity.findViewById<SwitchMaterial>(R.id.swHideSettings)
        swHideSettings.isChecked = Prefs.enabled(activity, Prefs.HIDE_HOME_SETTINGS, false)
        swHideSettings.setOnCheckedChangeListener { _, v ->
            Prefs.set(activity, Prefs.HIDE_HOME_SETTINGS, v)
        }

        activity.findViewById<MaterialButton>(R.id.btnDefault).setOnClickListener {
            requestDefaultHome()
        }
        activity.findViewById<MaterialButton>(R.id.btnRestoreHome).setOnClickListener {
            restoreSystemHome()
        }

        wasDefaultHome = HomeRole.isDefaultHome(activity)
    }

    /** 每次回到设置页检测默认桌面是否设置成功（由 Activity.onResume 转发） */
    fun onResume() {
        onDefaultHomeStateChanged(HomeRole.isDefaultHome(activity))
    }

    /**
     * 「设为默认桌面」主流程。
     *
     * 历史：v1.0.9 ~ v1.2.2 一直直接跳 ACTION_HOME_SETTINGS，连续 5 个版本工作正常；
     * v1.2.3 改成优先 RoleManager 角色申请后，在 HyperOS 上因 ROM 静默拦截变成
     * 「点了没反应」（异常被空 catch 吞掉、兜底又退到系统设置首页）。
     *
     * 现在每次点击都打开与老版本相同的系统「默认主屏幕」设置页；该页不可解析时
     * 顺延到应用详情页；所有入口都不可用也明确提示手动路径，绝不静默失败。
     *
     * 参数可注入，便于单元测试驱动各分支。返回实际启动的分支 key，未启动返回 null。
     */
    internal fun requestDefaultHome(
        isDefault: Boolean = HomeRole.isDefaultHome(activity),
        options: List<HomeRole.HomeSetupOption> = HomeRole.defaultHomeSetupOptions(activity)
    ): String? {
        if (isDefault) {
            Toast.makeText(activity, R.string.default_home_already, Toast.LENGTH_SHORT).show()
            CrashLogger.trace("设为默认桌面：当前已是默认桌面，无需操作")
            return null
        }

        // 每次都从首选开始：能勾选桌面的只有「默认主屏幕应用」页，重复点击即重试该页
        for (opt in options) {
            try {
                activity.startActivity(opt.intent)
                CrashLogger.trace("设为默认桌面：已启动 ${opt.key} 分支")
                Toast.makeText(activity, R.string.default_home_guide, Toast.LENGTH_LONG).show()
                return opt.key
            } catch (e: Exception) {
                CrashLogger.trace(
                    "设为默认桌面：${opt.key} 启动失败 ${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }

        Toast.makeText(activity, R.string.default_home_manual, Toast.LENGTH_LONG).show()
        CrashLogger.trace("设为默认桌面：所有候选入口均不可用，已提示手动设置路径")
        return null
    }

    /**
     * 从系统设置返回时刷新默认桌面状态：由「非默认」变为「默认」时给出成功反馈。
     * 显式传参便于单元测试直接驱动，不依赖测试环境的 HOME 解析结果。
     */
    internal fun onDefaultHomeStateChanged(nowDefault: Boolean): Boolean {
        val becameDefault = !wasDefaultHome && nowDefault
        if (becameDefault) {
            Toast.makeText(activity, R.string.default_home_success, Toast.LENGTH_LONG).show()
            CrashLogger.trace("设为默认桌面成功：检测到默认桌面角色已生效")
        }
        wasDefaultHome = nowDefault
        return becameDefault
    }

    /**
     * 恢复系统桌面（引导式，不再尝试自动取消）。
     *
     * Android 10 (API 29) 起默认桌面由 RoleManager.ROLE_HOME 独占管理，应用无法自行放弃；
     * 且**绝不能再发 ACTION_MAIN + CATEGORY_HOME intent**：本应用仍声明 HOME，该 intent
     * 可能被解析回自己，导致切回系统桌面时界面闪回、Activity 反复重建。
     */
    internal fun restoreSystemHome(isDefaultHome: Boolean = HomeRole.isDefaultHome(activity)) {
        if (isDefaultHome) {
            Toast.makeText(activity, R.string.restore_system_home_guide, Toast.LENGTH_LONG).show()
            try {
                activity.startActivity(HomeRole.homeSettingsIntent())
            } catch (_: Exception) {
                try {
                    activity.startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (_: Exception) {
                }
            }
            CrashLogger.trace("恢复系统桌面：当前仍是默认，已引导至系统设置手动切换")
        } else {
            Toast.makeText(
                activity, R.string.restore_system_home_already, Toast.LENGTH_SHORT
            ).show()
            CrashLogger.trace("恢复系统桌面：当前已不是默认，无需操作（未发 HOME intent）")
        }
    }
}
