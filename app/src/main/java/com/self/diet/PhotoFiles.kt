package com.self.diet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID

object PhotoFiles {
    fun directory(context: Context) = File(context.filesDir, "photos").apply { mkdirs() }
    fun import(context: Context, uri: Uri): String {
        val temp = File.createTempFile("photo-", ".tmp", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(32768); var total = 0L
                    while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 50L * 1024 * 1024) { "照片超过 50 MB" }; output.write(buffer, 0, count) }
                }
            } ?: error("无法读取照片")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法识别图片格式" }
            val options = BitmapFactory.Options().apply { inSampleSize = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 4096) inSampleSize *= 2 }
            var bitmap = BitmapFactory.decodeFile(temp.path, options) ?: error("无法解码照片")
            val exif = ExifInterface(temp)
            val matrix = Matrix().apply { if(exif.isFlipped) postScale(-1f, 1f); postRotate(exif.rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) { bitmap.recycle(); bitmap = rotated }
            val scale = minOf(1f, 2048f / maxOf(bitmap.width, bitmap.height))
            if (scale < 1f) { val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true); bitmap.recycle(); bitmap = scaled }
            val name = UUID.randomUUID().toString() + ".jpg"
            val result = File(directory(context), name)
            try { result.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) { "照片保存失败" } } }
            catch (e: Exception) { result.delete(); throw e }
            finally { bitmap.recycle() }
            return name
        } finally { temp.delete() }
    }
}
