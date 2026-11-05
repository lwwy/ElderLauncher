package com.example.elderlauncher.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

class ContactEditActivity : AppCompatActivity() {

    private var slot = 0
    private var hasExisting = false
    private var pendingCameraFile: File? = null

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

    /** 拍照：结果落在 FileProvider 提供的临时文件里 */
    private val cameraTaker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val f = pendingCameraFile ?: return@registerForActivityResult
        val bmp = BitmapFactory.decodeFile(f.absolutePath)?.let { scaleDown(it, 512) }
        if (bmp == null) {
            Toast.makeText(this, R.string.avatar_failed, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        LocalContacts.saveAvatar(this, slot, bmp)
        f.delete()
        loadAvatarPreview()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_edit)
        slot = intent.getIntExtra(EXTRA_SLOT, 0)

        val c = LocalContacts.get(this, slot)
        hasExisting = c != null
        findViewById<TextInputEditText>(R.id.etName).setText(c?.name ?: "")
        findViewById<TextInputEditText>(R.id.etPhone).setText(c?.phone ?: "")
        loadAvatarPreview()

        findViewById<ShapeableImageView>(R.id.imgAvatar).setOnClickListener { pickAlbum() }
        findViewById<MaterialButton>(R.id.btnFromAlbum).setOnClickListener { pickAlbum() }
        findViewById<MaterialButton>(R.id.btnTakePhoto).setOnClickListener { takePhoto() }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btnDelete).apply {
            visibility = if (hasExisting) View.VISIBLE else View.GONE
            setOnClickListener { delete() }
        }
    }

    private fun loadAvatarPreview() {
        val img = findViewById<ShapeableImageView>(R.id.imgAvatar)
        val placeholderTint = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.colorOnSurfaceVariant)
        )
        val bmp = LocalContacts.loadAvatar(this, slot)
        if (bmp != null) {
            // 真实头像：清除着色（否则照片会被灰色罩染成“空白”）
            img.imageTintList = null
            img.setImageBitmap(bmp)
        } else {
            img.imageTintList = placeholderTint
            img.setImageResource(R.drawable.ic_person)
        }
    }

    private fun pickAlbum() {
        val intent = Intent(Intent.ACTION_PICK)
            .setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        albumPicker.launch(intent)
    }

    private fun takePhoto() {
        // 调用系统相机 App 拍照不需要本应用声明/申请 CAMERA 权限，
        // 由系统相机自己处理授权，故直接拉起即可。
        launchCamera()
    }

    private fun launchCamera() {
        val f = File(cacheDir, "capture_tmp_$slot.jpg").apply { pendingCameraFile = this }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        cameraTaker.launch(intent)
    }

    private fun scaleDown(src: Bitmap, maxPx: Int): Bitmap {
        val w = src.width
        val h = src.height
        val ratio = minOf(maxPx.toFloat() / w, maxPx.toFloat() / h, 1f)
        if (ratio >= 1f) return src
        return Bitmap.createScaledBitmap(src, (w * ratio).toInt(), (h * ratio).toInt(), true)
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
        val name = LocalContacts.get(this, slot)?.name?.takeIf { it.isNotEmpty() } ?: "该联系人"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(getString(R.string.confirm_delete_msg, name))
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
    }
}
