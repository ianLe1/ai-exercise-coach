package com.example.aiexercise.domain

const val DAY_MS: Long = 24L * 60L * 60L * 1000L

/**
 * 全局业务参数（可替换点）。
 *
 * 所有魔法数字集中在这里：遗忘曲线参数、判重阈值、及格线、练习卷上限。
 * 调整这些值不会影响流程代码，单元测试按默认值编写。
 */
data class ToolConfig(
    val forgetting: ForgettingCurveConfig = ForgettingCurveConfig(),
    /** 两道题文本相似度达到该值即视为“同一习题” */
    val similarityThreshold: Double = 0.85,
    /** 批改及格线 */
    val passScore: Int = 60,
    /** 判重命中后，关联知识点掌握度上限（再次遇到说明仍然不会） */
    val duplicateMasteryCap: Double = 0.35,
    /** 批改不及格时，提前复习窗口 */
    val failedGradeAccelerateMs: Long = DAY_MS,
    /** 生成练习卷时，单卷最多题量 */
    val maxQuestionsPerPaper: Int = 20,
    /** 生成练习卷默认题量 */
    val defaultPaperCount: Int = 20,
    /** 单卷最多考察几个知识点 */
    val maxKnowledgePointsPerPaper: Int = 6,
    /** 本地最多保留多少份历史练习卷 */
    val maxStoredPapers: Int = 20,
)
