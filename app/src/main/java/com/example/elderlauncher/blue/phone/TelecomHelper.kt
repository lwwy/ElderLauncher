package com.example.elderlauncher.blue.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.elderlauncher.blue.core.CrashLogger

/**
 * 通话相关工具：发起呼出。
 *
 * 本应用不再注册自己的 PhoneAccount（那个账号没有蜂窝能力，只会让呼出卡在"拨号中"），
 * 一律走运营商 SIM 账号或系统拨号器。
 */
object TelecomHelper {

    /**
     * 日志脱敏：只保留号码末 4 位（例如 13800138000 → ****8000）。
     *
     * 诊断日志 / 运行轨迹会被导出到公共「下载」目录并可能发给开发者，
     * 因此任何写日志的地方都不得出现完整电话号码。先滤掉 +、-、空格等分隔符
     * 再取末 4 位，保证带格式存储的号码也不会被完整带出。
     */
    internal fun maskNumber(number: String): String {
        val digits = number.filter { it.isDigit() }
        // 不足 4 位（如 110 / 120 短号）无法"只留后 4 位"，全部打码，绝不回显完整号码
        return if (digits.length >= 4) "****" + digits.takeLast(4) else "****"
    }

    /**
     * 取一个"真正能打电话"的账号（运营商 SIM）。
     * 排除本应用名下的账号，优先系统给出的 simCallManager。
     */
    fun simAccount(context: Context): PhoneAccountHandle? {
        // 读通话账号需要 READ_PHONE_STATE；没授权时不勉强，让调用方走 ACTION_CALL 兜底
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            CrashLogger.trace("simAccount 跳过：未授予 READ_PHONE_STATE")
            return null
        }
        return try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val accounts = tm.callCapablePhoneAccounts ?: return null
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

    /**
     * 发起呼出电话。
     *
     * 默认电话应用时用 [TelecomManager.placeCall]，并且把 EXTRA_PHONE_ACCOUNT_HANDLE
     * 指向真实 SIM 账号；否则退回 ACTION_CALL 交给系统拨号器处理。
     */
    fun placeCall(context: Context, number: String) {
        if (number.isBlank()) return
        // 只用于发起呼叫；写日志一律用 [maskNumber]，绝不落完整号码
        val uri = Uri.fromParts("tel", number, null)
        val extras = Bundle().apply {
            // 官方免提参数，交给系统在通话建立时自动开免提
            putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
            simAccount(context)?.let {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it)
            }
        }

        try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (isDefaultDialer(context) &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                tm.placeCall(uri, extras)
                CrashLogger.trace("placeCall(SIM) 发起，拨打号码：${maskNumber(number)}")
                return
            }
        } catch (e: Exception) {
            // 异常信息里可能带 dat=tel:138... ，trace 与 logcat 都只写脱敏后的文本
            val failure = CrashLogger.redactPhoneNumbers(
                "placeCall failed: ${e.javaClass.name}: ${e.message}"
            )
            CrashLogger.trace(failure)
            Log.e("TelecomHelper", failure)
        }

        // 兜底：非默认电话应用 / placeCall 异常 → 走系统拨号
        try {
            context.startActivity(
                Intent(Intent.ACTION_CALL, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtras(extras)
                }
            )
            CrashLogger.trace("ACTION_CALL 兜底发起，拨打号码：${maskNumber(number)}")
        } catch (e: Exception) {
            CrashLogger.trace("ACTION_CALL 也失败: ${e.javaClass.name}: ${e.message}")
        }
    }
}
