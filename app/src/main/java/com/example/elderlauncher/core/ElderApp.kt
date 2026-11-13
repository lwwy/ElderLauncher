package com.example.elderlauncher.core

import android.app.Application
import android.content.Context

/**
 * 全局入口：只负责尽早安装崩溃捕获器。
 *
 * 注意：AlarmManager 闹钟、开机广播等「无头进程」同样会创建 Application，
 * 所以这里**不做**任何写轨迹 / 读会话标记 / 导出诊断 / 弹 Toast 的动作 ——
 * 那整段只在真实 UI 启动路径执行（见 `StartupDiagnostics.onUiStart`，由 HomeActivity 调用）。
 * 否则无头进程会把 session_pending 卡在 true，并在每次唤醒时误导出运行轨迹
 * （v1.2.16 修复的那个 bug）。
 *
 * 已知权衡：会话标记要等 Activity.onCreate 才置位，因此 Application 阶段被系统强杀 /
 * native 崩溃不会被判为「上次异常结束」；这类 Java 崩溃仍由 [CrashLogger] 的
 * 未捕获异常处理器写出崩溃报告。当前设计不在 Application 阶段猜测进程类型。
 *
 * 不再承担任何「保活 / 安全模式」职责：应用无常驻前台服务，
 * 低电量播报由系统按需拉起的静态广播接收器触发。
 */
class ElderApp : Application() {

    // 显式声明 public：ContextWrapper.attachBaseContext 是 protected，Kotlin 覆写时
    // 不写修饰符会继承 protected，测试便无法直接驱动完整生命周期；放宽不影响框架回调。
    public override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 尽早安装捕获器：之后发生的任何 Java 崩溃都能记录。
        // init 不写启动轨迹，避免每个无头进程都往 trace.log 刷屏。
        CrashLogger.init(base)
    }
}
