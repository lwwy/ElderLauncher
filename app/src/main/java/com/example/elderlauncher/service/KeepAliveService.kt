package com.example.elderlauncher.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.elderlauncher.R
import com.example.elderlauncher.core.CrashLogger
import com.example.elderlauncher.ui.HomeActivity

/**
 * 后台保活前台服务：常驻通知 + START_STICKY + 被划掉自启，
 * 降低被系统回收导致来电/通话异常的概率。
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "elder_keepalive"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            CrashLogger.trace("KeepAliveService.start 被调用")
            val i = Intent(context, KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
                CrashLogger.trace("startForegroundService 调用成功")
            } catch (e: Exception) {
                CrashLogger.trace("startForegroundService 抛出: ${e.javaClass.name}: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CrashLogger.trace("KeepAliveService.onCreate 开始")
        createChannel()
        CrashLogger.trace("通知渠道创建完成")
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 使用 dataSync 类型：仅需普通权限，无“默认拨号器”等前置条件，
                // 避免 Android 14+ 用 phoneCall 类型导致 startForeground 失败进而被系统强杀。
                startForeground(
                    NOTIF_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                CrashLogger.trace("startForeground(dataSync) 成功")
            } else {
                startForeground(NOTIF_ID, notification)
                CrashLogger.trace("startForeground(旧版本) 成功")
            }
        } catch (e: Exception) {
            CrashLogger.trace("startForeground(dataSync) 失败: ${e.javaClass.name}: ${e.message}")
            // 兜底：即使带类型失败，也务必以“无类型”方式成功转前台，
            // 否则由 startForegroundService 启动的服务会触发 RemoteServiceException 崩溃。
            try {
                startForeground(NOTIF_ID, notification)
                CrashLogger.trace("startForeground(无类型兜底) 成功")
            } catch (e2: Exception) {
                CrashLogger.trace("startForeground(无类型兜底) 也失败: ${e2.javaClass.name}: ${e2.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 被系统杀死后尝试重启
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 用户从最近任务划掉时重启，保证来电/通话不被中断
        start(this)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.keepalive_notice),
                    NotificationManager.IMPORTANCE_LOW
                )
                ch.setShowBadge(false)
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.keepalive_notice))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
