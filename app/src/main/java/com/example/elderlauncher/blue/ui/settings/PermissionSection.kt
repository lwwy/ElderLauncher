package com.example.elderlauncher.blue.ui.settings

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.elderlauncher.blue.R
import com.example.elderlauncher.blue.core.CrashLogger
import com.example.elderlauncher.blue.core.Diagnostics
import com.google.android.material.button.MaterialButton

/**
 * 设置页「权限与保活」+「日志与诊断」两个分区：自启动、电池优化、导出诊断日志。
 *
 * 从 [com.example.elderlauncher.blue.ui.SettingsActivity] 拆出的独立职责块，行为与原实现一致。
 * Activity 通过同名 internal 方法对 [requestIgnoreBattery] 做薄委托，保证既有测试入口不变。
 */
internal class PermissionSection(private val activity: AppCompatActivity) {

    /** 绑定自启动、电池优化、导出日志三个按钮 */
    fun bind() {
        activity.findViewById<MaterialButton>(R.id.btnAutostart).setOnClickListener {
            openAutostart()
        }
        activity.findViewById<MaterialButton>(R.id.btnBattery).setOnClickListener {
            requestIgnoreBattery()
        }
        activity.findViewById<MaterialButton>(R.id.btnExportLog).setOnClickListener {
            exportDiagnosticLog()
        }
    }

    /** 生成诊断日志并唤起系统分享面板，用户可直接发给开发者 */
    private fun exportDiagnosticLog() {
        Toast.makeText(activity, R.string.export_log_doing, Toast.LENGTH_SHORT).show()
        CrashLogger.trace("用户手动导出诊断日志")
        val file = Diagnostics.exportDiagnostic(activity)
        if (file == null) {
            Toast.makeText(activity, R.string.export_log_failed, Toast.LENGTH_LONG).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.export_log))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(
                Intent.createChooser(send, activity.getString(R.string.export_log_chooser))
            )
        } catch (e: Exception) {
            // 分享不可用时至少告诉用户文件在哪（同时已落一份到「下载」目录）
            CrashLogger.trace("分享诊断日志失败：${e.message}")
            Toast.makeText(
                activity,
                activity.getString(R.string.export_log_saved, file.absolutePath),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 打开国产 ROM 的自启动管理页；都不匹配时退到应用详情页 */
    private fun openAutostart() {
        val pm = activity.packageManager
        val targets = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartActivity")
        )
        for (cn in targets) {
            val intent = Intent().setComponent(cn)
            if (intent.resolveActivity(pm) != null) {
                try {
                    activity.startActivity(intent)
                    Toast.makeText(activity, R.string.autostart_opened, Toast.LENGTH_LONG).show()
                    return
                } catch (_: Exception) {
                }
            }
        }
        // 回退：打开应用详情，手动开启自启动 / 后台运行
        try {
            activity.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${activity.packageName}")
                )
            )
        } catch (_: Exception) {
        }
        Toast.makeText(activity, R.string.autostart_failed, Toast.LENGTH_LONG).show()
    }

    /**
     * 「忽略电池优化」入口：直达本应用的「省电策略」页（可直接选「无限制」）。
     *
     * 历史：v1.0.9 ~ v1.2.5 一直用 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS +
     * Uri.parse("package:$packageName")，连续 7 个版本真机正常。v1.2.6 曾误换成
     * 不带包名的全局列表页（用户要多点一步），v1.2.9 按用户要求换回老 action。
     *
     * 现实现：首选老 action（带包名），保留真状态判断、日志埋点、电池专属文案与
     * 「启动失败返回 null、不假成功」；老 action 不可解析时顺延到系统电池优化白名单
     * 列表页，再不行退应用详情页。全程只发 intent，不声明受限权限。
     *
     * 参数可注入，便于单元测试驱动各分支；返回实际启动的 intent action，未启动返回 null。
     */
    internal fun requestIgnoreBattery(
        alreadyIgnoring: Boolean = isIgnoringBatteryOptimizations(),
        resolvable: (Intent) -> Boolean = { it.resolveActivity(activity.packageManager) != null },
        launch: (Intent) -> Unit = { activity.startActivity(it) }
    ): String? {
        if (alreadyIgnoring) {
            Toast.makeText(activity, R.string.battery_already, Toast.LENGTH_SHORT).show()
            CrashLogger.trace("忽略电池优化：已在白名单，无需操作")
            return null
        }

        // 候选链按「能否直达本应用省电策略」排序；package: 是关键，缺了只会落到全局列表页
        val strategyPage = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${activity.packageName}")
        )
        val listPage = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val appDetails = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${activity.packageName}")
        )
        val target = when {
            resolvable(strategyPage) -> strategyPage
            resolvable(listPage) -> listPage
            else -> appDetails
        }

        return try {
            launch(target)
            CrashLogger.trace("忽略电池优化：已打开 ${target.action} 分支")
            Toast.makeText(activity, R.string.battery_done, Toast.LENGTH_LONG).show()
            target.action
        } catch (e: Exception) {
            CrashLogger.trace(
                "忽略电池优化：启动失败 ${e.javaClass.simpleName}: ${e.message}"
            )
            Toast.makeText(activity, R.string.battery_failed, Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean = try {
        val pm = activity.getSystemService(PowerManager::class.java)
        pm?.isIgnoringBatteryOptimizations(activity.packageName) == true
    } catch (_: Throwable) {
        false
    }
}
