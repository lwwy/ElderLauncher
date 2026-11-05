package com.example.elderlauncher.core

import android.content.Context
import android.util.Log
import com.example.elderlauncher.data.LocalContacts
import com.example.elderlauncher.data.Prefs
import java.io.File

/**
 * 诊断报告生成器：把「版本 / 设备 / 功能开关 / 语音引擎 / 权限 / 崩溃列表 / 运行轨迹」
 * 拼成一份可直接发给开发者的纯文本。
 *
 * 只包含运行状态，不含联系人号码、照片、通话内容等隐私数据。
 * 崩溃捕获与轨迹记录在 [CrashLogger]，写「下载」目录在 [FileExporter]。
 */
object Diagnostics {

    private const val TAG = "ElderDiagnostics"
    private const val TRACE_TAIL_LINES = 400

    /**
     * 生成诊断报告并落盘到应用缓存目录（供 FileProvider 分享），
     * 同时复制一份到公共「下载」目录，防止分享面板没有可用目标。
     *
     * @return 生成的文件；失败返回 null
     */
    fun exportDiagnostic(context: Context): File? {
        val text = buildReport(context)
        return try {
            val dir = File(context.cacheDir, "diagnostics")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "diag-${CrashLogger.fileStamp()}.txt")
            f.writeText(text)
            try {
                FileExporter.writeToDownloads(context, f.name, text)
            } catch (_: Throwable) {
            }
            f
        } catch (t: Throwable) {
            Log.e(TAG, "导出诊断日志失败", t)
            null
        }
    }

    private fun buildReport(ctx: Context): String {
        val sb = StringBuilder()
        sb.appendLine("========== 老人桌面 · 诊断日志 ==========")
        sb.appendLine("导出时间: ${CrashLogger.stamp()}")
        sb.appendLine("应用版本: ${CrashLogger.appVersion(ctx)}")
        sb.appendLine("包名: ${ctx.packageName}")
        sb.appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.DEVICE})")
        sb.appendLine("系统: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("ABI: ${android.os.Build.SUPPORTED_ABIS?.joinToString() ?: "-"}")
        sb.appendLine()

        sb.appendLine("---------- 功能开关 ----------")
        sb.appendLine("来电播报: ${onOff(Prefs.enabled(ctx, Prefs.ANN_INCOMING))}")
        sb.appendLine("低电量播报: ${onOff(Prefs.enabled(ctx, Prefs.ANN_LOW))}")
        sb.appendLine("默认桌面: ${if (isDefaultHome(ctx)) "是" else "否"}")
        // 显式传默认 false：该项默认应为“关闭”（显示齿轮），与 enabled() 的默认 true 相反
        sb.appendLine("隐藏设置入口: ${onOff(Prefs.enabled(ctx, Prefs.HIDE_HOME_SETTINGS, false))}")
        sb.appendLine("常用联系人: ${contactSummary(ctx)}")
        sb.appendLine()

        sb.appendLine("---------- 语音播报 ----------")
        sb.appendLine("系统语音引擎: ${if (TtsManager.hasEngine(ctx)) "已安装" else "未检测到"}")
        sb.appendLine("初始化状态: ${if (TtsManager.isReady()) "就绪" else "未就绪"}")
        sb.appendLine("中文语音数据: ${if (TtsManager.langMissing) "缺失" else "正常/未知"}")
        sb.appendLine("最近错误: ${TtsManager.lastError ?: "无"}")
        sb.appendLine()

        sb.appendLine("---------- 权限状态 ----------")
        // 只列本应用会实际申请的权限。未声明/未申请的权限项会永远显示「未授权」，
        // 干扰判断来电播报到底能不能收到广播。
        val perms = listOf(
            "CALL_PHONE" to android.Manifest.permission.CALL_PHONE,
            "READ_CONTACTS" to android.Manifest.permission.READ_CONTACTS,
            "READ_PHONE_STATE" to android.Manifest.permission.READ_PHONE_STATE
        )
        for ((label, p) in perms) {
            sb.appendLine("$label: ${permState(ctx, p)}")
        }
        sb.appendLine()

        sb.appendLine("---------- 崩溃记录 ----------")
        val crashLines = crashSummary(ctx)
        if (crashLines.isEmpty()) sb.appendLine("(无)") else crashLines.forEach { sb.appendLine(it) }
        sb.appendLine()

        sb.appendLine("---------- 运行轨迹（最近 $TRACE_TAIL_LINES 行）----------")
        sb.appendLine(CrashLogger.traceTail(ctx, TRACE_TAIL_LINES))
        sb.appendLine("========== 结束 ==========")
        return sb.toString()
    }

    private fun onOff(v: Boolean) = if (v) "开启" else "关闭"

    private fun isDefaultHome(ctx: Context): Boolean = try {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        val resolved = ctx.packageManager.resolveActivity(
            intent,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )
        resolved?.activityInfo?.packageName == ctx.packageName
    } catch (_: Throwable) {
        false
    }

    /** 只统计已填几个联系人，不写入姓名/号码，避免隐私外泄 */
    private fun contactSummary(ctx: Context): String {
        val filled = (0 until LocalContacts.COUNT).count {
            !LocalContacts.get(ctx, it)?.name.isNullOrEmpty()
        }
        return "$filled / ${LocalContacts.COUNT} 已设置"
    }

    private fun permState(ctx: Context, permission: String): String = try {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) "已授权" else "未授权"
    } catch (_: Throwable) {
        "未知"
    }

    private fun crashSummary(ctx: Context): List<String> = try {
        CrashLogger.crashFiles(ctx).take(8).map { f ->
            "  ${f.name}  (${f.length() / 1024} KB, ${CrashLogger.shortTime(f.lastModified())})"
        }
    } catch (_: Throwable) {
        emptyList()
    }
}
