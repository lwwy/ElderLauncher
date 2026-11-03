package com.example.elderlauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textfield.TextInputEditText
import java.io.File

class ContactEditActivity : AppCompatActivity() {

    private var slot = 0
    private var hasExisting = false
    private var pendingCameraFile: File? = null

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
        val bmp = LocalContacts.loadAvatar(this, slot)
        if (bmp != null) {
            img.clearColorFilter()
            img.setImageBitmap(bmp)
        } else {
            img.setImageResource(R.drawable.ic_person)
            img.setColorFilter(
                ContextCompat.getColor(this, R.color.colorOnSurfaceVariant),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
        }
    }

    private fun pickAlbum() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        startActivityForResult(intent, REQ_ALBUM)
    }

    private fun takePhoto() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA_PERM)
            return
        }
        launchCamera()
    }

    private fun launchCamera() {
        val f = File(cacheDir, "capture_tmp_$slot.jpg").apply { pendingCameraFile = this }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_CAMERA)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA_PERM &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else if (requestCode == REQ_CAMERA_PERM) {
            Toast.makeText(this, R.string.camera_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_ALBUM -> {
                val uri = data?.data ?: return
                val bmp = LocalContacts.decodeUri(this, uri)
                if (bmp == null) {
                    Toast.makeText(this, R.string.avatar_failed, Toast.LENGTH_SHORT).show()
                    return
                }
                LocalContacts.saveAvatar(this, slot, bmp)
                loadAvatarPreview()
            }
            REQ_CAMERA -> {
                val f = pendingCameraFile ?: return
                val raw = BitmapFactory.decodeFile(f.absolutePath)
                val bmp = raw?.let { scaleDown(it, 512) } ?: run {
                    Toast.makeText(this, R.string.avatar_failed, Toast.LENGTH_SHORT).show()
                    return
                }
                LocalContacts.saveAvatar(this, slot, bmp)
                f.delete()
                loadAvatarPreview()
            }
        }
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

    private fun delete() {
        LocalContacts.delete(this, slot)
        Toast.makeText(this, R.string.contact_deleted, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val EXTRA_SLOT = "slot"
        private const val REQ_ALBUM = 11
        private const val REQ_CAMERA = 12
        private const val REQ_CAMERA_PERM = 100
    }
}
