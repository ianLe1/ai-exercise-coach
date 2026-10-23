package com.example.aiexercise.platform

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.aiexercise.ai.HttpTransport
import com.example.aiexercise.ai.ModlensPayload
import com.example.aiexercise.ai.UrlConnectionHttpTransport
import com.example.aiexercise.ai.VisionSettings
import com.example.aiexercise.domain.AppError
import com.example.aiexercise.domain.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * modlens 视觉引擎：把图片发给本机运行的 modlens 桥（tools/modlens-bridge/server.mjs），
 * 由 modlens（多模态模型）读题并返回文字，再交给后续知识点分析流程。
 *
 * 桥接协议见 [ModlensPayload]：POST {bridgeUrl}/analyze。
 */
class ModlensVisionEngine(
    private val context: Context,
    private val bridgeUrl: String,
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val timeoutMs: Int = 20_000,
) : VisionEngine {

    override val displayName: String = "modlens 视觉桥"

    override val isOffline: Boolean = false

    override suspend fun recognize(uri: Uri): Outcome<String> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext Outcome.Failure(AppError.UnrecognizedInput("无法读取所选的图片文件"))
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val body = ModlensPayload.encodeRequest(
                base64Image = Base64.encodeToString(bytes, Base64.NO_WRAP),
                mime = mime,
            )
            val endpoint = bridgeUrl.trim().trimEnd('/').ifBlank { VisionSettings.DEFAULT_MODLENS_URL } + "/analyze"
            Log.d(TAG, "POST $endpoint（图片 ${bytes.size} 字节，请求体 ${body.length} 字符）")
            val result = transport.postJson(
                url = endpoint,
                headers = mapOf("Content-Type" to "application/json"),
                body = body,
                timeoutMs = timeoutMs,
            )
            Log.d(TAG, "HTTP ${result.statusCode}，耗时 ${System.currentTimeMillis() - startedAt}ms，响应 ${result.body.length} 字符")
            if (result.statusCode !in 200..299) {
                Outcome.Failure(
                    AppError.AiNetworkError(
                        "modlens 桥接服务返回 HTTP ${result.statusCode}：${errorMessage(result.body)}" +
                            "（可在设置中切回 ML Kit，或检查 modlens 的视觉 provider 配置）",
                    ),
                )
            } else {
                ModlensPayload.parseResponse(result.body)
            }
        } catch (exception: Exception) {
            Log.w(TAG, "调用 modlens 桥失败（耗时 ${System.currentTimeMillis() - startedAt}ms）", exception)
            Outcome.Failure(
                AppError.AiNetworkError(
                    "无法连接 modlens 桥（$bridgeUrl）：${exception.javaClass.simpleName}: ${exception.message ?: "无详情"}。" +
                        "请确认已运行 tools/modlens-bridge，或在设置中切回 ML Kit。",
                ),
            )
        }
    }

    private fun errorMessage(raw: String): String {
        val match = Regex("\"(?:error|message)\"\\s*:\\s*\"([^\"]{1,200})\"").find(raw)
        return match?.groupValues?.get(1) ?: raw.take(120).ifBlank { "无响应体" }
    }

    private companion object {
        const val TAG = "ModlensVisionEngine"
    }
}
