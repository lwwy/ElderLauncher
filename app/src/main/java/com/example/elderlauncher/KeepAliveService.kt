package com.example.elderlauncher

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

/**
 * 后台保活前台服务：常驻通知 + START_STICKY + 被划掉自启，
 * 降低被系统回收导致来电/通话异常的概率。
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "elder_keepalive"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val i = Intent(context, KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            // Android 13+ 未授予通知权限时无法显示前台通知：保活降级，但不影响通话功能
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
