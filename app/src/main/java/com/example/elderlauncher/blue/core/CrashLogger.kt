package com.example.elderlauncher.blue.core

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
 *
 * 隐私：所有会离开应用私有目录的文本（诊断报告、崩溃报告、运行轨迹，
 * 以及导出到公共「下载」目录的副本）都在写入 / 读出 / 导出边界经过
 * [redactPhoneNumbers] 脱敏，绝不携带完整电话号码。
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

    /**
     * 日期 / 时间戳片段，避免把导出时间、崩溃报告时间与日志文件名误判成号码：
     * `2026-11-07`（fullFmt）与 `20261107-144201`（fileFmt）两种形态都要放过。
     */
    private val DATE_LIKE = Regex("^\\d{4}-\\d{2}-\\d{2}|^\\d{8}-\\d{6}")

    /**
     * 疑似号码片段：以数字或 + 开头、由数字 / + / - / 空格组成、长度 >= 7。
     * 允许 +、-、空格，是为了兜住旧版写入的带分隔符号码（如 138-0013-8000）。
     * 前后排除字母数字与已脱敏的 * 号，避免误伤机型型号（22081212C）、
     * 版本号、API level，也不会二次改动已有的 ****8000 结果。
     */
    private val NUMBER_RUN =
        Regex("(?<![A-Za-z0-9*])[+\\d][\\d\\-+ ]{5,}\\d(?![A-Za-z0-9])")

    /** 系统异常信息里可能出现的 tel: URI，号码中可带 +、-、空格 */
    private val TEL_URI = Regex("tel:[+\\d][\\d\\-+ ]{4,}\\d")

    @Volatile
    private var appContext: Context? = null

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fullFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /**
     * 日志脱敏：把疑似完整号码替换成 **** + 末 4 位。
     *
     * 为什么在“日志边界”再兜一层，而不只在 [com.example.elderlauncher.blue.phone.TelecomHelper]
     * 写入时处理：
     *  - 覆盖安装后，旧版本已经写进 trace.log / 崩溃文件的完整号码仍会被导出；
     *  - 系统抛出的异常 message 可能自带 `dat=tel:13800138000` 这类完整 URI。
     * 只改写入端拦不住这两条路径。
     *
     * 纯字符串变换，不改动任何业务数据；对已脱敏的 ****8000 天然幂等
     * （4 位数字不满足 7 位阈值，且 * 号被排除在匹配之外）。
     */
    internal fun redactPhoneNumbers(text: String): String {
        // 先处理 tel: URI，保留 tel: 前缀，只把号码换成 ****末 4 位
        val withTel = TEL_URI.replace(text) { m ->
            val digits = m.value.substringAfter("tel:").filter { it.isDigit() }
            if (digits.length >= 4) "tel:****" + digits.takeLast(4) else m.value
        }
        return NUMBER_RUN.replace(withTel) { m ->
            val raw = m.value
            val digits = raw.filter { it.isDigit() }
            when {
                // 短数字段（时间戳 14:42:01、API 34、版本号 1.2.10 等）保持原样
                digits.length < 7 -> raw
                DATE_LIKE.containsMatchIn(raw) -> raw
                else -> "****" + digits.takeLast(4)
            }
        }
    }

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
        val safe = redactPhoneNumbers(msg)
        Log.i(TAG, safe)
        try {
            val f = File(dirFor(ctx), TRACE_FILE)
            if (f.exists() && f.length() > MAX_TRACE_BYTES) {
                // 超上限时只保留后半段，避免无限增长
                val tail = f.readText().takeLast(MAX_TRACE_BYTES / 2)
                f.writeText(redactPhoneNumbers(tail))
            }
            f.appendText("[${timeFmt.format(Date())}] $safe\n")
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

    /**
     * 标记本次为「真实 UI 启动尝试」，用于判断“上次启动是否异常中断（没走到桌面显示）”。
     *
     * 注意：本方法**只应在前台 UI 启动路径（HomeActivity.onCreate）调用**。
     * 闹钟/开机等由系统拉起的「无头进程」不要调用——否则进程拉起后没有桌面显示，
     * 标记会一直卡在 true，下一次启动会被误判为“上次启动未完成”而误导出轨迹。
     */
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
            val text = redactPhoneNumbers(src.readText().takeLast(64 * 1024))
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
        if (f.exists()) redactPhoneNumbers(f.readLines().takeLast(lines).joinToString("\n"))
        else "(无轨迹记录)"
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
        sb.appendLine(redactPhoneNumbers(Log.getStackTraceString(error)))
        sb.appendLine("--------------- 运行轨迹 ---------------")
        sb.appendLine(readTrace(ctx))
        sb.appendLine("========================================")
        return sb.toString()
    }

    private fun readTrace(ctx: Context): String = try {
        val f = File(dirFor(ctx), TRACE_FILE)
        if (f.exists()) redactPhoneNumbers(f.readText().takeLast(8000)) else "(无轨迹记录)"
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
        FileExporter.writeToDownloads(ctx, file.name, redactPhoneNumbers(file.readText()))
    } catch (_: Throwable) {
        false
    }
}
