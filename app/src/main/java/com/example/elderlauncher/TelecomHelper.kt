package com.example.elderlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
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
        } catch (e: Exception) {
            Log.e("TelecomHelper", "register failed", e)
        }
    }

    /** 发起呼出电话（仅当本应用为默认电话应用时生效） */
    fun placeCall(context: Context, number: String) {
        try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val uri = Uri.fromParts("tel", number, null)
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, getHandle(context))
            }
            tm.placeCall(uri, extras)
        } catch (e: Exception) {
            Log.e("TelecomHelper", "placeCall failed", e)
            // 兜底：直接拉起系统拨号
            try {
                context.startActivity(
                    Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (_: Exception) {
            }
        }
    }
}
