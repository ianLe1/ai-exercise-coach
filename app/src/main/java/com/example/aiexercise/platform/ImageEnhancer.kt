package com.example.aiexercise.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import com.example.aiexercise.domain.AppError
import com.example.aiexercise.domain.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 本地图片矫正 + 增强（相册多选 / 无 Play 服务时的回退）。
 *
 * 做三件事：按 EXIF 旋转摆正、限制长边尺寸（省内存）、提升对比度让字迹更清楚。
 * 透视矫正由 [DocumentScanEngine] 负责；这里不做，所以页面会原样保留。
 */
class ImageEnhancer(private val context: Context) {

    private val scanDir: File
        get() = File(context.filesDir, "scan_pages").apply { mkdirs() }

    /** 从相册 Uri 读入 → 矫正增强 → 写入 App 目录，返回文件路径。 */
    suspend fun enhance(uri: Uri, prefix: String = "page"): Outcome<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = decodeScaled(uri)
                ?: throw IllegalStateException("无法解码图片")
            val upright = applyExifRotation(uri, bitmap)
            val enhanced = boostContrast(upright)
            enhanced.saveToFile(File(scanDir, "${prefix}_${System.currentTimeMillis()}.jpg"))
        }.fold(
            onSuccess = { Outcome.Success(it) },
            onFailure = { Outcome.Failure(AppError.Unexpected("图片增强失败：${it.message}")) },
        )
    }

    /** 文档扫描器返回的页图已经是矫正+增强过的，这里只做转存（保留原图质量）。 */
    suspend fun saveScannedPage(uri: Uri, prefix: String = "scan"): Outcome<String> = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(scanDir, "${prefix}_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法读取扫描页")
            target.absolutePath
        }.fold(
            onSuccess = { Outcome.Success(it) },
            onFailure = { Outcome.Failure(AppError.Unexpected("保存扫描页失败：${it.message}")) },
        )
    }

    private fun decodeScaled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_SIDE * 2 || bounds.outHeight / sample > MAX_SIDE * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun boostContrast(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val contrast = 1.25f
        val translate = (-0.5f * contrast + 0.5f) * 255f + 12f
        val matrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        Canvas(output).drawBitmap(source, 0f, 0f, paint)
        return output
    }

    private fun Bitmap.saveToFile(file: File): String {
        FileOutputStream(file).use { output -> compress(Bitmap.CompressFormat.JPEG, 88, output) }
        return file.absolutePath
    }

    private companion object {
        const val MAX_SIDE = 2200
    }
}
