package com.example.elderlauncher.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.elderlauncher.Scheduler

/** 开机后重新调度整点报时（需先在 App 内打开过一次以授予权限） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Scheduler.scheduleHourly(context)
        }
    }
}
