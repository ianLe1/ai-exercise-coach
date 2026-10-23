package com.example.aiexercise.ai

import com.example.aiexercise.domain.AppError
import com.example.aiexercise.domain.Outcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * 联网搜索服务（可替换点）。
 *
 * 默认实现：Azure 必应 Web Search API v7
 *   GET {endpoint}?q=...&count=...&mkt=zh-CN
 *   Header: Ocp-Apim-Subscription-Key
 * 换成 Google/Brave/SearXNG 时只需另写一个 [SearchService] 实现。
 */
interface SearchService {
    val displayName: String
    suspend fun search(query: String, count: Int = 5): Outcome<List<SearchHit>>
}

/** 必应返回 JSON 的容错解析（独立出来便于 JVM 单测）。 */
object BingPayload {

    @Serializable
    private data class BingResponse(val webPages: BingWebPages? = null)

    @Serializable
    private data class BingWebPages(val value: List<BingPage> = emptyList())

    @Serializable
    private data class BingPage(
        val name: String = "",
        val url: String = "",
        val snippet: String = "",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun parse(raw: String): Outcome<List<SearchHit>> = try {
        val response = json.decodeFromString(BingResponse.serializer(), raw)
        val hits = response.webPages?.value.orEmpty()
            .filter { it.url.isNotBlank() }
            .map { SearchHit(title = it.name.ifBlank { it.url }, url = it.url, snippet = it.snippet) }
        Outcome.Success(hits)
    } catch (exception: Exception) {
        Outcome.Failure(
            AppError.AiFormatError("必应搜索返回格式不合法：${exception.message ?: exception.javaClass.simpleName}"),
        )
    }

    /** 从错误响应里尽量提取可读信息（Bing 错误格式：{"error":{"message":...}}）。 */
    fun errorMessage(raw: String): String {
        val match = Regex("\"message\"\\s*:\\s*\"([^\"]{1,200})\"").find(raw)
        return match?.groupValues?.get(1) ?: raw.take(120).ifBlank { "无响应体" }
    }
}

class BingSearchService(
    private val endpoint: String,
    private val apiKey: String,
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val timeoutMs: Int = 20_000,
) : SearchService {

    override val displayName: String = "必应搜索（Bing Web Search API）"

    override suspend fun search(query: String, count: Int): Outcome<List<SearchHit>> {
        val keyword = query.trim()
        if (keyword.isEmpty()) return Outcome.Failure(AppError.UnrecognizedInput("搜索关键词为空"))
        if (apiKey.isBlank()) {
            return Outcome.Failure(
                AppError.AiNotConfigured("必应搜索未配置 API Key，请到「设置 → 联网搜索」填写后再试。"),
            )
        }
        val safeCount = count.coerceIn(1, 10)
        val url = buildUrl(keyword, safeCount)
        return try {
            val result = transport.getJson(
                url = url,
                headers = mapOf(
                    "Ocp-Apim-Subscription-Key" to apiKey,
                    "Accept" to "application/json",
                ),
                timeoutMs = timeoutMs,
            )
            when {
                result.statusCode in 200..299 -> BingPayload.parse(result.body)
                result.statusCode == 401 || result.statusCode == 403 -> Outcome.Failure(
                    AppError.AiNetworkError(
                        "必应搜索鉴权失败（HTTP ${result.statusCode}）：请检查 API Key 与端点区域是否匹配。",
                    ),
                )
                else -> Outcome.Failure(
                    AppError.AiNetworkError(
                        "必应搜索失败（HTTP ${result.statusCode}）：${BingPayload.errorMessage(result.body)}",
                    ),
                )
            }
        } catch (exception: Exception) {
            Outcome.Failure(
                AppError.AiNetworkError(
                    "连接必应搜索失败：${exception.message ?: exception.javaClass.simpleName}（请检查网络或端点地址）",
                ),
            )
        }
    }

    private fun buildUrl(keyword: String, count: Int): String {
        val separator = if (endpoint.contains('?')) '&' else '?'
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        return "$endpoint${separator}q=$encoded&count=$count&mkt=zh-CN&responseFilter=Webpages"
    }
}

/** 搜索服务工厂：根据设置构建实例（OFF → null，表示不启用搜索）。 */
object SearchServiceFactory {
    fun create(settings: SearchSettings): SearchService? = when (settings.mode) {
        SearchMode.OFF -> null
        SearchMode.BING -> BingSearchService(endpoint = settings.endpoint, apiKey = settings.apiKey)
    }
}
