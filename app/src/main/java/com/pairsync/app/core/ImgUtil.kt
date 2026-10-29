package com.pairsync.app.core

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object ImgUtil {
    fun compressImage(bytes: ByteArray): Pair<ByteArray, String>? {
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bo)
        if (bo.outWidth <= 0 || bo.outHeight <= 0) return null
        var s = 1
        while (bo.outWidth / s > 1600 || bo.outHeight / s > 1600) s *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = s }
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) ?: return null
        val sc = minOf(1f, 1280f / maxOf(src.width, src.height))
        val bmp = if (sc < 1f) {
            Bitmap.createScaledBitmap(src, (src.width * sc).toInt().coerceAtLeast(1), (src.height * sc).toInt().coerceAtLeast(1), true)
        } else src
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        if (out.size() == 0) return null
        return out.toByteArray() to "image/jpeg"
    }

    fun save(ctx: Context, f: File, mime: String, name: String): String {
        val isImg = mime.startsWith("image/")
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, if (isImg) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS)
            val uri = if (isImg) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
            return try {
                val u = cr.insert(uri, values)
                if (u == null) {
                    "保存失败"
                } else {
                    cr.openOutputStream(u)?.use { it.write(f.readBytes()) }
                    if (isImg) "已保存到相册" else "已保存到下载"
                }
            } catch (e: Exception) {
                "保存失败: " + e.message
            }
        }
        return try {
            val dir = File(if (isImg) Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), if (isImg) "PairSync" else "")
            dir.mkdirs()
            val out = File(dir, name)
            f.copyTo(out, overwrite = true)
            android.media.MediaScannerConnection.scanFile(ctx, arrayOf(out.path), arrayOf(mime), null)
            if (isImg) "已保存到相册" else "已保存到下载"
        } catch (e: Exception) {
            "保存失败: " + e.message
        }
    }
}
