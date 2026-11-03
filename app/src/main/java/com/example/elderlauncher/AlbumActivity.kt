package com.example.elderlauncher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class AlbumActivity : AppCompatActivity() {

    private val paths = ArrayList<String>()
    private lateinit var grid: GridView
    private lateinit var imgFull: ImageView
    private lateinit var btnSlideshow: Button
    private val handler = Handler(Looper.getMainLooper())
    private var slideIndex = 0
    private var sliding = false

    private val slideRunnable = object : Runnable {
        override fun run() {
            if (!sliding || paths.isEmpty()) return
            showFull(paths[slideIndex % paths.size])
            slideIndex++
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_album)
        grid = findViewById(R.id.gridAlbum)
        imgFull = findViewById(R.id.imgFull)
        btnSlideshow = findViewById(R.id.btnSlideshow)

        loadImages()

        grid.adapter = object : BaseAdapter() {
            override fun getCount() = paths.size
            override fun getItem(i: Int) = paths[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convertView: View?, parent: ViewGroup?): View {
                val iv = ImageView(this@AlbumActivity)
                iv.layoutParams = ViewGroup.LayoutParams(300, 300)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                val bmp = decodeSampled(paths[i], 300, 300)
                if (bmp != null) iv.setImageBitmap(bmp)
                iv.setOnClickListener { showFull(paths[i]) }
                return iv
            }
        }

        btnSlideshow.setOnClickListener {
            if (sliding) stopSlideshow() else startSlideshow()
        }
        imgFull.setOnClickListener { imgFull.visibility = View.GONE }
    }

    private fun loadImages() {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.Images.Media.DATA)
        contentResolver.query(uri, proj, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val p = c.getString(0)
                if (p != null) paths.add(p)
            }
        }
    }

    private fun decodeSampled(path: String, reqW: Int, reqH: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            opts.inSampleSize = calculateInSampleSize(opts, reqW, reqH)
            opts.inJustDecodeBounds = false
            BitmapFactory.decodeFile(path, opts)
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(opts: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        val h = opts.outHeight
        val w = opts.outWidth
        var inSample = 1
        if (h > reqH || w > reqW) {
            val halfH = h / 2
            val halfW = w / 2
            while (halfH / inSample >= reqH && halfW / inSample >= reqW) inSample *= 2
        }
        return inSample
    }

    private fun showFull(path: String) {
        val bmp = decodeSampled(path, 1024, 1024)
        if (bmp != null) {
            imgFull.setImageBitmap(bmp)
            imgFull.visibility = View.VISIBLE
        }
    }

    private fun startSlideshow() {
        sliding = true
        slideIndex = 0
        btnSlideshow.text = getString(R.string.album_stop)
        grid.visibility = View.GONE
        handler.post(slideRunnable)
    }

    private fun stopSlideshow() {
        sliding = false
        btnSlideshow.text = getString(R.string.album_slideshow)
        handler.removeCallbacks(slideRunnable)
        grid.visibility = View.VISIBLE
        imgFull.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(slideRunnable)
    }
}
