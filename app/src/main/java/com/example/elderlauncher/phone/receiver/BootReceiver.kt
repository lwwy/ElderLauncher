package com.example.elderlauncher.phone.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.elderlauncher.service.KeepAliveService
import com.example.elderlauncher.ui.HomeActivity

/** 开机 / 快速重启 / 应用更新后：拉起桌面并启动保活前台服务 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // 若为默认桌面，系统也会自动显示；这里做兜底确保进入
                val home = Intent(context, HomeActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(home)
                } catch (_: Exception) {
                }
                // 启动保活前台服务，保证来电/通话不被系统回收
                KeepAliveService.start(context)
            }
        }
    }
}
