package com.example.aiexercise.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import com.example.aiexercise.domain.AppError
import com.example.aiexercise.domain.Outcome
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ML Kit 文档扫描器（可替换点）：
 * 拍照多页 → 自动透视矫正 + 增强 + 分页（也可从相册导入多页）。
 *
 * 能力来自 `play-services-mlkit-document-scanner`；设备没有 Google Play 服务时会失败，
 * 此时 UI 回退到「相册多选 + 本地增强」（[ImageEnhancer]），仍支持多页与跨页分割。
 */
class DocumentScanEngine(private val context: Context) {

    /** 是否有可用的 ML Kit 文档扫描器（华为等无 Google 服务设备为 false）。 */
    val isAvailable: Boolean
        get() = runCatching {
            com.google.android.gms.common.GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == com.google.android.gms.common.ConnectionResult.SUCCESS
        }.getOrDefault(false)

    /** 发起一次多页扫描（结果通过 ActivityResult 返回，用 [parseResult] 解析）。 */
    suspend fun startIntent(activity: Activity, pageLimit: Int = 20): Outcome<IntentSender> {
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setPageLimit(pageLimit.coerceIn(1, 50))
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .build()
        val scanner = GmsDocumentScanning.getClient(options)
        return suspendCancellableCoroutine { continuation ->
            scanner.getStartScanIntent(activity)
                .addOnSuccessListener { intentSender ->
                    if (continuation.isActive) continuation.resume(Outcome.Success(intentSender))
                }
                .addOnFailureListener { throwable ->
                    if (continuation.isActive) {
                        continuation.resume(
                            Outcome.Failure(
                                AppError.UnrecognizedInput(
                                    "文档扫描器不可用（${throwable.message ?: "未安装 Google Play 服务"}），" +
                                        "请改用「相册多选」或手动输入。",
                                ),
                            ),
                        )
                    }
                }
        }
    }

    /** 解析文档扫描器返回的 ActivityResult. */
    fun parseResult(data: Intent?): Outcome<List<Uri>> {
        val result = data?.let { GmsDocumentScanningResult.fromActivityResultIntent(it) }
            ?: return Outcome.Failure(
                AppError.UnrecognizedInput("文档扫描没有返回结果，请重试或改用相册多选/手动输入。"),
            )
        val pages = result.pages.orEmpty().mapNotNull { it.imageUri }
        if (pages.isEmpty()) {
            return Outcome.Failure(AppError.UnrecognizedInput("文档扫描没有返回任何页面，请重新扫描。"))
        }
        return Outcome.Success(pages)
    }
}
