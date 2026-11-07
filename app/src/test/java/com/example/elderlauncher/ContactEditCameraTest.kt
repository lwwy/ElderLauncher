package com.example.elderlauncher

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.example.elderlauncher.data.LocalContacts
import com.example.elderlauncher.ui.ContactEditActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * 编辑联系人头像「拍照」三处修复的回归测试：
 *  1. 无相机设备 / 无拍照应用时，不启动相机、给提示、绝不闪退；
 *  2. 拍照原图解码走采样，decodeFile 对损坏/超大输入安全返回，不 OOM；
 *  3. 拍照期间 Activity 被回收重建，待取回路径能从实例状态恢复，照片不丢。
 *
 * 说明：Robolectric 不能真的拉起系统相机、也不真正解码高像素 JPEG 到内存，
 * 因此「OOM」用纯逻辑 [LocalContacts.decodeFile] 的采样/异常兜底覆盖，
 * 「重建」用 ActivityController 的 saveInstanceState + 重新 create 覆盖。
 *
 * 另注：Robolectric 每个测试方法会换一个临时 cacheDir，而 androidx 的
 * FileProvider 按 authority 静态缓存了首次解析出的根目录，跨方法再用就会抛
 * 「Failed to find configured root」。这是测试沙箱差异（真机 cacheDir 固定），
 * 故发起拍照的用例注入 uriFor 绕开它；线上调用不带该参数，仍走真实 FileProvider。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ContactEditCameraTest {

    private lateinit var app: Application

    /** 测试专用的拍照输出 URI，避免依赖 FileProvider 的静态路径缓存 */
    private val fakeCameraUri: Uri
        get() = Uri.parse("content://${app.packageName}.camera.test/capture.jpg")

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        for (i in 0 until LocalContacts.COUNT) LocalContacts.delete(app, i)
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        for (i in 0 until LocalContacts.COUNT) LocalContacts.delete(app, i)
    }

    private fun edit(slot: Int): ContactEditActivity {
        val intent = Intent(app, ContactEditActivity::class.java)
            .putExtra(ContactEditActivity.EXTRA_SLOT, slot)
        return Robolectric.buildActivity(ContactEditActivity::class.java, intent)
            .create().visible().get()
    }

    // ---------------------------------------------------------- 1. 无相机不闪退

    @Test
    fun canTakePhoto_requiresBothCameraFeatureAndResolvableApp() {
        val a = edit(0)
        assertFalse("无相机硬件时不可用", a.canTakePhoto(hasCameraFeature = false, resolvable = { true }))
        assertFalse("有硬件但没有拍照应用时不可用", a.canTakePhoto(hasCameraFeature = true, resolvable = { false }))
        assertTrue("硬件与拍照应用齐备时才可用", a.canTakePhoto(hasCameraFeature = true, resolvable = { true }))
        a.finish()
    }

    @Test
    fun takePhoto_whenUnavailable_showsHintAndStartsNothing() {
        val a = edit(0)
        assertFalse("无相机时 takePhoto 应返回 false", a.takePhoto(available = false))
        assertEquals(
            "应给出可操作的提示",
            app.getString(R.string.camera_unavailable),
            ShadowToast.getTextOfLatestToast()
        )
        assertNull("无相机时不得启动任何 Activity", shadowOf(a).nextStartedActivity)
        a.finish()
    }

    @Test
    fun takePhoto_whenAvailable_launchesImageCaptureWithOutputUri() {
        val a = edit(0)
        assertTrue(
            "有相机时应发起成功",
            a.takePhoto(available = true, uriFor = { fakeCameraUri })
        )
        val started = shadowOf(a).nextStartedActivity
        assertNotNull("应启动拍照 Intent", started)
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, started!!.action)
        assertTrue(
            "必须带 EXTRA_OUTPUT，否则相机不知道把照片写到哪",
            started.extras?.containsKey(MediaStore.EXTRA_OUTPUT) == true
        )
        @Suppress("DEPRECATION")
        val outputUri = started.extras?.get(MediaStore.EXTRA_OUTPUT) as? Uri
        assertEquals(
            "EXTRA_OUTPUT 应指向注入的输出 URI",
            fakeCameraUri,
            outputUri
        )
        a.finish()
    }

    @Test
    fun noCameraFeature_hidesTakePhotoButton() {
        val a = edit(0)
        // Robolectric 默认不声明相机 feature / 无 ACTION_IMAGE_CAPTURE 应用
        val btn = a.findViewById<View>(R.id.btnTakePhoto)
        assertEquals("无相机设备应隐藏拍照按钮", View.GONE, btn.visibility)
        a.finish()
    }

    // ---------------------------------------------------------- 2. 采样解码防 OOM

    @Test
    fun decodeFile_onMissingOrEmptyFile_returnsNullNotCrash() {
        assertNull(
            "不存在的文件应安全返回 null",
            LocalContacts.decodeFile(File(app.cacheDir, "nope.jpg").absolutePath)
        )
        val empty = File(app.cacheDir, "empty.jpg").apply { writeBytes(ByteArray(0)) }
        assertNull("空文件应安全返回 null", LocalContacts.decodeFile(empty.absolutePath))
        empty.delete()

        // 损坏图片：Robolectric 的 BitmapFactory 是模拟实现，对「存在但无法解析」的文件
        // 会返回占位位图而非 null（真机上 decodeFile 才返回 null，由调用方统一提示
        // 「图片读取失败」）。此处只验证不崩溃、不抛异常，结果本身不做强断言。
        val broken = File(app.cacheDir, "broken.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        LocalContacts.decodeFile(broken.absolutePath)
        broken.delete()
    }

    @Test
    fun decodeFile_downscalesLargeBitmap() {
        // 造一张明显大于头像尺寸的真实位图，验证 decodeFile 采样后最大边收敛到 512 量级
        val src = Bitmap.createBitmap(2048, 1536, Bitmap.Config.ARGB_8888)
        val f = File(app.cacheDir, "big_avatar.jpg")
        f.outputStream().use { src.compress(Bitmap.CompressFormat.JPEG, 90, it) }

        val decoded = LocalContacts.decodeFile(f.absolutePath)
        assertNotNull("合法图片应解码成功", decoded)
        assertTrue(
            "采样后最大边应 ≤ 2*512（inSampleSize 为 2 的幂），实际 ${decoded!!.width}x${decoded.height}",
            maxOf(decoded.width, decoded.height) <= 1024
        )
        assertTrue(
            "采样后应明显小于原图 2048",
            maxOf(decoded.width, decoded.height) < 2048
        )
        f.delete()
    }

    @Test
    fun decodeUri_onBrokenStream_returnsNullNotCrash() {
        // 采样口径与 decodeFile 一致：非法输入都返回 null，调用方统一提示「图片读取失败」
        val broken = File(app.cacheDir, "broken_uri.bin").apply { writeBytes(ByteArray(16)) }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            app, "${app.packageName}.fileprovider", broken
        )
        assertNull("损坏 URI 应安全返回 null", LocalContacts.decodeUri(app, uri))
        broken.delete()
    }

    // ---------------------------------------------------------- 3. 切后台/重建照片不丢

    @Test
    fun pendingCameraPath_survivesActivityRecreation() {
        val controller = Robolectric.buildActivity(
            ContactEditActivity::class.java,
            Intent(app, ContactEditActivity::class.java)
                .putExtra(ContactEditActivity.EXTRA_SLOT, 1)
        ).create().visible()
        val a = controller.get()

        // 模拟「点拍照」：launchCamera 会把待取回路径写入实例状态
        assertTrue(a.takePhoto(available = true, uriFor = { fakeCameraUri }))
        assertTrue(
            "发起拍照后应有待取回路径",
            readField<String?>(a, "pendingCameraPath") != null
        )

        // 模拟「切到其他应用 → 系统回收并重建」：保存状态 → 用该状态重新 create
        val state = android.os.Bundle()
        controller.saveInstanceState(state)
        assertTrue(
            "实例状态里必须保存待取回路径",
            state.containsKey("camera_pending_file")
        )

        val recreated = Robolectric.buildActivity(ContactEditActivity::class.java)
            .create(state).get()
        val restored = readField<String?>(recreated, "pendingCameraPath")
        assertNotNull("重建后必须能恢复待取回路径", restored)
        assertTrue("恢复的路径应指向有效文件名", restored!!.contains("capture_tmp_"))
        recreated.finish()
        a.finish()
    }

    @Test
    fun savedAvatar_persistsAcrossRecreation() {
        // 拍照成功后头像立即落盘到私有 avatars/，重建后 loadAvatarPreview 仍能读回
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        LocalContacts.saveAvatar(app, 2, bmp)

        val recreated = edit(2)
        val img = recreated.findViewById<android.widget.ImageView>(R.id.imgAvatar)
        assertNotNull("重建后仍应显示已保存的头像（不是占位小人像）", img.drawable)
        assertEquals(
            "有真实头像时必须清除占位着色",
            null,
            img.imageTintList
        )
        recreated.finish()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> readField(target: Any, name: String): T {
        val f = ContactEditActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        return f.get(target) as T
    }
}
