package com.example.elderlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

/** 通话相关工具：注册 PhoneAccount、发起呼出 */
object TelecomHelper {
    const val ACCOUNT_ID = "elder_account"

    fun getHandle(context: Context): PhoneAccountHandle =
        PhoneAccountHandle(ComponentName(context, ElderConnectionService::class.java), ACCOUNT_ID)

    /**
     * 取一个"真正能打电话"的账号（运营商 SIM）。
     *
     * 注意：绝不能返回本应用自己注册的 [ElderConnectionService] 账号——那个账号只是
     * 为了让系统把本应用识别为拨号器，本身没有蜂窝能力，路由过去只会一直停在"拨号中"。
     */
    fun simAccount(context: Context): PhoneAccountHandle? {
        return try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val accounts = tm.callCapablePhoneAccounts ?: return null
            // 排除本应用自建的账号，优先系统给出的 simCallManager
            val others = accounts.filter {
                it.componentName?.packageName != context.packageName
            }
            tm.simCallManager?.takeIf { it in others } ?: others.firstOrNull()
        } catch (e: Exception) {
            Log.e("TelecomHelper", "simAccount failed", e)
            null
        }
    }

    /** 本应用当前是否为系统默认电话应用 */
    fun isDefaultDialer(context: Context): Boolean {
        return try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.defaultDialerPackage == context.packageName
        } catch (e: Exception) {
            false
        }
    }

    /** 注册本应用为可拨号账号（设为默认电话应用后由系统路由来电/去电） */
    fun register(context: Context) {
        try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val handle = getHandle(context)
            val account = PhoneAccount.builder(handle, context.getString(R.string.app_name))
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .setIcon(Icon.createWithResource(context, R.drawable.ic_launcher_foreground))
                .build()
            tm.registerPhoneAccount(account)
            CrashLogger.trace("registerPhoneAccount 成功")
        } catch (e: Exception) {
            CrashLogger.trace("registerPhoneAccount 失败: ${e.javaClass.name}: ${e.message}")
            Log.e("TelecomHelper", "register failed", e)
        }
    }

    /**
     * 发起呼出电话。
     *
     * 默认电话应用时用 [TelecomManager.placeCall]，但**必须**把 EXTRA_PHONE_ACCOUNT_HANDLE
     * 指向真实 SIM 账号（而不是本应用的假账号），否则系统会显示"正在拨号"却永远不接通。
     * 非默认电话应用时退回 ACTION_CALL，交给系统拨号器处理。
     */
    fun placeCall(context: Context, number: String) {
        if (number.isBlank()) return
        val uri = Uri.fromParts("tel", number, null)
        val extras = Bundle().apply {
            // 官方免提参数，交给系统在通话建立时自动开免提
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
            }
            simAccount(context)?.let {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it)
            }
        }

        try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && isDefaultDialer(context)) {
                tm.placeCall(uri, extras)
                CrashLogger.trace("placeCall(SIM) 发起：$number")
                return
            }
        } catch (e: Exception) {
            CrashLogger.trace("placeCall 失败: ${e.javaClass.name}: ${e.message}")
            Log.e("TelecomHelper", "placeCall failed", e)
        }

        // 兜底：非默认电话应用 / placeCall 异常 → 走系统拨号
        try {
            context.startActivity(
                Intent(Intent.ACTION_CALL, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtras(extras)
                }
            )
            CrashLogger.trace("ACTION_CALL 兜底发起：$number")
        } catch (e: Exception) {
            CrashLogger.trace("ACTION_CALL 也失败: ${e.javaClass.name}: ${e.message}")
        }
    }
}
