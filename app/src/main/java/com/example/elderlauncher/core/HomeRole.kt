package com.example.elderlauncher.core

import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
 * RoleManager 只用于「申请成为默认桌面」和诊断角色持有状态。
 */
object HomeRole {

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
     * 请求成为默认桌面：API 29+ 走官方 RoleManager 角色申请（系统弹确认框，一步到位）。
     * 角色不可用或低版本时返回 null，由调用方回退到系统「默认主屏幕」设置。
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
}
