package com.example.aiexercise.ai

import com.example.aiexercise.domain.AppError
import com.example.aiexercise.failureError
import com.example.aiexercise.successValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModlensPayloadTest {

    @Test
    fun encodeRequestCarriesImageAndPrompt() {
        val body = ModlensPayload.encodeRequest(base64Image = "QUJD", mime = "image/png", prompt = "读题")

        assertTrue(body.contains("\"image_base64\":\"QUJD\""))
        assertTrue(body.contains("\"mime\":\"image/png\""))
        assertTrue(body.contains("\"prompt\":\"读题\""))
    }

    @Test
    fun parseResponseReturnsRecognizedText() {
        val text = ModlensPayload.parseResponse(
            """{"text":"已知函数 f(x)=x²-4x+3，求最小值。","provider":"gemini-api"}""",
        ).successValue()

        assertEquals("已知函数 f(x)=x²-4x+3，求最小值。", text)
    }

    @Test
    fun bridgeErrorFieldBecomesReadableError() {
        val error = ModlensPayload.parseResponse(
            """{"error":"modlens 未配置视觉 provider"}""",
        ).failureError()

        assertTrue(error is AppError.AiNetworkError)
        assertTrue(error.userMessage.contains("未配置视觉 provider"))
    }

    @Test
    fun tooShortTextIsUnrecognized() {
        val error = ModlensPayload.parseResponse("""{"text":"a"}""").failureError()
        assertTrue(error is AppError.UnrecognizedInput)
    }

    @Test
    fun invalidJsonIsFormatError() {
        val error = ModlensPayload.parseResponse("not-json").failureError()
        assertTrue(error is AppError.AiFormatError)
    }
}
