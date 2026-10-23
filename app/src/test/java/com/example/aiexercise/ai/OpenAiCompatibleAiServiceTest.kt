package com.example.aiexercise.ai

import com.example.aiexercise.domain.AppError
import com.example.aiexercise.failureError
import com.example.aiexercise.successValue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleAiServiceTest {

    private class FakeTransport(private val responses: List<HttpResult>) : HttpTransport {
        var callCount = 0
            private set

        override suspend fun postJson(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutMs: Int,
        ): HttpResult {
            callCount += 1
            return responses[minOf(callCount - 1, responses.lastIndex)]
        }

        override suspend fun getJson(
            url: String,
            headers: Map<String, String>,
            timeoutMs: Int,
        ): HttpResult {
            callCount += 1
            return responses[minOf(callCount - 1, responses.lastIndex)]
        }
    }

    private fun newService(transport: HttpTransport): OpenAiCompatibleAiService = OpenAiCompatibleAiService(
        config = AiRemoteConfig(
            baseUrl = "https://example.com/v1",
            apiKey = "test-key",
            model = "test-model",
        ),
        transport = transport,
        maxRetries = 1,
    )

    private fun chatResponse(content: String): String {
        val quoted = Json.encodeToString(String.serializer(), content)
        return """{"choices":[{"message":{"role":"assistant","content":$quoted}}]}"""
    }

    private val validKnowledgePoints = """
        {"analysis":"题目考查二次函数","subject":"数学","knowledgePoints":[
          {"title":"二次函数最值","aspect":"二次函数","subject":"数学","detail":"比较顶点与端点","reason":"作答偏差"}
        ]}
    """.trimIndent()

    @Test
    fun parsesKnowledgePointsFromChatCompletion() = runBlocking {
        val transport = FakeTransport(listOf(HttpResult(200, chatResponse(validKnowledgePoints))))
        val payload = newService(transport)
            .analyze(AnalyzeRequest("求 f(x)=x²-4x+3 在 [0,3] 上的最值。", "最大 3，最小 -1", "最大 0"))
            .successValue()

        assertEquals("数学", payload.subject)
        assertEquals(1, payload.knowledgePoints.size)
        assertEquals("二次函数最值", payload.knowledgePoints.first().title)
        assertEquals(1, transport.callCount)
    }

    @Test
    fun parsesContentWrappedInFencesAndProse() = runBlocking {
        val noisy = "好的，结果如下：\n```json\n$validKnowledgePoints\n```\n请查收。"
        val transport = FakeTransport(listOf(HttpResult(200, chatResponse(noisy))))
        val payload = newService(transport)
            .analyze(AnalyzeRequest("求最值。", "1", "2"))
            .successValue()

        assertEquals(1, payload.knowledgePoints.size)
    }

    @Test
    fun malformedOutputIsRetriedOnceThenFailsReadably() = runBlocking {
        val transport = FakeTransport(
            listOf(
                HttpResult(200, chatResponse("抱歉，我无法按格式回答。")),
                HttpResult(200, chatResponse("还是没有 JSON")),
            ),
        )
        val error = newService(transport)
            .analyze(AnalyzeRequest("求最值。", "1", "2"))
            .failureError()

        assertTrue(error is AppError.AiFormatError)
        assertEquals(2, transport.callCount)
        assertTrue(error.userMessage.contains("格式不合法"))
    }

    @Test
    fun clientErrorIsNotRetried() = runBlocking {
        val transport = FakeTransport(
            listOf(HttpResult(401, """{"error":{"message":"invalid api key"}}""")),
        )
        val error = newService(transport)
            .analyze(AnalyzeRequest("求最值。", "1", "2"))
            .failureError()

        assertTrue(error is AppError.AiNetworkError)
        assertEquals(1, transport.callCount)
        assertTrue(error.userMessage.contains("invalid api key"))
    }

    @Test
    fun missingApiKeyIsReportedBeforeNetworkCall() = runBlocking {
        val transport = FakeTransport(listOf(HttpResult(200, chatResponse(validKnowledgePoints))))
        val service = OpenAiCompatibleAiService(
            config = AiRemoteConfig(baseUrl = "https://example.com/v1", apiKey = "", model = "m"),
            transport = transport,
        )
        val error = service.analyze(AnalyzeRequest("求最值。", "1", "2")).failureError()

        assertTrue(error is AppError.AiNotConfigured)
        assertEquals(0, transport.callCount)
    }
}
