package com.example.elderlauncher.blue.core

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
 * 判定口径（v1.2.6 起）：[holdsHomeRole]（ROLE_HOME 角色持有）**或** HOME intent 的
 * `resolveActivity` 解析到自身，任一成立即为默认桌面。此前只看 resolveActivity，
 * HyperOS 上「角色持有状态」与「解析结果」可能不同步，导致日志头部写「否」但按 Home 键
 * 其实正常回到本应用（判定假阴性）。改为「或」只会把「否」修成「是」，不会把「是」误判成「否」。
 *
 * 例外：`HomeActivity.shouldInterceptBack()` 仍只用 [resolveHomeMatchesSelf] 的解析口径 ——
 * 返回键拦截是实际功能行为，保持与 v1.2.5 完全一致，不随本次判定口径调整而改变。
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

    /** 本应用当前是否为默认桌面：ROLE_HOME 持有 或 HOME 解析到自身，任一成立即可 */
    fun isDefaultHome(ctx: Context): Boolean = try {
        isDefaultHome(holdsHomeRole(ctx), homeResolvePackage(ctx), ctx.packageName)
    } catch (_: Throwable) {
        false
    }

    /**
     * 纯判定逻辑抽出来，便于单元测试直接驱动「角色持有但解析为其它」这类假阴性场景，
     * 不依赖真实 RoleManager / PackageManager 阴影。
     */
    internal fun isDefaultHome(
        roleHeld: Boolean,
        resolvedPackage: String?,
        selfPackage: String
    ): Boolean = roleHeld || isDefaultHome(resolvedPackage, selfPackage)

    /** 纯判定逻辑抽出来，便于单元测试不依赖 PackageManager 阴影 */
    internal fun isDefaultHome(resolvedPackage: String?, selfPackage: String): Boolean =
        resolvedPackage != null && resolvedPackage == selfPackage

    /** 诊断用：HOME intent 实际解析到的包名；解析不到返回 null */
    internal fun homeResolvePackage(ctx: Context): String? = try {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        ctx.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    } catch (_: Throwable) {
        null
    }

    /**
     * 解析口径（不含 RoleManager）：返回键拦截等需保持原有行为的场景专用。
     * v1.2.6 只把它用于 [com.example.elderlauncher.blue.ui.HomeActivity.shouldInterceptBack]，
     * 确保本次「判定口径调整」不改变任何已验证正常的功能行为。
     */
    internal fun resolveHomeMatchesSelf(ctx: Context): Boolean =
        isDefaultHome(homeResolvePackage(ctx), ctx.packageName)

    /** 是否持有 ROLE_HOME（仅 API 29+ 有意义），供判定与诊断使用；低版本返回 false */
    @SuppressLint("NewApi")
    fun holdsHomeRole(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) false
        else try {
            val rm = ctx.getSystemService(RoleManager::class.java)
            rm?.isRoleHeld(RoleManager.ROLE_HOME) == true
        } catch (_: Throwable) {
            false
        }

    /** 打开系统「默认主屏幕」设置，让用户手动切换或放弃默认桌面 */
    fun homeSettingsIntent(): Intent = Intent(Settings.ACTION_HOME_SETTINGS)

    /**
     * 「设为默认桌面」的候选入口，按优先级排列，只保留当前系统真正能解析的。
     *
     * 顺序刻意与 v1.0.9 ~ v1.2.2（连续 5 个版本实测正常）保持一致：
     *  - home_settings：系统「默认主屏幕」设置 —— 唯一能真正勾选桌面的页面，始终首选；
     *  - app_details：本应用详情页 —— 仅当上面打不开时的最后兜底，至少让用户看到界面。
     *
     * 不含 RoleManager 的 role 入口：它能通过 resolveActivity 校验却不生效，
     * 会让「取首个可启动入口」的策略永远停在它上面（详见类注释）。
     * 不含 default_apps（ACTION_MANAGE_DEFAULT_APPS_SETTINGS）：HyperOS 实测该页只有
     * 电话 / 短信 / 浏览器 / 钱包等条目，完全没有桌面选项，作为兜底会把用户带进死路。
     * resolvable 抽成可注入参数，便于单元测试不依赖真实 PackageManager 驱动分支。
     */
    fun defaultHomeSetupOptions(
        ctx: Context,
        resolvable: (Intent) -> Boolean = { it.resolveActivity(ctx.packageManager) != null }
    ): List<HomeSetupOption> {
        val all = listOf(
            HomeSetupOption("home_settings", homeSettingsIntent()),
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
