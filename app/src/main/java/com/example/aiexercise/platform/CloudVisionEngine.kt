package com.example.aiexercise.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.example.aiexercise.ai.HttpTransport
import com.example.aiexercise.ai.UrlConnectionHttpTransport
import com.example.aiexercise.ai.VisionSettings
import com.example.aiexercise.domain.AppError
import com.example.aiexercise.domain.LayoutFormatter
import com.example.aiexercise.domain.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream

/**
 * 内置多模态视觉（无需本机桥）：把题目照片直接发给 OpenAI 兼容的多模态接口，
 * 要求模型按 Markdown 输出，保留题号、选项、表格与公式等排版信息。
 *
 * 返回 [VisionDocument]：markdown 用于阅读与 AI 分类，plainText 用于按题号切分。
 */
class CloudVisionEngine(
    private val context: Context,
    private val settings: VisionSettings,
    private val aiSettings: com.example.aiexercise.ai.AiSettings =
        com.example.aiexercise.ai.AiSettings(),
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val timeoutMs: Int = 60_000,
) : VisionEngine {

    override val displayName: String = "内置多模态视觉（Markdown）"

    override val isOffline: Boolean = false

    override suspend fun recognize(uri: Uri): Outcome<VisionDocument> = withContext(Dispatchers.IO) {
        val endpointBase = settings.cloudEndpoint.trim().ifBlank { aiSettings.baseUrl }
        val apiKey = settings.cloudApiKey.trim().ifBlank { aiSettings.apiKey }
        val modelName = settings.cloudModel.trim().ifBlank { aiSettings.model.ifBlank { "gpt-4o-mini" } }
        if (apiKey.isBlank()) {
            return@withContext Outcome.Failure(
                AppError.AiNotConfigured("内置视觉未配置 API Key，请在「设置 → 视觉识别」填写多模态接口与 Key。"),
            )
        }
        try {
            val (base64, mime) = readImage(uri)
            val body = buildJsonObject {
                put("model", JsonPrimitive(modelName))
                put("temperature", JsonPrimitive(0.1))
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(PROMPT))
                            })
                            add(buildJsonObject {
                                put("type", JsonPrimitive("image_url"))
                                put(
                                    "image_url",
                                    buildJsonObject { put("url", JsonPrimitive("data:$mime;base64,$base64")) },
                                )
                            })
                        })
                    })
                })
            }.toString()
            val endpoint = endpointBase.trimEnd('/').ifBlank { "https://api.openai.com/v1" } + "/chat/completions"
            val result = transport.postJson(
                url = endpoint,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer $apiKey",
                ),
                body = body,
                timeoutMs = timeoutMs,
            )
            if (result.statusCode !in 200..299) {
                return@withContext Outcome.Failure(
                    AppError.AiNetworkError("内置视觉接口返回 HTTP ${result.statusCode}：${result.body.take(160)}"),
                )
            }
            val content = Json.parseToJsonElement(result.body)
                .jsonObject["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: return@withContext Outcome.Failure(AppError.AiFormatError("内置视觉返回缺少 choices[0].message.content"))
            val markdown = stripFence(content.trim())
            if (markdown.length < MIN_LENGTH) {
                return@withContext Outcome.Failure(AppError.UnrecognizedInput("视觉结果过短（\"$markdown\"），可能是图片模糊或没有文字"))
            }
            Outcome.Success(
                VisionDocument(
                    plainText = LayoutFormatter.toPlainText(markdown),
                    markdown = if (LayoutFormatter.looksLikeMarkdown(markdown)) markdown else LayoutFormatter.toMarkdown(markdown),
                ),
            )
        } catch (exception: Exception) {
            Outcome.Failure(
                AppError.AiNetworkError("调用内置视觉失败：${exception.javaClass.simpleName}: ${exception.message ?: "无详情"}"),
            )
        }
    }

    private fun readImage(uri: Uri): Pair<String, String> {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法读取所选的图片文件")
        if (raw.size <= MAX_BYTES) {
            return Base64.encodeToString(raw, Base64.NO_WRAP) to (context.contentResolver.getType(uri) ?: "image/jpeg")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        var scale = 1
        while (bounds.outWidth / scale > MAX_EDGE || bounds.outHeight / scale > MAX_EDGE) scale *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            raw, 0, raw.size,
            BitmapFactory.Options().apply { inSampleSize = scale },
        ) ?: throw IllegalStateException("图片解码失败")
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) to "image/jpeg"
    }

    private fun stripFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed.removePrefix("```markdown").removePrefix("```").removeSuffix("```").trim()
    }

    private companion object {
        const val MIN_LENGTH = 4
        const val MAX_BYTES = 3_500_000
        const val MAX_EDGE = 1600
        const val PROMPT =
            "把这张题目图片转成 Markdown，用于后续做题与讲解：\n" +
                "1. 保留题号（如 1.）、大题标题、选择题选项（A/B/C/D）与小题编号；\n" +
                "2. 表格用 Markdown 表格，公式用 LaTeX（行内 $...$，独立 $$...$$）；\n" +
                "3. 不要补写题目没有的内容，不要解题、不要加粗大段文字；\n" +
                "4. 只输出 Markdown 正文，不要代码围栏。"
    }
}
