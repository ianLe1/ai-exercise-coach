package com.example.aiexercise.domain

import com.example.aiexercise.ai.AiService
import com.example.aiexercise.ai.SearchService
import com.example.aiexercise.ai.SolutionFilterRequest
import com.example.aiexercise.data.model.KbCitation
import com.example.aiexercise.data.model.ResearchBundle
import com.example.aiexercise.data.model.SolutionDigest
import com.example.aiexercise.data.model.WebHit

/**
 * 自动资料检索（新需求）：AI 做知识点分类前，先把“这道题的解析”找出来。
 *
 * 固定流水线（每一步失败都降级，不阻断分类）：
 * 1. 网络搜索：用题目正文拼查询词，调 [SearchService]（必应或其它实现）；
 * 2. AI 过滤：把命中交给 [AiService.distillSolution]，只保留真正讲解本题的解析；
 * 3. 知识库：用题目 + 解析检索教材/教师用书（当前 [KnowledgeBase] 为空实现，待数据接入）。
 *
 * 返回值 [ResearchBundle] 会随习题持久化（`Exercise.lastResearch`），
 * UI 以「折叠的证据卡」展示，主流程始终围绕“不会的知识点列表”。
 */
interface ResearchSource {
    suspend fun research(
        questionText: String,
        referenceAnswer: String = "",
        studentAnswer: String = "",
        subjectHint: String = "",
        onStage: (String) -> Unit = {},
    ): ResearchBundle
}

class ResearchAssistant(
    private val search: SearchService?,
    private val knowledgeBase: KnowledgeBase?,
    private val ai: AiService,
    private val clock: () -> Long = System::currentTimeMillis,
    private val searchCount: Int = 5,
    private val maxQueryLength: Int = 80,
) : ResearchSource {

    override suspend fun research(
        questionText: String,
        referenceAnswer: String,
        studentAnswer: String,
        subjectHint: String,
        onStage: (String) -> Unit,
    ): ResearchBundle {
        val query = buildQuery(questionText)
        val warnings = mutableListOf<String>()

        /* 1) 联网搜索 */
        var webHits: List<WebHit> = emptyList()
        var webState = ResearchBundle.WEB_SKIPPED
        if (search == null) {
            warnings += "未启用联网搜索（设置 → 联网搜索），本次未检索网络解析"
        } else {
            onStage("联网搜索本题解析…")
            when (val result = search.search(query, searchCount)) {
                is Outcome.Success -> {
                    webHits = result.value
                        .filter { it.url.isNotBlank() || it.snippet.isNotBlank() }
                        .map { WebHit(title = it.title, url = it.url, snippet = it.snippet) }
                    if (webHits.isEmpty()) warnings += "网络搜索没有返回结果"
                }

                is Outcome.Failure -> {
                    webState = ResearchBundle.WEB_FAILED
                    warnings += "网络搜索失败：${result.error.userMessage}"
                }
            }
        }

        /* 2) AI 过滤出“本题解析” */
        var digest = SolutionDigest()
        if (webHits.isNotEmpty()) {
            onStage("AI 正在过滤搜索结果，提取本题解析…")
            when (
                val result = ai.distillSolution(
                    SolutionFilterRequest(
                        questionText = questionText,
                        referenceAnswer = referenceAnswer,
                        studentAnswer = studentAnswer,
                        webHits = webHits,
                    ),
                )
            ) {
                is Outcome.Success -> {
                    digest = result.value
                    webState = if (digest.relevant) ResearchBundle.WEB_OK else ResearchBundle.WEB_NO_MATCH
                    if (!digest.relevant) warnings += "AI 未从搜索结果中筛出与本题匹配的解析"
                }

                is Outcome.Failure -> {
                    webState = ResearchBundle.WEB_FAILED
                    warnings += "AI 过滤网络解析失败：${result.error.userMessage}"
                }
            }
        }

        /* 3) 教材 / 教师用书知识库 */
        var citations: List<KbCitation> = emptyList()
        var kbState = ResearchBundle.KB_SKIPPED
        val kb = knowledgeBase
        val kbDocuments = kb?.documentCount ?: 0
        if (kb == null || kbDocuments == 0) {
            warnings += "教材知识库暂未接入（等待教材/教师用书数据）"
        } else {
            onStage("检索教材 / 教师用书知识库…")
            val queryForKb = KnowledgeQuery(
                questionText = questionText,
                referenceAnswer = referenceAnswer,
                subjectHint = subjectHint,
                solutionHint = digest.solutionMarkdown,
            )
            runCatching { kb.search(queryForKb) }
                .onSuccess { hits ->
                    citations = hits
                    kbState = if (hits.isEmpty()) ResearchBundle.KB_EMPTY else ResearchBundle.KB_OK
                    if (hits.isEmpty()) warnings += "知识库中没有检索到相关教材依据"
                }
                .onFailure { error ->
                    kbState = ResearchBundle.KB_FAILED
                    warnings += "知识库检索失败：${error.message ?: error.javaClass.simpleName}"
                }
        }

        return ResearchBundle(
            query = query,
            webHits = webHits,
            digest = digest,
            kbCitations = citations,
            searchedAt = clock(),
            webState = webState,
            kbState = kbState,
            warning = warnings.joinToString("；"),
            kbDocuments = kbDocuments,
        )
    }

    /** 查询词 = 题目正文（压缩空白、截断）+ “解析 答案”。 */
    private fun buildQuery(questionText: String): String {
        val compact = questionText.replace(Regex("\\s+"), " ").trim()
        val clipped = if (compact.length <= maxQueryLength) compact else compact.take(maxQueryLength)
        return "$clipped 解析 答案"
    }
}
