package com.example.aiexercise.data.model

import kotlinx.serialization.Serializable

/**
 * 一次「练习卷答题卡」扫描批改结果（模块三：完成练习 → 扫描 → 批改 → 列出不会的知识点）。
 *
 * [practiceReviews] 按 paperId 累积，同一份练习卷多次扫描即为多轮「订正」（round 递增），
 * 用于判断订正质量、以及是否仍有题目错误。
 */
@Serializable
data class PracticeReview(
    val id: String = "",
    val paperId: String = "",
    /** 第几轮：1=首次批改，2+=订正后再次扫描。 */
    val round: Int = 1,
    val createdAt: Long = 0L,
    val totalQuestions: Int = 0,
    val gradedQuestions: Int = 0,
    val score: Int = 0,
    val passed: Boolean = false,
    /** 本次仍答错的题（含订正质量评估）。 */
    val wrong: List<PracticeWrong> = emptyList(),
    /** 本次涉及、且仍未掌握的不会知识点 id（按紧迫度排序由 UI 再排）。 */
    val relatedKnowledgePointIds: List<String> = emptyList(),
    /** 有题目“订正质量极低”，此时对该题的引导式提问会被拒绝。 */
    val hasBlockedQuestions: Boolean = false,
    val note: String = "",
)

/** 练习卷中仍然答错的一道题。 */
@Serializable
data class PracticeWrong(
    val questionId: String = "",
    val questionTitle: String = "",
    /** 题干原文（引导式提问的上下文）。 */
    val questionText: String = "",
    val studentAnswer: String = "",
    val correctAnswer: String = "",
    val score: Int = 0,
    /** 订正质量：NONE / LOW / MEDIUM / HIGH（见 domain/ReviewGuards.kt）。 */
    val correctionQuality: String = "NONE",
    /** 是否在上一轮就答错过（决定“订正质量极低则拒绝回答”是否生效）。 */
    val previousRoundWrong: Boolean = false,
    val knowledgePointId: String = "",
    val knowledgePointTitle: String = "",
    val explanationMarkdown: String = "",
    /** 引导式提问是否可发起（false = 订正质量太低，先重做）。 */
    val mayAskGuided: Boolean = true,
)

/** 引导式提问的一条消息。 */
@Serializable
data class GuidedExchange(
    val role: String = "tutor",
    val text: String = "",
)

/**
 * 针对某道错题的引导式提问会话（AI 只给引导，不直接给答案）。
 * 会话中收集的信息在 resolved=true 时并入「不会的知识点列表」。
 */
@Serializable
data class GuidedSession(
    val id: String = "",
    val exerciseId: String = "",
    val paperId: String = "",
    val questionId: String = "",
    val questionTitle: String = "",
    /** 题干原文（引导式提问的上下文）。 */
    val questionText: String = "",
    val knowledgePointId: String = "",
    val knowledgePointTitle: String = "",
    val stage: Int = 0,
    val exchanges: List<GuidedExchange> = emptyList(),
    val resolved: Boolean = false,
    val refused: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)
