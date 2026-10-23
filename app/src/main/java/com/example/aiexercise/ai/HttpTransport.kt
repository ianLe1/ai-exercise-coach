package com.example.aiexercise.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class HttpResult(val statusCode: Int, val body: String)

/**
 * HTTP 传输抽象（可替换点：OkHttp / Ktor / 各家 SDK 均可替换本接口的实现）。
 * 单测注入假的 transport，无需真实网络。
 */
interface HttpTransport {
    suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Int,
    ): HttpResult

    /** GET 请求（必应搜索等只读接口使用）。 */
    suspend fun getJson(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int,
    ): HttpResult
}

/** 默认实现：JDK HttpURLConnection，零额外依赖。 */
class UrlConnectionHttpTransport : HttpTransport {

    override suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Int,
    ): HttpResult = withContext(Dispatchers.IO) {
        request(method = "POST", url = url, headers = headers, body = body, timeoutMs = timeoutMs)
    }

    override suspend fun getJson(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int,
    ): HttpResult = withContext(Dispatchers.IO) {
        request(method = "GET", url = url, headers = headers, body = null, timeoutMs = timeoutMs)
    }

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Int,
    ): HttpResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) {
                doOutput = true
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return HttpResult(statusCode = status, body = responseBody)
        } finally {
            connection.disconnect()
        }
    }
}
