package com.example.elderlauncher.blue.core

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import com.example.elderlauncher.blue.data.Prefs
import java.io.File
import java.io.InputStream

/**
 * 低电量播报的「自定义提示音」：让用户选一段本地音频，替代系统 TTS 念固定文案。
 *
 * 设计要点（与「设为默认桌面」一样，坚持「默认行为不变、失败必回退」）：
 * 1. 只作用于低电量播报；未选择提示音时仍走 [TtsManager]，完全不受影响。
 * 2. SAF 选到的 content:// URI 只在 Activity 存活期间有效，因此拿到后**立刻复制**到
 *    应用私有目录 filesDir/low_battery/tone。之后播放只读私有文件：不依赖原 URI 的
 *    持久授权、不怕原文件被删/移动/重启、后台兼容路径（BatteryReceiver）也能播。
 *    因为立即复制，既不需要 takePersistableUriPermission，也不需要任何存储权限。
 * 3. 固定单个文件名（覆盖式，只保留最后选的一个）+ 10MB 上限，避免占空间或一直播。
 * 4. 播放失败 / 文件失效一律回退 TTS，绝不静默失败（见 [resolveRoute]）。
 * 5. 「恢复系统语音」= 删除私有文件 + 清除显示名，行为回到默认。
 */
object LowBatterySound {

    /** 单个提示音大小上限：10MB。超出则拒绝保存，避免选到整首歌一直播 / 占空间 */
    const val MAX_BYTES = 10L * 1024 * 1024

    private const val DIR_NAME = "low_battery"
    private const val FILE_NAME = "tone"

    /**
     * 诊断日志 / 运行轨迹里显示的固定占位名。
     * 用户所选音频的原始文件名可能含姓名等个人信息，可导出的日志一律不写真实名；
     * 设置页 UI 仍通过 [displayName] 在本机显示真实文件名，不受影响。
     */
    private const val LOG_TONE_LABEL = "自定义音频.mp3"

    /** 保存结果，供设置页给出对应提示 */
    enum class SaveResult { OK, TOO_LARGE, FAILED }

    /** 本次播报实际走的音源分支；FALLBACK = 自定义播放失败后回退 TTS */
    enum class Route { TTS, CUSTOM, FALLBACK }

    internal enum class CopyOutcome { OK, TOO_LARGE, FAILED }

    /** 正在播放的实例；重复点击试听时先释放旧的，避免多个音频叠加 */
    @Volatile
    private var current: MediaPlayer? = null

    /** 仅供单元测试观测本次播放实际应用的音频通道（生产逻辑不依赖它） */
    internal var lastAppliedAudioAttributes: AudioAttributes? = null

    fun toneFile(ctx: Context): File =
        File(File(ctx.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }, FILE_NAME)

    /** 是否有可用的自定义提示音（文件存在且非空即可；显示名只用于界面展示） */
    fun hasCustom(ctx: Context): Boolean = try {
        val f = toneFile(ctx)
        f.isFile && f.length() > 0
    } catch (_: Throwable) {
        false
    }

    /** 用户选择时记下的文件名，仅用于设置页与诊断日志展示；取不到时返回 null */
    fun displayName(ctx: Context): String? = Prefs.getString(ctx, Prefs.LOW_TONE_NAME)

    /** 诊断日志用的一行状态描述；只区分系统语音 / 自定义，不写真实文件名 */
    fun statusLabel(ctx: Context): String =
        if (hasCustom(ctx)) LOG_TONE_LABEL else "系统语音"

    /**
     * 把 SAF 选中的音频复制到私有目录。
     *
     * @param displayName 从 OpenableColumns.DISPLAY_NAME 取到的原始文件名，仅作展示
     */
    fun save(ctx: Context, uri: Uri, displayName: String?): SaveResult {
        return try {
            val input = ctx.contentResolver.openInputStream(uri)
            if (input == null) {
                SaveResult.FAILED
            } else {
                input.use { saveFromStream(ctx, it, displayName) }
            }
        } catch (e: Exception) {
            CrashLogger.trace("低电量提示音：保存失败 ${e.javaClass.simpleName}: ${e.message}")
            SaveResult.FAILED
        }
    }

    /** 从 InputStream 落盘，抽出来便于单测用 ByteArrayInputStream 驱动三种结果 */
    internal fun saveFromStream(
        ctx: Context,
        input: InputStream,
        displayName: String?
    ): SaveResult {
        val dir = File(ctx.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }
        val tmp = File(dir, "$FILE_NAME.tmp")
        val outcome = copyWithLimit(input, tmp, MAX_BYTES)
        if (outcome != CopyOutcome.OK) {
            tmp.delete()
            return if (outcome == CopyOutcome.TOO_LARGE) SaveResult.TOO_LARGE else SaveResult.FAILED
        }
        val target = File(dir, FILE_NAME)
        val saved = if (tmp.renameTo(target)) {
            true
        } else {
            try {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
                true
            } catch (_: Exception) {
                false
            }
        }
        if (!saved) {
            tmp.delete()
            return SaveResult.FAILED
        }
        // 真实文件名只存进 Prefs 供设置页本机展示；运行轨迹里只写固定占位名
        Prefs.setString(ctx, Prefs.LOW_TONE_NAME, displayName?.takeIf { it.isNotBlank() })
        CrashLogger.trace("低电量提示音：已保存 $LOG_TONE_LABEL")
        return SaveResult.OK
    }

    /** 纯 IO 逻辑：边复制边累计，超过 maxBytes 立即停止且不留完整文件 */
    internal fun copyWithLimit(input: InputStream, dest: File, maxBytes: Long): CopyOutcome {
        var total = 0L
        var tooLarge = false
        try {
            dest.outputStream().use { out ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    total += n
                    if (total > maxBytes) {
                        tooLarge = true
                        break
                    }
                    out.write(buf, 0, n)
                }
            }
        } catch (_: Exception) {
            return CopyOutcome.FAILED
        }
        if (tooLarge) return CopyOutcome.TOO_LARGE
        return if (dest.length() > 0L) CopyOutcome.OK else CopyOutcome.FAILED
    }

    /** 清除自定义提示音，恢复默认系统语音 */
    fun clear(ctx: Context) {
        releaseCurrent()
        try {
            toneFile(ctx).delete()
            File(File(ctx.filesDir, DIR_NAME), "$FILE_NAME.tmp").delete()
        } catch (_: Throwable) {
        }
        Prefs.setString(ctx, Prefs.LOW_TONE_NAME, null)
        CrashLogger.trace("低电量提示音：已恢复系统语音")
    }

    /**
     * 播放私有目录里的提示音。
     * @return 是否成功开始播放；false 表示调用方应回退 TTS
     */
    fun play(ctx: Context): Boolean {
        val f = toneFile(ctx)
        if (!f.isFile || f.length() == 0L) return false
        releaseCurrent()
        return try {
            val attrs = AnnounceAudio.alarm()
            lastAppliedAudioAttributes = attrs
            val mp = MediaPlayer().apply {
                // 统一走闹钟通道：跟随系统「闹钟音量」流，静音 / 媒体音量为 0 时仍能听到
                setAudioAttributes(attrs)
                setDataSource(f.absolutePath)
                setOnCompletionListener { player ->
                    if (current === player) current = null
                    player.release()
                }
                setOnErrorListener { player, _, _ ->
                    if (current === player) current = null
                    player.release()
                    true
                }
                prepare()
                start()
            }
            current = mp
            CrashLogger.trace("低电量播报：播放自定义提示音")
            true
        } catch (e: Exception) {
            CrashLogger.trace(
                "低电量播报：自定义提示音播放失败，回退 TTS（${e.javaClass.simpleName}: ${e.message}）"
            )
            releaseCurrent()
            false
        }
    }

    private fun releaseCurrent() {
        current?.let { p ->
            try {
                if (p.isPlaying) p.stop()
            } catch (_: Exception) {
            }
            try {
                p.release()
            } catch (_: Exception) {
            }
        }
        current = null
    }

    /** 纯决策逻辑：给定「有无自定义」与「播放是否成功」，返回本次应走的音源分支 */
    internal fun resolveRoute(hasCustom: Boolean, playOk: Boolean): Route = when {
        !hasCustom -> Route.TTS
        playOk -> Route.CUSTOM
        else -> Route.FALLBACK
    }
}
