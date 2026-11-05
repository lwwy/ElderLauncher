package com.example.elderlauncher.core

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃 / 运行轨迹记录器（纯本地，不联网）。
 *
 * 职责只有两件，各管一段：
 *  1. 捕获未处理异常（FATAL EXCEPTION），把完整堆栈写入文件；
 *  2. 记录启动过程的关键节点（trace），用于分析“没有 Java 堆栈就闪退”的情况
 *     （例如被系统强杀、native 崩溃）。
 *
 * 诊断报告在 [Diagnostics] 里生成，写「下载」目录在 [FileExporter] 里做，
 * 这里不再混着三件事。
 */
object CrashLogger {

    /** 调试开关：需要时改为 false 即可停止记录 */
    private const val ENABLED = true

    private const val TAG = "ElderCrash"
    private const val DIR_NAME = "crash"
    private const val TRACE_FILE = "trace.log"
    private const val MAX_TRACE_BYTES = 128 * 1024

    private const val PREFS = "elder_crash_debug"
    private const val KEY_SESSION_PENDING = "session_pending"

    @Volatile
    private var appContext: Context? = null

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fullFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    // ---------------------------------------------------------------- 初始化

    /** 在 Application.attachBaseContext 中调用，越早越好 */
    fun init(context: Context) {
        if (!ENABLED) return
        val app = context.applicationContext ?: context
        appContext = app

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val report = buildCrashReport(app, thread, error)
                val file = writeCrashFile(app, report)
                if (file != null) exportToDownloads(app, file)
            } catch (t: Throwable) {
                Log.e(TAG, "写入崩溃日志失败", t)
            }
            // 交回系统默认处理，保证系统正常弹出「应用已停止」提示
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }

        trace("==== 应用启动 ====")
        trace("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        trace("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        trace("版本: ${appVersion(app)}")
    }

    // ---------------------------------------------------------------- 轨迹

    /** 记录一个运行节点，崩溃报告里会附带这些内容 */
    fun trace(msg: String) {
        if (!ENABLED) return
        val ctx = appContext ?: return
        Log.i(TAG, msg)
        try {
            val f = File(dirFor(ctx), TRACE_FILE)
            if (f.exists() && f.length() > MAX_TRACE_BYTES) {
                // 超上限时只保留后半段，避免无限增长
                val tail = f.readText().takeLast(MAX_TRACE_BYTES / 2)
                f.writeText(tail)
            }
            f.appendText("[${timeFmt.format(Date())}] $msg\n")
        } catch (_: Throwable) {
            // 打点失败不影响主流程
        }
    }

    /** 首页真正显示出来后调用，表示这次启动已成功 */
    fun markSessionReady() {
        if (!ENABLED) return
        val ctx = appContext ?: return
        trace("启动完成，桌面已显示")
        try {
            prefs(ctx).edit().putBoolean(KEY_SESSION_PENDING, false).apply()
        } catch (_: Throwable) {
        }
    }

    /** 标记本次启动开始（用于判断“上次启动是否异常中断”） */
    fun markSessionStart() {
        if (!ENABLED) return
        val ctx = appContext ?: return
        try {
            prefs(ctx).edit().putBoolean(KEY_SESSION_PENDING, true).apply()
        } catch (_: Throwable) {
        }
    }

    /**
     * 上次启动是否异常中断（没有走到桌面显示）。
     * 用于处理“系统强杀、没有 Java 堆栈”的闪退。
     */
    fun lastSessionAbnormal(context: Context): Boolean = try {
        prefs(context).getBoolean(KEY_SESSION_PENDING, false)
    } catch (_: Throwable) {
        false
    }

    // ---------------------------------------------------------------- 导出

    /**
     * 把尚未导出的崩溃日志导出到公共「下载」目录。
     * @return 本次成功导出的文件名列表
     */
    fun exportPendingCrashes(context: Context): List<String> {
        if (!ENABLED) return emptyList()
        val result = mutableListOf<String>()
        try {
            val dir = dirFor(context)
            val names = dir.list() ?: return result
            val files = names.map { File(dir, it) }
                .filter { it.isFile && it.name.startsWith("crash-") && it.name.endsWith(".txt") }
                .sortedBy { it.lastModified() }
            for (f in files) {
                if (exportToDownloads(context, f)) {
                    result.add(f.name)
                    // 导出成功则改名，避免下次重复导出
                    f.renameTo(File(dir, f.name + ".done"))
                }
            }
        } catch (_: Throwable) {
        }
        return result
    }

    /** 把当前轨迹导出到「下载」，用于分析无堆栈的闪退 */
    fun exportTrace(context: Context): String? {
        if (!ENABLED) return null
        return try {
            val src = File(dirFor(context), TRACE_FILE)
            if (!src.exists()) return null
            val name = "trace-${fileFmt.format(Date())}.txt"
            val text = src.readText().takeLast(64 * 1024)
            if (FileExporter.writeToDownloads(context, name, text)) name else null
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- 内部实现

    /** 崩溃日志目录（外部私有目录优先，不可用时退到内部目录） */
    internal fun dirFor(ctx: Context): File {
        val base = try {
            ctx.getExternalFilesDir(null)
        } catch (_: Throwable) {
            null
        } ?: ctx.filesDir
        val d = File(base, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** 轨迹文件里的最后 [lines] 行，供诊断报告拼装 */
    internal fun traceTail(ctx: Context, lines: Int): String = try {
        val f = File(dirFor(ctx), TRACE_FILE)
        if (f.exists()) f.readLines().takeLast(lines).joinToString("\n") else "(无轨迹记录)"
    } catch (_: Throwable) {
        "(读取轨迹失败)"
    }

    /** 现有的崩溃文件（按文件名倒序），供诊断报告列出 */
    internal fun crashFiles(ctx: Context): List<File> = try {
        (dirFor(ctx).list() ?: emptyArray())
            .filter { it.startsWith("crash-") }
            .sortedDescending()
            .map { File(dirFor(ctx), it) }
    } catch (_: Throwable) {
        emptyList()
    }

    /** 版本号 + versionCode，崩溃报告与诊断报告共用 */
    @Suppress("DEPRECATION")
    internal fun appVersion(ctx: Context): String = try {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            p.longVersionCode
        } else {
            p.versionCode.toLong()
        }
        "${p.versionName} ($code)"
    } catch (_: Throwable) {
        "unknown"
    }

    /** 导出时间格式，供 Diagnostics 复用 */
    internal fun stamp(): String = fullFmt.format(Date())

    /** 文件时间戳格式（yyyyMMdd-HHmmss），供 Diagnostics 生成文件名 */
    internal fun fileStamp(): String = fileFmt.format(Date())

    /** 崩溃列表里每行的时间格式 */
    internal fun shortTime(ms: Long): String = timeFmt.format(Date(ms))

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun buildCrashReport(ctx: Context, thread: Thread, error: Throwable): String {
        val sb = StringBuilder()
        sb.appendLine("================ 崩溃报告 ================")
        sb.appendLine("时间: ${fullFmt.format(Date())}")
        sb.appendLine("异常线程: ${thread.name}")
        sb.appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("版本: ${appVersion(ctx)}")
        sb.appendLine("--------------- 异常堆栈 ---------------")
        sb.appendLine(Log.getStackTraceString(error))
        sb.appendLine("--------------- 运行轨迹 ---------------")
        sb.appendLine(readTrace(ctx))
        sb.appendLine("========================================")
        return sb.toString()
    }

    private fun readTrace(ctx: Context): String = try {
        val f = File(dirFor(ctx), TRACE_FILE)
        if (f.exists()) f.readText().takeLast(8000) else "(无轨迹记录)"
    } catch (_: Throwable) {
        "(读取轨迹失败)"
    }

    private fun writeCrashFile(ctx: Context, text: String): File? = try {
        val f = File(dirFor(ctx), "crash-${fileFmt.format(Date())}.txt")
        // 崩溃场景必须同步落盘，不能用异步写入
        f.writeText(text)
        f
    } catch (t: Throwable) {
        Log.e(TAG, "写崩溃文件失败", t)
        null
    }

    /** 把崩溃文件复制到公共「下载」目录（Android 10+ 走 MediaStore，无需存储权限） */
    private fun exportToDownloads(ctx: Context, file: File): Boolean = try {
        FileExporter.writeToDownloads(ctx, file.name, file.readText())
    } catch (_: Throwable) {
        false
    }
}
