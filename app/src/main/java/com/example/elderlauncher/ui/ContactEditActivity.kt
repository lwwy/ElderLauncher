package com.example.elderlauncher.ui

import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.elderlauncher.R
import com.example.elderlauncher.data.LocalContacts
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textfield.TextInputEditText
import java.io.File

open class ContactEditActivity : AppCompatActivity() {

    private var slot = 0
    private var hasExisting = false

    /**
     * 待取回的拍照临时文件的绝对路径。
     *
     * 拍照会拉起系统相机，本 Activity 进入后台，期间可能被系统回收重建。
     * 若只把它放在普通字段里，重建后就变成 null，相机返回时读不到照片
     * —— 这正是「拍完照切出去再切回来照片消失」的根因。
     * 因此：① 文件名由 slot 确定性推导，随时可重建；② 路径额外存入
     * onSaveInstanceState，双保险。相机的结果由 ActivityResultRegistry 在重建后重新投递。
     */
    private var pendingCameraPath: String? = null

    /** 相册选图：用 ActivityResult API，替代已废弃的 startActivityForResult */
    private val albumPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val bmp = LocalContacts.decodeUri(this, uri)
        if (bmp == null) {
            Toast.makeText(this, R.string.avatar_failed, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        LocalContacts.saveAvatar(this, slot, bmp)
        loadAvatarPreview()
    }

    /** 拍照：结果落在 FileProvider 提供的临时文件里，取回时按采样解码 */
    private val cameraTaker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            clearPendingCamera()
            return@registerForActivityResult
        }
        // 路径优先取实例状态里恢复的值，取不到再用确定性文件名重建
        val f = pendingCameraPath?.let(::File) ?: cameraFile()
        val bmp = if (f.exists() && f.length() > 0) {
            LocalContacts.decodeFile(f.absolutePath)  // 采样解码，避免原图撑爆内存
        } else {
            null
        }
        if (bmp == null) {
            Toast.makeText(this, R.string.avatar_failed, Toast.LENGTH_SHORT).show()
            clearPendingCamera()
            return@registerForActivityResult
        }
        // 拍照成功立即落盘到私有 avatars/ 目录：之后即使切后台、Activity 重建，
        // onCreate 的 loadAvatarPreview 也能从磁盘把照片读回来，不会消失。
        LocalContacts.saveAvatar(this, slot, bmp)
        clearPendingCamera()
        loadAvatarPreview()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_edit)
        slot = intent.getIntExtra(EXTRA_SLOT, 0)
        // 恢复「正在等待拍照结果」的路径（Activity 在相机期间被回收重建时非空）
        pendingCameraPath = savedInstanceState?.getString(KEY_CAMERA_FILE)

        val c = LocalContacts.get(this, slot)
        hasExisting = c != null
        findViewById<TextInputEditText>(R.id.etName).setText(c?.name ?: "")
        findViewById<TextInputEditText>(R.id.etPhone).setText(c?.phone ?: "")
        loadAvatarPreview()

        findViewById<ShapeableImageView>(R.id.imgAvatar).setOnClickListener { pickAlbum() }
        findViewById<MaterialButton>(R.id.btnFromAlbum).setOnClickListener { pickAlbum() }
        // 不再基于 resolveActivity 预检隐藏按钮：Android 11+ 包可见性会使预检误判为
        // 「无应用」而错误隐藏入口（<queries> 未声明相机/相册 intent 时尤其明显）。
        // 改为始终显示，由 takePhoto 内部 try-catch 兜底，真正无相机时启动抛异常再提示，
        // 绝不闪退（官方推荐做法，无需为相机/相册声明 <queries>）。
        findViewById<MaterialButton>(R.id.btnTakePhoto).setOnClickListener { takePhoto() }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btnDelete).apply {
            visibility = if (hasExisting) View.VISIBLE else View.GONE
            setOnClickListener { delete() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingCameraPath?.let { outState.putString(KEY_CAMERA_FILE, it) }
    }

    private fun loadAvatarPreview() {
        val img = findViewById<ShapeableImageView>(R.id.imgAvatar)
        val placeholderTint = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.colorOnSurfaceVariant)
        )
        val bmp = LocalContacts.loadAvatar(this, slot)
        if (bmp != null) {
            // 真实头像：清除着色（否则照片会被灰色罩染成"空白"）
            img.imageTintList = null
            img.setImageBitmap(bmp)
        } else {
            img.imageTintList = placeholderTint
            img.setImageResource(R.drawable.ic_person)
        }
    }

    /**
     * 从相册选图。不再做 resolveActivity 预检（Android 11+ 包可见性会让预检误判，
     * 反而把可用入口错误关掉）；直接 launch，由 try-catch 兜底：极少数情况下系统
     * 判定无可处理的应用而抛 [ActivityNotFoundException] 时，给提示而非闪退。
     */
    private fun pickAlbum() {
        val intent = Intent(Intent.ACTION_PICK)
            .setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        try {
            launchAlbumPicker(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.album_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /** 实际发起相册选择；抽成 internal open 便于单测注入「无应用 → 抛异常」路径 */
    internal open fun launchAlbumPicker(intent: Intent) {
        albumPicker.launch(intent)
    }

    /**
     * 点击「拍照」。不再基于 resolveActivity 预检（同相册，包可见性会误判），
     * 直接发起；无相机硬件 / 无拍照应用时系统抛出 [ActivityNotFoundException]，
     * 由 try-catch 捕获并提示，绝不闪退。调用系统相机不需要本应用 CAMERA 权限。
     */
    internal fun takePhoto(
        // 抽出 URI 构造便于测试注入：Robolectric 每个用例换一个 cacheDir，
        // 而 FileProvider 会静态缓存首次解析出的根目录，跨用例会误判为「未配置的 root」；
        // 默认与线上完全一致，真实点击不带此参数。
        uriFor: (File) -> Uri = {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", it)
        }
    ): Boolean = try {
        launchCamera(uriFor)
        true
    } catch (_: Exception) {
        Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
        false
    }

    internal open fun launchCamera(uriFor: (File) -> Uri) {
        val f = cameraFile()
        // 清掉上次可能残留的临时文件，避免把旧照片当成这次结果
        if (f.exists()) f.delete()
        pendingCameraPath = f.absolutePath
        val uri = uriFor(f)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        cameraTaker.launch(intent)
    }

    /** 拍照临时文件：文件名只由 slot 推导，Activity 重建后仍可定位 */
    private fun cameraFile(): File = File(cacheDir, "capture_tmp_$slot.jpg")

    /** 结束一次拍照流程：清状态并删除临时文件（头像已另存到私有 avatars/） */
    private fun clearPendingCamera() {
        pendingCameraPath = null
        cameraFile().delete()
    }

    private fun save() {
        val name = findViewById<TextInputEditText>(R.id.etName).text.toString().trim()
        val phone = findViewById<TextInputEditText>(R.id.etPhone).text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.contact_empty_name, Toast.LENGTH_SHORT).show()
            return
        }
        if (phone.isEmpty()) {
            Toast.makeText(this, R.string.contact_empty_phone, Toast.LENGTH_SHORT).show()
            return
        }
        LocalContacts.set(this, slot, LocalContacts.Contact(name, phone))
        Toast.makeText(this, R.string.contact_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    internal fun delete() {
        val name = LocalContacts.get(this, slot)?.name?.takeIf { it.isNotEmpty() }
        // 无名联系人用专门文案，避免出现"删除联系人 该联系人"这种自我重复的啰嗦表述
        val msg = if (name != null) {
            getString(R.string.confirm_delete_msg, name)
        } else {
            getString(R.string.confirm_delete_msg_unnamed)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(msg)
            .setNegativeButton(R.string.cancel) { d, _ -> d.dismiss() }
            .setPositiveButton(R.string.delete_contact) { _, _ ->
                LocalContacts.delete(this, slot)
                Toast.makeText(this, R.string.contact_deleted, Toast.LENGTH_SHORT).show()
                finish()
            }
            .show()
    }

    companion object {
        const val EXTRA_SLOT = "slot"
        private const val KEY_CAMERA_FILE = "camera_pending_file"
    }
}
