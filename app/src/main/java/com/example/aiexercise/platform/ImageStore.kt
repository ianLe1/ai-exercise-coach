package com.example.aiexercise.platform

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 扫描图片的本地缓存管理：
 * - 相机拍照写 cacheDir/scan/camera_*.jpg，并通过 FileProvider 授权给相机应用；
 * - 相册选图复制一份到缓存，保证后续能稳定预览（content:// 授权可能随后失效）。
 */
class ImageStore(private val context: Context) {

    fun newCameraFile(): File {
        val directory = File(context.cacheDir, "scan").apply { mkdirs() }
        return File(directory, "camera_${System.currentTimeMillis()}.jpg")
    }

    fun fileProviderUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun copyFromUri(uri: Uri): File {
        val directory = File(context.cacheDir, "scan").apply { mkdirs() }
        val target = File(directory, "gallery_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取所选图片")
        return target
    }
}
