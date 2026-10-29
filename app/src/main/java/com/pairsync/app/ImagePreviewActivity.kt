package com.pairsync.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView

class ImagePreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        setContentView(R.layout.image_preview)
        val iv = findViewById<ImageView>(R.id.ivPreview)
        val path = intent.getStringExtra("path") ?: ""
        val f = java.io.File(path)
        if (f.exists()) {
            val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bo)
            var s = 1
            while (bo.outWidth / s > 1440 || bo.outHeight / s > 2160) s *= 2
            val o = BitmapFactory.Options().apply { inSampleSize = s }
            val bmp: Bitmap? = BitmapFactory.decodeFile(f.path, o)
            if (bmp != null) iv.setImageBitmap(bmp)
        }
        iv.setOnClickListener { finish() }
    }
}
