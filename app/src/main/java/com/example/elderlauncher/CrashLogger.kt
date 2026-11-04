package com.example.elderlauncher

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试期崩溃 / 运行轨迹记录器（纯本地，不联网）。
 *
 * 作用：
 *  1. 捕获未处理异常（FATAL EXCEPTION），把完整堆栈写入文件；
 *  2. 记录启动过程的关键节点（trace），用于分析“没有 Java 堆栈就闪退”的情况
 *     （例如被系统强杀、native 崩溃）；
 *  3. 崩溃文件同时尝试写入公共「下载」目录，方便用文件管理器直接取出。
 *
 * 说明：正式发布前可以关闭 [ENABLED] 或整体移除本文件。
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

    /**
     * 上次启动是否异常中断（没有走到桌面显示）。
     * 用于处理“系统强杀、没有 Java 堆栈”的闪退。
     */
    fun lastSessionAbnormal(context: Context): Boolean = try {
        prefs(context).getBoolean(KEY_SESSION_PENDING, false)
    } catch (_: Throwable) {
        false
    }

    /** 把当前轨迹导出到「下载」，用于分析无堆栈的闪退 */
    fun exportTrace(context: Context): String? {
        if (!ENABLED) return null
        return try {
            val src = File(dirFor(context), TRACE_FILE)
            if (!src.exists()) return null
            val name = "trace-${fileFmt.format(Date())}.txt"
            val text = src.readText().takeLast(64 * 1024)
            if (writeToDownloads(context, name, text)) name else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 生成一份可直接发给开发者的诊断报告：
     * 版本 / 设备 / 功能开关 / 语音引擎状态 / 权限 / 崩溃列表 / 运行轨迹。
     * 只包含运行状态，不含联系人号码、照片、通话内容等隐私数据。
     *
     * @return 生成的文本文件（位于应用缓存目录，可通过 FileProvider 分享）
     */
    fun exportDiagnostic(context: Context): File? {
        if (!ENABLED) return null
        val text = buildDiagnostic(context)
        return try {
            val dir = File(context.cacheDir, "diagnostics")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "diag-${fileFmt.format(Date())}.txt")
            f.writeText(text)
            // 同时落一份到公共「下载」目录，万一分享面板没有目标也能手动取
            try {
                writeToDownloads(context, f.name, text)
            } catch (_: Throwable) {
            }
            f
        } catch (t: Throwable) {
            Log.e(TAG, "导出诊断日志失败", t)
            null
        }
    }

    private fun buildDiagnostic(ctx: Context): String {
        val sb = StringBuilder()
        sb.appendLine("========== 老人桌面 · 诊断日志 ==========")
        sb.appendLine("导出时间: ${fullFmt.format(Date())}")
        sb.appendLine("应用版本: ${appVersion(ctx)}")
        sb.appendLine("包名: ${ctx.packageName}")
        sb.appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABI: ${Build.SUPPORTED_ABIS?.joinToString() ?: "-"}")
        sb.appendLine()

        sb.appendLine("---------- 功能开关 ----------")
        sb.appendLine("来电播报: ${onOff(Prefs.enabled(ctx, Prefs.ANN_INCOMING))}")
        sb.appendLine("低电量播报: ${onOff(Prefs.enabled(ctx, Prefs.ANN_LOW))}")
        sb.appendLine("默认桌面: ${if (isDefaultHome(ctx)) "是" else "否"}")
        sb.appendLine("常用联系人: ${contactSummary(ctx)}")
        sb.appendLine()

        sb.appendLine("---------- 语音播报 ----------")
        sb.appendLine("系统语音引擎: ${if (TtsManager.hasEngine(ctx)) "已安装" else "未检测到"}")
        sb.appendLine("初始化状态: ${if (TtsManager.isReady()) "就绪" else "未就绪"}")
        sb.appendLine("中文语音数据: ${if (TtsManager.langMissing) "缺失" else "正常/未知"}")
        sb.appendLine("最近错误: ${TtsManager.lastError ?: "无"}")
        sb.appendLine()

        sb.appendLine("---------- 权限状态 ----------")
        val perms = listOf(
            "CALL_PHONE" to android.Manifest.permission.CALL_PHONE,
            "READ_CONTACTS" to android.Manifest.permission.READ_CONTACTS,
            "CAMERA" to android.Manifest.permission.CAMERA,
            "READ_PHONE_STATE" to android.Manifest.permission.READ_PHONE_STATE,
            "ANSWER_PHONE_CALLS" to android.Manifest.permission.ANSWER_PHONE_CALLS,
            "POST_NOTIFICATIONS" to "android.permission.POST_NOTIFICATIONS"
        )
        for ((label, p) in perms) {
            sb.appendLine("$label: ${permState(ctx, p)}")
        }
        sb.appendLine("通知使用权: ${if (hasNotificationListener(ctx)) "已开启" else "未开启"}")
        sb.appendLine()

        sb.appendLine("---------- 崩溃记录 ----------")
        val crashLines = crashFiles(ctx)
        if (crashLines.isEmpty()) sb.appendLine("(无)") else crashLines.forEach { sb.appendLine(it) }
        sb.appendLine()

        sb.appendLine("---------- 运行轨迹（最近 400 行）----------")
        sb.appendLine(readTraceTail(ctx))
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

    /**
     * 通知使用权（读取通知栏）是否开启。保活服务依赖它做兜底，
     * 属关键排查项，但查询失败不影响日志生成。
     */
    private fun hasNotificationListener(ctx: Context): Boolean {
        return try {
            val enabled = android.provider.Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners"
            )
            enabled != null && enabled.contains(ctx.packageName)
        } catch (_: Throwable) {
            false
        }
    }

    private fun crashFiles(ctx: Context): List<String> = try {
        val dir = dirFor(ctx)
        (dir.list() ?: emptyArray())
            .filter { it.startsWith("crash-") }
            .sortedDescending()
            .take(8)
            .map { name ->
                val f = File(dir, name)
                "  $name  (${f.length() / 1024} KB, ${timeFmt.format(Date(f.lastModified()))})"
            }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun readTraceTail(ctx: Context): String {
        return try {
            val f = File(dirFor(ctx), TRACE_FILE)
            if (!f.exists()) {
                "(无轨迹记录)"
            } else {
                f.readLines().takeLast(400).joinToString("\n")
            }
        } catch (_: Throwable) {
            "(读取轨迹失败)"
        }
    }

    // ---------------------------------------------------------------- 内部实现

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dirFor(ctx: Context): File {
        val base = try {
            ctx.getExternalFilesDir(null)
        } catch (_: Throwable) {
            null
        } ?: ctx.filesDir
        val d = File(base, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeToDownloads(ctx, file.name, file.readText())
        } else {
            false
        }
    } catch (_: Throwable) {
        false
    }

    private fun writeToDownloads(ctx: Context, name: String, text: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                false
            } else {
                val os = ctx.contentResolver.openOutputStream(uri)
                if (os == null) {
                    false
                } else {
                    os.use { it.write(text.toByteArray()) }
                    true
                }
            }
        } else {
            false
        }
    } catch (_: Throwable) {
        false
    }

    @Suppress("DEPRECATION")
    private fun appVersion(ctx: Context): String = try {
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
}
