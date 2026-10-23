package com.example.aiexercise.domain

import com.example.aiexercise.data.model.KbCitation

/**
 * 教材 / 教师用书知识库（可替换点）。
 *
 * 当前版本只定义接口与空实现：用户暂不提供教材数据（“暂时不接知识库”）。
 * 将来接入时，实现 [search]（可用本地 JSONL + 关键词召回，或向量库 / 检索服务），
 * 并把结果填进 [com.example.aiexercise.data.model.ResearchBundle.kbCitations] 即可，
 * 流水线（ResearchAssistant）与 UI 已按该结构展示“教材依据”。
 */
data class KnowledgeQuery(
    val questionText: String,
    val referenceAnswer: String = "",
    val subjectHint: String = "",
    val solutionHint: String = "",
    val limit: Int = 4,
)

interface KnowledgeBase {
    val name: String

    /** 当前已加载的文档数；0 表示未接入。 */
    val documentCount: Int

    suspend fun search(query: KnowledgeQuery): List<KbCitation>
}

/** 占位实现：不返回任何依据（知识库数据待接入）。 */
object EmptyKnowledgeBase : KnowledgeBase {

    override val name: String = "教材知识库（未接入）"

    override val documentCount: Int = 0

    override suspend fun search(query: KnowledgeQuery): List<KbCitation> = emptyList()
}
