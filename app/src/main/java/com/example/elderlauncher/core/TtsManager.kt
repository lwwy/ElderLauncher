package com.example.elderlauncher.core

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 本地语音播报封装：使用设备自带的 TextToSpeech 引擎，
 * 不依赖任何网络/远端 TTS 服务（与原始 App 的远端合成不同，此处纯本地）。
 *
 * 健壮性要点（小米 HyperOS 等 ROM 上踩过的坑）：
 * 1. 初始化失败必须允许重试：早期实现里 `tts != null` 就直接 return，
 *    一旦首次 init 拿到 ERROR（引擎还没起来 / 被系统限流），后面永远不可用。
 * 2. `setLanguage` 可能返回 LANG_MISSING_DATA / LANG_NOT_SUPPORTED（没装中文数据包），
 *    此时 status 是 SUCCESS 但播报无声，所以要检查返回值并回退语言。
 * 3. 引擎初始化是异步的，首次在低端机上可能超过 2 秒，调用方不要过早判死。
 * 4. 播报统一走闹钟通道（[AnnounceAudio]）：setAudioAttributes 为主，
 *    同时用传统 streamType 参数兜底，兼容只认老参数的国产 TTS 引擎。
 */
object TtsManager {
    private var tts: TextToSpeech? = null
    private var ready = false

    /** TTS 异步初始化期间先暂存的待播文本，就绪后立刻播出去 */
    private var pending: String? = null

    /** 最近一次失败原因，供设置页给出可操作提示 */
    var lastError: String? = null
        private set

    /** 中文语音数据是否缺失（缺数据时会播报无声） */
    var langMissing = false
        private set

    /** 仅供单元测试观测实际应用的音频通道（生产逻辑不依赖它） */
    internal var appliedAudioAttributes: AudioAttributes? = null

    /**
     * 系统里是否有可用的 TTS 引擎。
     * 注意：TextToSpeech.getEngines() 是实例方法，未创建实例时调不到，
     * 所以这里用 PackageManager 查 TTS_SERVICE 的注册情况。
     */
    fun hasEngine(context: Context): Boolean = enginePackages(context).isNotEmpty()

    /** 系统里已安装的 TTS 引擎包名（按系统返回顺序） */
    private fun enginePackages(context: Context): List<String> = try {
        // 常量 TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE 属于系统 API，直接用字面量更稳
        context.packageManager
            .queryIntentServices(Intent("android.intent.action.TTS_SERVICE"), 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
    } catch (e: Exception) {
        emptyList()
    }

    fun init(context: Context) {
        if (tts != null) return
        lastError = null
        val ctx = context.applicationContext
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                // 顺序很重要：先清零再判语言。applyLanguage() 内部发现缺中文数据时会把
                // langMissing 置 true，若之后再清零，这个状态位就永远是 false，
                // 设置页与诊断日志也就永远看不到"缺中文语音数据"。
                langMissing = false
                applyLanguage()
                applyAudioAttributes()
                ready = true
                CrashLogger.trace("TTS 初始化成功")
                flushPending()
            } else {
                // 关键：失败要把实例置空，否则下次调用会因为 tts != null 而永不重试
                lastError = "引擎初始化失败 status=$status"
                ready = false
                appliedAudioAttributes = null
                CrashLogger.trace("TTS 初始化失败，status=$status，已释放以便重试")
                try {
                    tts?.shutdown()
                } catch (_: Exception) {
                }
                tts = null
            }
        }
        try {
            // 系统没设默认引擎时（部分 ROM 出厂如此）显式用已安装引擎，避免直接 ERROR。
            // getDefaultEngine()/getEngines() 都是隐藏 API，公开途径读 Secure 设置项。
            val defaultEngine = try {
                android.provider.Settings.Secure.getString(
                    ctx.contentResolver,
                    android.provider.Settings.Secure.TTS_DEFAULT_SYNTH
                )
            } catch (e: Exception) {
                null
            }
            tts = if (defaultEngine.isNullOrBlank()) {
                val pkgs = enginePackages(ctx)
                if (pkgs.isEmpty()) {
                    lastError = "系统未安装任何语音引擎"
                    CrashLogger.trace("TTS 失败：系统未安装任何语音引擎")
                    null
                } else {
                    CrashLogger.trace("TTS 无默认引擎，改用 ${pkgs[0]}")
                    TextToSpeech(ctx, listener, pkgs[0])
                }
            } else {
                TextToSpeech(ctx, listener)
            }
        } catch (e: Exception) {
            lastError = "创建引擎异常：${e.javaClass.simpleName} ${e.message}"
            CrashLogger.trace("TTS 创建异常：${e.message}")
            tts = null
            ready = false
            appliedAudioAttributes = null
        }
    }

    /** 设置播报语言：优先中文，缺数据包时回退系统语言，尽量让用户先听到声音 */
    private fun applyLanguage() {
        val engine = tts ?: return
        try {
            engine.setSpeechRate(0.95f)
            val r = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                langMissing = true
                CrashLogger.trace("TTS 中文数据缺失（code=$r），回退系统语言")
                val r2 = engine.setLanguage(Locale.getDefault())
                if (r2 == TextToSpeech.LANG_MISSING_DATA || r2 == TextToSpeech.LANG_NOT_SUPPORTED) {
                    CrashLogger.trace("TTS 系统语言同样不可用（code=$r2）")
                }
            }
        } catch (e: Exception) {
            CrashLogger.trace("TTS 设置语言异常：${e.message}")
        }
    }

    /**
     * 把播报通道设为闹钟流：跟随系统独立的「闹钟音量」，
     * 手机静音 / 媒体音量为 0 / 系统勿扰默认放行闹钟时仍能听到提醒。
     */
    private fun applyAudioAttributes() {
        val engine = tts ?: return
        val attrs = AnnounceAudio.alarm()
        // 先记录本次采用的通道：部分 ROM 的引擎不接受 setAudioAttributes，
        // 此时仍有 alarmParams() 的 streamType 兜底，通道意图不变。
        appliedAudioAttributes = attrs
        try {
            engine.setAudioAttributes(attrs)
        } catch (e: Exception) {
            CrashLogger.trace("TTS 设置闹钟通道异常（streamType 兜底）：${e.message}")
        }
    }

    /**
     * 部分 ROM 的 TTS 引擎只认传统的 streamType 参数，这里与 setAudioAttributes
     * 双保险，保证播报真的落在闹钟流。用字面量 key 避免引用已废弃的常量。
     */
    private fun alarmParams(): Bundle = Bundle().apply {
        putInt("streamType", AudioManager.STREAM_ALARM)
    }

    private fun flushPending() {
        val text = pending ?: return
        pending = null
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, alarmParams(), "elder_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            CrashLogger.trace("TTS 播报异常：${e.message}")
        }
    }

    fun isReady(): Boolean = ready

    fun speak(text: String) {
        if (!ready) {
            pending = text
            return
        }
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, alarmParams(), "elder_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            CrashLogger.trace("TTS 播报异常：${e.message}")
        }
    }

    /**
     * 在 TTS 尚未初始化（如广播接收器内、或上次初始化失败）时也能播报。
     * 失败后再次调用会重新初始化，不会永久卡死。
     */
    fun speakNow(context: Context, text: String) {
        pending = text
        if (tts == null) {
            init(context)
        } else if (ready) {
            pending = null
            speak(text)
        }
        // tts 非空但未 ready：说明正在初始化，pending 已存，就绪后自动播
    }

    fun shutdown() {
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
        pending = null
        appliedAudioAttributes = null
    }
}
