package com.example.elderlauncher.core

import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 默认桌面（HOME）角色的统一判定与引导。
 *
 * Android 10 (API 29) 起，默认桌面由 RoleManager.ROLE_HOME 独占管理：
 *  - `clearPackagePreferredActivities()` / `addPreferredActivity()` 已弃用，
 *    官方明确说明「This function no longer does anything」，应用调了也不会生效；
 *  - 应用**不能自行放弃**已持有的 HOME 角色，只能引导用户到系统设置里改。
 *
 * 判定口径以 HOME intent 的 `resolveActivity` 结果为准 —— 与诊断日志里的
 * 「默认桌面: 是/否」完全一致，也最能反映「按 Home 键会不会回到本应用」。
 *
 * 重要：RoleManager 的「角色申请」intent **不作为「设为默认桌面」的入口**。
 * 它在部分国产 ROM（HyperOS 等）上会被静默拦截：`resolveActivity` 能通过校验、
 * `startActivity` 也不抛异常，但就是不弹确认框、不生效。若把它放在候选链首位，
 * 「取第一个能启动的入口就返回」的策略会永远停在它上面，后面真正能用的系统设置页
 * 一次都轮不到 —— 这正是 v1.2.3「点击设为默认桌面没反应」的根因。
 * 实测 v1.0.9 ~ v1.2.2 一直只跳 ACTION_HOME_SETTINGS 且工作正常，故这里回到该稳定路径。
 */
object HomeRole {

    /** 默认桌面设置入口候选项；key 用于日志与单元测试区分实际走了哪条分支 */
    data class HomeSetupOption(val key: String, val intent: Intent)

    /** 本应用当前是否为默认桌面（与诊断日志口径一致） */
    fun isDefaultHome(ctx: Context): Boolean = try {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = ctx.packageManager.resolveActivity(
            home, PackageManager.MATCH_DEFAULT_ONLY
        )
        isDefaultHome(resolved?.activityInfo?.packageName, ctx.packageName)
    } catch (_: Throwable) {
        false
    }

    /** 纯判定逻辑抽出来，便于单元测试不依赖 PackageManager 阴影 */
    internal fun isDefaultHome(resolvedPackage: String?, selfPackage: String): Boolean =
        resolvedPackage != null && resolvedPackage == selfPackage

    /** 是否持有 ROLE_HOME（仅 API 29+ 有意义），供诊断使用；低版本返回 false */
    @SuppressLint("NewApi")
    fun holdsHomeRole(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) false
        else try {
            val rm = ctx.getSystemService(RoleManager::class.java)
            rm?.isRoleHeld(RoleManager.ROLE_HOME) == true
        } catch (_: Throwable) {
            false
        }

    /**
     * 构造 RoleManager 角色申请 intent。
     *
     * 仅保留供诊断 / 其它场景使用，**不纳入「设为默认桌面」候选链**（原因见类注释）。
     * 角色不可用或低版本返回 null。
     */
    @SuppressLint("NewApi")
    fun requestDefaultHomeIntent(ctx: Context): Intent? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) null
        else try {
            val rm = ctx.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                rm.createRequestRoleIntent(RoleManager.ROLE_HOME)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }

    /** 打开系统「默认主屏幕」设置，让用户手动切换或放弃默认桌面 */
    fun homeSettingsIntent(): Intent = Intent(Settings.ACTION_HOME_SETTINGS)

    /**
     * 「设为默认桌面」的候选入口，按优先级排列，只保留当前系统真正能解析的。
     *
     * 顺序刻意与 v1.0.9 ~ v1.2.2（连续 5 个版本实测正常）保持一致：
     *  - home_settings：系统「默认主屏幕」设置 —— 首选，用户点一次就能进到选择页；
     *  - default_apps：系统「默认应用」设置 —— 国产 ROM 通常在这里选桌面，二次点击优先；
     *  - app_details：本应用详情页 —— 最后兜底，至少让用户看到界面。
     *
     * 不含 RoleManager 的 role 入口：它能通过 resolveActivity 校验却不生效，
     * 会让「取首个可启动入口」的策略永远停在它上面（详见类注释）。
     * resolvable 抽成可注入参数，便于单元测试不依赖真实 PackageManager 驱动分支。
     */
    fun defaultHomeSetupOptions(
        ctx: Context,
        resolvable: (Intent) -> Boolean = { it.resolveActivity(ctx.packageManager) != null }
    ): List<HomeSetupOption> {
        val all = listOf(
            HomeSetupOption("home_settings", homeSettingsIntent()),
            HomeSetupOption("default_apps", Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)),
            HomeSetupOption(
                "app_details",
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${ctx.packageName}")
                )
            )
        )
        return all.filter { resolvable(it.intent) }
    }
}
