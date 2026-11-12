package com.example.elderlauncher.blue.core

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 全局入口：安装崩溃日志捕获，并在启动时把上次的诊断日志导出到「下载」目录，
 * 方便用文件管理器直接取出后反馈。
 *
 * 不再承担任何「保活/安全模式」职责：应用无常驻前台服务，
 * 低电量播报由系统按需拉起的静态广播接收器触发。
 */
class ElderApp : Application() {

    /** 上次启动是否没走到桌面就结束了（崩溃或被系统强杀），仅用于导出诊断轨迹 */
    private var lastSessionAbnormal = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 尽早安装捕获器，Application 之后发生的任何崩溃都能记录
        CrashLogger.init(base)
        lastSessionAbnormal = CrashLogger.lastSessionAbnormal(base)
        if (lastSessionAbnormal) {
            CrashLogger.trace("检测到上次启动未完成，已导出运行轨迹供排查")
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
