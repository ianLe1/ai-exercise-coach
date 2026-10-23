package com.example.aiexercise.ai

import com.example.aiexercise.domain.AppError
import com.example.aiexercise.failureError
import com.example.aiexercise.successValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BingSearchServiceTest {

    private class FakeTransport(private val response: HttpResult) : HttpTransport {
        var callCount = 0
            private set
        var lastUrl: String? = null
            private set
        var lastHeaders: Map<String, String> = emptyMap()
            private set
        var lastBody: String? = null
            private set

        override suspend fun postJson(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutMs: Int,
        ): HttpResult {
            callCount += 1
            lastUrl = url
            lastHeaders = headers
            lastBody = body
            return response
        }

        override suspend fun getJson(
            url: String,
            headers: Map<String, String>,
            timeoutMs: Int,
        ): HttpResult {
            callCount += 1
            lastUrl = url
            lastHeaders = headers
            return response
        }
    }

    private val bingJson = """
        {"webPages":{"value":[
          {"name":"二次函数闭区间最值解法","url":"https://example.com/a","snippet":"比较顶点与端点函数值…"},
          {"name":"同类题：f(x)=x²-4x+3","url":"https://example.com/b","snippet":"配方后讨论对称轴…"},
          {"name":"","url":"","snippet":"无效条目"}
        ]}}
    """.trimIndent()

    private fun service(transport: HttpTransport, apiKey: String = "test-bing-key") =
        BingSearchService(endpoint = "https://example.com/v7.0/search", apiKey = apiKey, transport = transport)

    @Test
    fun parsesBingWebPagesIntoHits() = runBlocking {
        val transport = FakeTransport(HttpResult(200, bingJson))
        val hits = service(transport).search("二次函数 最值", count = 3).successValue()

        assertEquals(2, hits.size)
        assertEquals("二次函数闭区间最值解法", hits.first().title)
        assertEquals("https://example.com/a", hits.first().url)
        assertTrue(hits.first().snippet.contains("顶点"))
    }

    @Test
    fun buildsQueryWithEncodingCountAndApiKeyHeader() = runBlocking {
        val transport = FakeTransport(HttpResult(200, bingJson))
        service(transport).search("二次函数 最值", count = 3)

        val url = transport.lastUrl.orEmpty()
        assertTrue(url.startsWith("https://example.com/v7.0/search?"))
        assertTrue(url.contains("q=%E4%BA%8C%E6%AC%A1%E5%87%BD%E6%95%B0+%E6%9C%80%E5%80%BC"))
        assertTrue(url.contains("count=3"))
        assertEquals("test-bing-key", transport.lastHeaders["Ocp-Apim-Subscription-Key"])
    }

    @Test
    fun emptyWebPagesReturnsEmptyList() = runBlocking {
        val transport = FakeTransport(HttpResult(200, """{"webPages":{"value":[]}}"""))
        assertTrue(service(transport).search("没有结果").successValue().isEmpty())
    }

    @Test
    fun missingApiKeyFailsBeforeNetwork() = runBlocking {
        val transport = FakeTransport(HttpResult(200, bingJson))
        val error = service(transport, apiKey = "").search("二次函数").failureError()

        assertTrue(error is AppError.AiNotConfigured)
        assertTrue(error.userMessage.contains("API Key"))
        assertEquals(0, transport.callCount)
    }

    @Test
    fun unauthorizedFailureIsReadable() = runBlocking {
        val transport = FakeTransport(HttpResult(401, """{"error":{"message":"invalid subscription key"}}"""))
        val error = service(transport).search("二次函数").failureError()

        assertTrue(error is AppError.AiNetworkError)
        assertTrue(error.userMessage.contains("401"))
        assertTrue(error.userMessage.contains("API Key"))
    }

    @Test
    fun malformedBodyIsReportedAsFormatError() = runBlocking {
        val transport = FakeTransport(HttpResult(200, "not-a-json"))
        val error = service(transport).search("二次函数").failureError()
        assertTrue(error is AppError.AiFormatError)
    }
}
