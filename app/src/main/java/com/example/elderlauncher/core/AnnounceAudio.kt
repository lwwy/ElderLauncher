package com.example.elderlauncher.core

import android.media.AudioAttributes

/**
 * 低电量播报（系统 TTS 与自定义提示音）共用的音频通道。
 *
 * 用户拍板：统一走「闹钟通道」USAGE_ALARM，直接改默认、不加开关、不进设置页。
 * 播报因此跟随系统独立的「闹钟音量」流，而不是媒体 / 通知音量流 —— 手机静音、
 * 媒体音量调到 0、或系统勿扰模式默认放行闹钟时，仍能听到提醒。
 *
 * 注意：这是通道选择，不是绕过系统静音。用户把「闹钟音量」本身调到 0 时仍会无声；
 * 极少数 ROM 的「就寝模式」也会连闹钟一起屏蔽。
 */
internal object AnnounceAudio {
    fun alarm(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
}
