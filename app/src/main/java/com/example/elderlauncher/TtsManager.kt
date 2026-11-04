package com.example.elderlauncher

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 本地语音播报封装：使用设备自带的 TextToSpeech 引擎，
 * 不依赖任何网络/远端 TTS 服务（与原始 App 的远端合成不同，此处纯本地）。
 */
object TtsManager {
    private var tts: TextToSpeech? = null
    private var ready = false

    /** TTS 异步初始化期间先暂存的待播文本，就绪后立刻播出去 */
    private var pending: String? = null

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
                tts?.setSpeechRate(0.95f)
                ready = true
                CrashLogger.trace("TTS 初始化成功")
                pending?.let { text ->
                    pending = null
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "elder_${System.currentTimeMillis()}")
                }
            } else {
                CrashLogger.trace("TTS 初始化失败，status=$status")
            }
        }
    }

    fun isReady(): Boolean = ready

    fun speak(text: String) {
        if (!ready) {
            pending = text
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "elder_${System.currentTimeMillis()}")
    }

    /** 在 TTS 尚未初始化（如广播接收器内）时也能直接播报 */
    fun speakNow(context: Context, text: String) {
        if (tts == null) {
            pending = text
            init(context)
        } else {
            speak(text)
        }
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
        pending = null
    }
}
