package com.example.aiexercise.ai

import com.example.aiexercise.domain.AppError
import com.example.aiexercise.domain.Outcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * modlens 视觉桥的请求/响应协议（独立出来便于 JVM 单测）。
 *
 * 请求：POST {bridgeUrl}/analyze
 *   {"image_base64":"...","mime":"image/jpeg","prompt":"..."}
 * 成功响应：
 *   {"text":"识别出的题目文字","provider":"gemini-api","raw":{...}}
 * 失败响应：
 *   {"error":"modlens 未配置视觉 provider"}
 */
object ModlensPayload {

    const val DEFAULT_PROMPT = "读取图片中的习题内容（题目与手写/印刷答案），按原文顺序输出纯文本，保留公式与数字。"

    @Serializable
    private data class AnalyzeRequest(
        val image_base64: String,
        val mime: String = "image/jpeg",
        val prompt: String = DEFAULT_PROMPT,
    )

    @Serializable
    private data class AnalyzeResponse(
        val text: String? = null,
        val error: String? = null,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun encodeRequest(base64Image: String, mime: String, prompt: String = DEFAULT_PROMPT): String {
        val request = AnalyzeRequest(
            image_base64 = base64Image,
            mime = mime.ifBlank { "image/jpeg" },
            prompt = prompt,
        )
        return json.encodeToString(AnalyzeRequest.serializer(), request)
    }

    /** 解析桥接服务响应；空文本 / 错误字段都转成可读错误。 */
    fun parseResponse(raw: String): Outcome<String> {
        val response = try {
            json.decodeFromString(AnalyzeResponse.serializer(), raw)
        } catch (exception: Exception) {
            return Outcome.Failure(
                AppError.AiFormatError("modlens 桥接服务返回格式不合法：${exception.message ?: exception.javaClass.simpleName}"),
            )
        }
        val error = response.error?.trim()
        if (!error.isNullOrEmpty()) {
            return Outcome.Failure(AppError.AiNetworkError("modlens 识别失败：$error"))
        }
        val text = response.text?.trim().orEmpty()
        if (text.length < MIN_TEXT_LENGTH) {
            return Outcome.Failure(
                AppError.UnrecognizedInput("modlens 没有识别出有效文字（结果过短），请换个角度重拍或改用手动输入"),
            )
        }
        return Outcome.Success(text)
    }

    private const val MIN_TEXT_LENGTH = 4
}
