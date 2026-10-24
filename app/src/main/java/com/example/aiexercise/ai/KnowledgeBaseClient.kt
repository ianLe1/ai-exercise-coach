package com.example.aiexercise.ai

import com.example.aiexercise.data.model.KbCitation
import com.example.aiexercise.domain.EmptyKnowledgeBase
import com.example.aiexercise.domain.KnowledgeBase
import com.example.aiexercise.domain.KnowledgeQuery
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * 教材 / 教师用书知识库客户端（可替换点）。
 *
 * 数据侧是 `ChinaTextbook-KB`（国家中小学智慧教育平台教材，SQLite FTS5 + 向量混合检索）：
 * 它自带一个 FastAPI 服务，`GET /api/search?q=&limit=` 返回带书目的原文片段。
 * 这里只做协议适配：把检索命中映射成 [KbCitation]，交给
 * [com.example.aiexercise.domain.ResearchAssistant] 作为 AI 分类前的“教材依据”。
 *
 * 本机默认端点 `http://127.0.0.1:8737`：
 * - 模拟器需先 `adb reverse tcp:8737 tcp:8737`，再用 127.0.0.1；
 * - 真机可填局域网地址（如 `http://192.168.1.10:8737`）。
 *
 * 服务未启动时 search() 会抛异常，流水线捕获后把 kbState 标成 failed 并给出可读提示，
 * 不会阻断知识点分类。
 */
class HttpKnowledgeBase(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val timeoutMs: Int = 10_000,
) : KnowledgeBase {

    override val name: String = "教材知识库（ChinaTextbook · smartedu）"

    /** 表示“已接入”；真实书目数由服务端 /api/health 提供，这里用平台教材数做默认展示。 */
    override val documentCount: Int = DEFAULT_BOOK_COUNT

    override suspend fun search(query: KnowledgeQuery): List<KbCitation> {
        val keyword = buildKeyword(query)
        if (keyword.isBlank()) return emptyList()
        val limit = query.limit.coerceIn(1, MAX_LIMIT)
        val url = "${endpoint.trimEnd('/')}/api/search" +
            "?q=${URLEncoder.encode(keyword, Charsets.UTF_8.name())}&limit=$limit"
        val response = transport.getJson(url, mapOf("Accept" to "application/json"), timeoutMs)
        if (response.statusCode !in 200..299) {
            throw IllegalStateException(
                "教材知识库返回 HTTP ${response.statusCode}（请确认本机已运行 ChinaTextbook-KB 服务）",
            )
        }
        val payload = runCatching { JSON.decodeFromString<KbSearchResponse>(response.body) }.getOrNull()
            ?: throw IllegalStateException("教材知识库返回的内容无法解析")
        return payload.results.mapNotNull { it.toCitation() }
    }

    /** 查询词：题目正文优先，其次参考答案/学科提示；去掉换行与多余空白。 */
    private fun buildKeyword(query: KnowledgeQuery): String {
        val candidates = listOf(query.questionText, query.referenceAnswer, query.solutionHint)
        val base = candidates.firstOrNull { it.isNotBlank() }.orEmpty()
        val subject = query.subjectHint.trim()
        val text = base.replace(Regex("\\s+"), " ").trim().take(MAX_QUERY_LENGTH)
        return if (subject.isNotEmpty() && !text.contains(subject)) "$subject $text" else text
    }

    private fun KbHit.toCitation(): KbCitation? {
        val bookTitle = book?.title.orEmpty()
        if (text.isBlank() && bookTitle.isBlank()) return null
        val cleanSnippet = text.replace(Regex("\\s+"), " ").trim().take(MAX_SNIPPET_LENGTH)
        return KbCitation(
            documentId = bookId.ifBlank { book?.id.orEmpty() },
            title = bookTitle,
            stage = book?.level.orEmpty(),
            subject = book?.subject.orEmpty(),
            edition = book?.edition.orEmpty(),
            volume = book?.volume.orEmpty(),
            chapter = book?.grade.orEmpty(),
            page = if (page > 0) "第 $page 页" else "",
            snippet = cleanSnippet,
            // 服务端 bm25 分通常 5～40，归一到 0..1 便于 UI 展示
            score = (bm25Score / BM25_NORMALIZER).coerceIn(0.0, 1.0),
        )
    }

    companion object {
        const val DEFAULT_ENDPOINT: String = "http://127.0.0.1:8737"
        private const val DEFAULT_BOOK_COUNT = 2205
        private const val MAX_LIMIT = 8
        private const val MAX_QUERY_LENGTH = 120
        private const val MAX_SNIPPET_LENGTH = 220
        private const val BM25_NORMALIZER = 50.0

        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }
    }
}

/** ChinaTextbook-KB `/api/search` 响应（只声明用到的字段）。 */
@Serializable
private data class KbSearchResponse(
    val results: List<KbHit> = emptyList(),
)

@Serializable
private data class KbHit(
    @SerialName("chunk_id") val chunkId: Long = 0,
    @SerialName("book_id") val bookId: String = "",
    val page: Int = 0,
    val text: String = "",
    val score: Double = 0.0,
    @SerialName("bm25_score") val bm25Score: Double = 0.0,
    val book: KbBook? = null,
)

@Serializable
private data class KbBook(
    val id: String = "",
    val title: String = "",
    @SerialName("display_name") val displayName: String = "",
    val level: String = "",
    val subject: String = "",
    val edition: String = "",
    val grade: String = "",
    val volume: String = "",
)

/** 按设置装配知识库：关闭时用空实现（流水线会把 kbState 标成 skipped 并提示）。 */
object KnowledgeBaseFactory {
    fun create(settings: KnowledgeSettings): KnowledgeBase =
        if (settings.enabled) HttpKnowledgeBase(settings.endpoint) else EmptyKnowledgeBase
}
