package com.example.elderlauncher.core

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 全局入口：安装崩溃日志捕获，并在启动时把上次的诊断日志导出到「下载」目录，
 * 方便用文件管理器直接取出后反馈。
 */
class ElderApp : Application() {

    /** 上次启动是否没走到桌面就结束了（崩溃或被系统强杀） */
    private var lastSessionAbnormal = false

    /**
     * 安全模式：上次启动异常时，本次先不启动保活前台服务，
     * 确保至少能进到桌面并把日志留在手机里；成功启动一次后自动恢复正常。
     */
    var skipKeepAliveOnce = false
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 尽早安装捕获器，Application 之后发生的任何崩溃都能记录
        CrashLogger.init(base)
        lastSessionAbnormal = CrashLogger.lastSessionAbnormal(base)
        skipKeepAliveOnce = lastSessionAbnormal
        CrashLogger.markSessionStart()
        if (lastSessionAbnormal) {
            CrashLogger.trace("检测到上次启动未完成，本次进入安全模式（跳过保活服务）")
        }
    }

    override fun onCreate() {
        super.onCreate()
        CrashLogger.trace("ElderApp.onCreate 完成")

        val crashes = CrashLogger.exportPendingCrashes(this)
        val trace = if (lastSessionAbnormal) CrashLogger.exportTrace(this) else null

        val msg = when {
            crashes.isNotEmpty() ->
                "上次崩溃日志已保存到「下载」目录：${crashes.last()}，请发给开发者"
            trace != null ->
                "上次运行异常结束，诊断日志已保存到「下载」目录：$trace"
            else -> null
        }
        if (msg != null) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {
                }
            }, 2000)
        }
    }
}
