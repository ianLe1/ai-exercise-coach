package com.example.aiexercise.platform

import android.content.Context
import android.net.Uri
import com.example.aiexercise.ai.VisionMode
import com.example.aiexercise.ai.VisionSettings
import com.example.aiexercise.domain.AppError
import com.example.aiexercise.domain.Outcome
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 习题扫描的“读图 → 文本”能力抽象（可替换点）。
 *
 * 两次可替换实现：
 * 1. [MlKitVisionEngine]：Google ML Kit 中文文字识别，端侧离线（默认）；
 * 2. [ModlensVisionEngine]：调用 modlens 视觉桥（tools/modlens-bridge），由 modlens 的多模态模型读图。
 * 新增云端 OCR（百度/腾讯/阿里）时，只要实现本接口即可，上层无需改动。
 */
interface VisionEngine {
    /** 展示给用户的引擎名称。 */
    val displayName: String

    /** 是否离线可用。 */
    val isOffline: Boolean

    /** 返回结构化结果：plainText 用于按题号切分，markdown 保留题号/选项/段落等排版。 */
    suspend fun recognize(uri: Uri): Outcome<VisionDocument>
}

/** 一次识别的两种表示：原始文本（切题用）与排版后的 Markdown（阅读/给 AI 用）。 */
data class VisionDocument(
    val plainText: String,
    val markdown: String,
)

/** 默认实现：ML Kit 中文文字识别（设备端离线模型，图片不出本机）。 */
class MlKitVisionEngine(private val context: Context) : VisionEngine {

    override val displayName: String = "ML Kit 中文 OCR（离线）"

    override val isOffline: Boolean = true

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override suspend fun recognize(uri: Uri): Outcome<VisionDocument> = withContext(Dispatchers.IO) {
        try {
            val image = InputImage.fromFilePath(context, uri)
            val text = suspendCancellableCoroutine<String> { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { result -> continuation.resume(result.text) }
                    .addOnFailureListener { error -> continuation.resumeWithException(error) }
            }
            val trimmed = text.trim()
            if (trimmed.length < MIN_MEANINGFUL_LENGTH) {
                Outcome.Failure(
                    AppError.UnrecognizedInput("OCR 结果过短（\"$trimmed\"），可能是图片模糊或没有文字"),
                )
            } else {
                Outcome.Success(VisionDocument(plainText = trimmed, markdown = com.example.aiexercise.domain.LayoutFormatter.toMarkdown(trimmed)))
            }
        } catch (exception: Exception) {
            Outcome.Failure(
                AppError.UnrecognizedInput("OCR 处理失败：${exception.message ?: exception.javaClass.simpleName}"),
            )
        }
    }

    private companion object {
        const val MIN_MEANINGFUL_LENGTH = 4
    }
}

/** 视觉引擎工厂：按设置装配。 */
object VisionEngineFactory {
    fun create(context: Context, settings: VisionSettings): VisionEngine = when (settings.mode) {
        VisionMode.MLKIT -> MlKitVisionEngine(context)
        VisionMode.MODLENS -> ModlensVisionEngine(context, settings.modlensUrl)
        VisionMode.CLOUD -> CloudVisionEngine(context, settings)
    }
}
