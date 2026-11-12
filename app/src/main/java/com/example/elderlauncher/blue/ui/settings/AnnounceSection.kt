package com.example.elderlauncher.blue.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.elderlauncher.blue.R
import com.example.elderlauncher.blue.core.LowBatterySound
import com.example.elderlauncher.blue.core.TtsManager
import com.example.elderlauncher.blue.data.Prefs
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * 设置页「语音播报」分区：低电量播报开关、试听、自定义提示音、TTS 引擎异常引导。
 *
 * 从 [com.example.elderlauncher.blue.ui.SettingsActivity] 拆出的独立职责块，行为与原实现一致：
 * Activity 只保留生命周期与 [onTonePicked] 等对外入口，本类负责该分区的控件绑定与全部逻辑。
 *
 * @param pickTone 触发「选择提示音」的回调。SAF 的 ActivityResult 注册必须留在 Activity
 *                 的字段初始化阶段，因此这里通过回调反向调用，不在本类里注册 launcher。
 */
internal class AnnounceSection(
    private val activity: AppCompatActivity,
    private val pickTone: () -> Unit
) {

    /** 绑定低电量播报开关、试听按钮、提示音按钮，并刷新一次提示音状态 */
    fun bind() {
        val swLow = activity.findViewById<SwitchMaterial>(R.id.swLow)
        swLow.isChecked = Prefs.enabled(activity, Prefs.ANN_LOW)
        swLow.setOnCheckedChangeListener { _, v -> Prefs.set(activity, Prefs.ANN_LOW, v) }

        activity.findViewById<MaterialButton>(R.id.btnTestLow).setOnClickListener {
            playLowBatteryPreview()
        }
        activity.findViewById<MaterialButton>(R.id.btnPickTone).setOnClickListener {
            pickTone()
        }
        activity.findViewById<MaterialButton>(R.id.btnResetTone).setOnClickListener {
            LowBatterySound.clear(activity)
            refreshLowToneUi()
            Toast.makeText(activity, R.string.low_tone_reset, Toast.LENGTH_SHORT).show()
        }
        refreshLowToneUi()
    }

    /**
     * 低电量播报试听：选了自定义提示音就播文件，否则走原来的 TTS 试听。
     * 未选择文件时行为与老版本完全一致；自定义音频播放失败时给明确提示并临时回退 TTS。
     */
    private fun playLowBatteryPreview() {
        if (!LowBatterySound.hasCustom(activity)) {
            testAnnounce(activity.getString(R.string.ann_low_battery))
            return
        }
        if (LowBatterySound.play(activity)) {
            Toast.makeText(activity, R.string.test_playing, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(activity, R.string.low_tone_failed, Toast.LENGTH_LONG).show()
            TtsManager.speakNow(activity, activity.getString(R.string.ann_low_battery))
        }
    }

    /** 文件选择回调：复制落盘 + 按结果提示 + 刷新界面 */
    internal fun onTonePicked(uri: Uri?) {
        if (uri == null) return
        val name = queryDisplayName(uri)
        when (LowBatterySound.save(activity, uri, name)) {
            LowBatterySound.SaveResult.OK -> Toast.makeText(
                activity,
                activity.getString(
                    R.string.low_tone_picked,
                    name ?: activity.getString(R.string.low_tone_fallback_name)
                ),
                Toast.LENGTH_LONG
            ).show()
            LowBatterySound.SaveResult.TOO_LARGE -> Toast.makeText(
                activity, R.string.low_tone_too_large, Toast.LENGTH_LONG
            ).show()
            LowBatterySound.SaveResult.FAILED -> Toast.makeText(
                activity, R.string.low_tone_failed, Toast.LENGTH_LONG
            ).show()
        }
        refreshLowToneUi()
    }

    /** 从 SAF URI 取原始文件名，仅用于展示；取不到不影响播放 */
    private fun queryDisplayName(uri: Uri): String? = try {
        activity.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }

    /** 刷新提示音状态文字与「恢复提示音」按钮的可用状态 */
    private fun refreshLowToneUi() {
        val hasCustom = LowBatterySound.hasCustom(activity)
        val label = if (hasCustom) {
            activity.getString(
                R.string.low_tone_status_custom,
                LowBatterySound.displayName(activity)
                    ?: activity.getString(R.string.low_tone_fallback_name)
            )
        } else {
            activity.getString(R.string.low_tone_status_system)
        }
        activity.findViewById<TextView>(R.id.tvLowToneStatus).text = label
        activity.findViewById<MaterialButton>(R.id.btnResetTone).isEnabled = hasCustom
    }

    /**
     * 播放一次示例播报。
     * TTS 初始化是异步的，HyperOS 等 ROM 首次冷启动可能耗时 3~5 秒，
     * 过早判定会误报「未检测到语音引擎」，所以这里给 5 秒宽限，并且失败时
     * 给出可操作的对话框（去系统设置 / 重试），而不是一句无从下手的 Toast。
     */
    private fun testAnnounce(text: String) {
        if (!TtsManager.hasEngine(activity)) {
            showTtsDialog(activity.getString(R.string.tts_no_engine), text)
            return
        }
        TtsManager.speakNow(activity, text)
        Toast.makeText(activity, R.string.test_playing, Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({
            if (!TtsManager.isReady()) {
                val msg = if (TtsManager.langMissing) {
                    activity.getString(R.string.tts_no_lang)
                } else {
                    activity.getString(R.string.tts_unavailable) +
                        (TtsManager.lastError?.let { "\n\n($it)" } ?: "")
                }
                showTtsDialog(msg, text)
            }
        }, 5000)
    }

    private fun showTtsDialog(message: String, retryText: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.tts_failed_title)
            .setMessage(message)
            .setPositiveButton(R.string.tts_go_settings) { _, _ -> openTtsSettings() }
            .setNeutralButton(R.string.tts_retry) { _, _ ->
                // 释放后重新初始化，避免上次 ERROR 后永久不可用
                TtsManager.shutdown()
                Toast.makeText(activity, R.string.tts_retrying, Toast.LENGTH_SHORT).show()
                testAnnounce(retryText)
            }
            .setNegativeButton(R.string.tts_cancel, null)
            .show()
    }

    private fun openTtsSettings() {
        val candidates = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            try {
                activity.startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(activity, R.string.tts_unavailable, Toast.LENGTH_LONG).show()
    }
}
