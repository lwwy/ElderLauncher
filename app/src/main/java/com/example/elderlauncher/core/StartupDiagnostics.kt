package com.example.elderlauncher.core

import android.content.Context

/**
 * 启动诊断的**唯一入口**，只在真实 UI 启动路径（HomeActivity.onCreate）调用一次。
 *
 * 为什么不放 Application：AlarmManager 闹钟 / 开机广播会拉起「无头进程」，
 * Application 同样会被创建，但它没有桌面、没有 UI、也不代表一次用户启动。
 * 若在 Application 里读会话标记并导出诊断，无头进程会：
 *  - 把 session_pending 卡在 true（没有 HomeActivity 来清零）；
 *  - 每次唤醒误导出一份运行轨迹到「下载」目录；
 *  - 尝试延迟弹 Toast（无 UI 进程里毫无意义）。
 *
 * 这里把「读上次标记 → 写本次轨迹 → 置本次标记 → 导出 → 提示」收拢成一次调用，
 * 顺序固定：**必须先读旧标记，再置新标记**，否则永远读不到 true。
 * 返回需要提示用户的消息，由调用方决定何时 Toast；无异常时返回 null。
 */
internal object StartupDiagnostics {

    fun onUiStart(context: Context): String? {
        // 1) 先读「上次」标记；下面一旦置位，旧值就再也读不到了
        val previousAbnormal = CrashLogger.lastSessionAbnormal(context)

        // 2) 写启动轨迹并标记本次为一次真实 UI 启动尝试
        CrashLogger.traceStartup(context)
        CrashLogger.markSessionStart()

        // 3) 上次异常时额外留一条线索，并导出轨迹
        if (previousAbnormal) {
            CrashLogger.trace("检测到上次启动未完成，准备导出运行轨迹供排查")
        }
        val crashes = CrashLogger.exportPendingCrashes(context)
        val trace = if (previousAbnormal) CrashLogger.exportTrace(context) else null

        // 4) 拼提示文案；Toast 由 Activity 延迟弹出，避免冷启动时打扰首屏
        return when {
            crashes.isNotEmpty() ->
                "上次崩溃日志已保存到「下载」目录：${crashes.last()}，请发给开发者"
            trace != null ->
                "上次运行异常结束，诊断日志已保存到「下载」目录：$trace"
            else -> null
        }
    }
}
