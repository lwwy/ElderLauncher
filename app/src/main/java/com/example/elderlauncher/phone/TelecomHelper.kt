package com.example.elderlauncher.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import com.example.elderlauncher.core.CrashLogger

/**
 * 通话相关工具：发起呼出。
 *
 * 本应用不是系统默认拨号器，也不申请 READ_PHONE_STATE / ROLE_DIALER，
 * 旧实现里的「SIM 账号选择 + TelecomManager.placeCall」分支因此恒不可达，
 * 每次拨号还会写一条误导性的「未授予 READ_PHONE_STATE」轨迹（真机日志实证）。
 * 该死代码已整套删除，现在只有两条真实路径：
 *  - [placeCall]：已授予 CALL_PHONE 时直接拨出（ACTION_CALL）；
 *  - [dial]：未授予或用户拒绝 CALL_PHONE 时降级打开系统拨号盘（ACTION_DIAL，免权限），
 *    号码自动预填，用户按一下拨打键即可 —— 绝不出现「拒绝权限就完全打不了电话」。
 *
 * 隐私：写日志一律用 [maskNumber] 只留末 4 位；异常 message 可能自带 tel: URI，
 * 再过一层 [CrashLogger.redactPhoneNumbers] 兜底，绝不落完整号码。
 */
object TelecomHelper {

    /**
     * 日志脱敏：只保留号码末 4 位（例如 13800138000 → ****8000）。
     * 先滤掉 +、-、空格等分隔符再取末 4 位，保证带格式存储的号码也不会被完整带出；
     * 不足 4 位（110 / 120 短号）全部打码，绝不回显完整号码。
     */
    internal fun maskNumber(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length >= 4) "****" + digits.takeLast(4) else "****"
    }

    private fun telUri(number: String): Uri = Uri.fromParts("tel", number, null)

    /** 已授予 CALL_PHONE 时直接拨出；任何失败都降级到 [dial]，绝不静默失败 */
    fun placeCall(context: Context, number: String) {
        if (number.isBlank()) return
        try {
            context.startActivity(
                Intent(Intent.ACTION_CALL, telUri(number)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtras(Bundle().apply {
                        // 官方免提参数，交给系统在通话建立时自动开免提
                        putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
                    })
                }
            )
            CrashLogger.trace("ACTION_CALL 直拨发起，拨打号码：${maskNumber(number)}")
        } catch (e: Exception) {
            CrashLogger.trace(
                CrashLogger.redactPhoneNumbers(
                    "ACTION_CALL 不可用，降级 ACTION_DIAL：${e.javaClass.name}: ${e.message}"
                )
            )
            dial(context, number)
        }
    }

    /**
     * 免权限降级：打开系统拨号盘并预填号码。
     * ACTION_DIAL 不需要 CALL_PHONE，用户拒绝授权 / 未授予权限时仍能完成拨号。
     */
    fun dial(context: Context, number: String) {
        if (number.isBlank()) return
        try {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, telUri(number)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            CrashLogger.trace("ACTION_DIAL 降级拨号盘，预填号码：${maskNumber(number)}")
        } catch (e: Exception) {
            CrashLogger.trace(
                CrashLogger.redactPhoneNumbers(
                    "ACTION_DIAL 也失败：${e.javaClass.name}: ${e.message}"
                )
            )
        }
    }
}
