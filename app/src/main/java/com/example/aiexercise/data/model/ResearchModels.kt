package com.example.aiexercise.data.model

import kotlinx.serialization.Serializable

/**
 * 自动检索流水线的数据结构（新需求：AI 分类前先联网搜题解析 + 查教材知识库）。
 *
 * 流程：题目 → 网络搜索命中 → AI 过滤出「本题解析」→ 检索教材/教师用书知识库 → 一起作为
 * 知识点分类的上下文（[ResearchBundle] 也会随习题保存，便于回溯“当时依据了什么”）。
 */

/** 一条网络搜索命中（必应 / 其它 SearchService 的统一结构）。 */
@Serializable
data class WebHit(
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
)

/**
 * AI 从网络搜索结果中筛选出的「本题解析」。
 *
 * - relevant=false 表示 AI 认为这些结果与本题无关（此时不把内容喂给知识点分析）；
 * - solutionMarkdown：筛选后的解析正文（Markdown）；
 * - matchedTitles：被 AI 采纳的来源标题，用于在 UI 中展示“依据”。
 */
@Serializable
data class SolutionDigest(
    val relevant: Boolean = false,
    val solutionMarkdown: String = "",
    val confidence: Double = 0.0,
    val matchedTitles: List<String> = emptyList(),
)

/** 教材 / 教师用书知识库中的一条命中。 */
@Serializable
data class KbCitation(
    val documentId: String = "",
    val title: String = "",
    val stage: String = "",
    val subject: String = "",
    val edition: String = "",
    val volume: String = "",
    val chapter: String = "",
    val page: String = "",
    val snippet: String = "",
    val score: Double = 0.0,
) {
    /** 形如「高中 · 数学 · 人教版 · 必修一 · 第二章 · 第 32 页」。 */
    fun citationLabel(): String = listOf(stage, subject, edition, volume, chapter, page)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

/**
 * 一次「自动资料检索」的完整结果。
 *
 * webState / kbState 是给 UI 与测试用的状态机（避免把失败当成功展示）。
 */
@Serializable
data class ResearchBundle(
    /** 实际发给搜索服务的查询词。 */
    val query: String = "",
    val webHits: List<WebHit> = emptyList(),
    val digest: SolutionDigest = SolutionDigest(),
    val kbCitations: List<KbCitation> = emptyList(),
    val searchedAt: Long = 0L,
    val webState: String = WEB_SKIPPED,
    val kbState: String = KB_SKIPPED,
    /** 降级/跳过原因（可读中文），不影响知识点分类继续执行。 */
    val warning: String = "",
    /** 知识库当前文档数（便于 UI 提示“知识库为空”）。 */
    val kbDocuments: Int = 0,
) {
    val hasAnyEvidence: Boolean
        get() = digest.relevant || kbCitations.isNotEmpty()

    companion object {
        const val WEB_OK = "ok"
        const val WEB_SKIPPED = "skipped"
        const val WEB_FAILED = "failed"
        const val WEB_NO_MATCH = "no_match"

        const val KB_OK = "ok"
        const val KB_EMPTY = "empty"
        const val KB_SKIPPED = "skipped"
        const val KB_FAILED = "failed"
    }
}
