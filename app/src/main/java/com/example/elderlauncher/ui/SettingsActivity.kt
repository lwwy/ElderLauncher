package com.example.elderlauncher.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.elderlauncher.R
import com.example.elderlauncher.core.HomeRole
import com.example.elderlauncher.ui.settings.AnnounceSection
import com.example.elderlauncher.ui.settings.ContactsSection
import com.example.elderlauncher.ui.settings.HomeRoleSection
import com.example.elderlauncher.ui.settings.PermissionSection

/**
 * 设置页：只负责生命周期编排与 ActivityResult 注册，具体功能按分区拆到
 * [AnnounceSection] / [ContactsSection] / [HomeRoleSection] / [PermissionSection]
 * 四个小块，各自持有自己的控件绑定与逻辑，互不影响，新增功能时不必再往本类堆代码。
 *
 * 对外保留若干 internal 方法作为薄委托入口（既有单元测试直接驱动这些方法），
 * 签名与行为与原单体实现完全一致。
 */
class SettingsActivity : AppCompatActivity() {

    /**
     * 选择低电量自定义提示音。SAF 的 OpenDocument 免存储权限；
     * 拿到 URI 后立刻复制到应用私有目录，不依赖持久授权。
     * registerForActivityResult 必须在字段初始化阶段注册，故保留在 Activity 内。
     */
    private val tonePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> announce.onTonePicked(uri) }

    private val announce by lazy { AnnounceSection(this, ::pickTone) }
    private val contacts by lazy { ContactsSection(this) }
    private val homeRole by lazy { HomeRoleSection(this) }
    private val permission by lazy { PermissionSection(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 各分区各自绑定自己的控件并设置监听
        announce.bind()
        contacts.buildRows()
        homeRole.bind()
        permission.bind()
    }

    /** 触发 SAF 选文件；无可用文件管理器时给明确提示，不静默失败 */
    private fun pickTone() {
        try {
            tonePicker.launch(arrayOf("audio/*"))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.low_tone_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** 每次回到设置页都重建联系人列表，并检测默认桌面是否设置成功 */
    override fun onResume() {
        super.onResume()
        contacts.buildRows()
        homeRole.onResume()
    }

    // ------------------------------------------------------------------
    // 以下 internal 方法为既有单元测试的驱动入口，转发到对应分区，行为不变
    // ------------------------------------------------------------------

    internal fun onTonePicked(uri: android.net.Uri?) = announce.onTonePicked(uri)

    internal fun requestDefaultHome(
        isDefault: Boolean = HomeRole.isDefaultHome(this),
        options: List<HomeRole.HomeSetupOption> = HomeRole.defaultHomeSetupOptions(this)
    ): String? = homeRole.requestDefaultHome(isDefault, options)

    internal fun onDefaultHomeStateChanged(nowDefault: Boolean): Boolean =
        homeRole.onDefaultHomeStateChanged(nowDefault)

    internal fun restoreSystemHome(isDefaultHome: Boolean = HomeRole.isDefaultHome(this)) =
        homeRole.restoreSystemHome(isDefaultHome)

    internal fun requestIgnoreBattery(
        alreadyIgnoring: Boolean = isIgnoringBatteryOptimizations(),
        resolvable: (android.content.Intent) -> Boolean = {
            it.resolveActivity(packageManager) != null
        },
        launch: (android.content.Intent) -> Unit = { startActivity(it) }
    ): String? = permission.requestIgnoreBattery(alreadyIgnoring, resolvable, launch)

    private fun isIgnoringBatteryOptimizations(): Boolean = try {
        val pm = getSystemService(android.os.PowerManager::class.java)
        pm?.isIgnoringBatteryOptimizations(packageName) == true
    } catch (_: Throwable) {
        false
    }
}
