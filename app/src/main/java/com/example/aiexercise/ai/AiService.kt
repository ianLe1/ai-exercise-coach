package com.example.aiexercise.ai

import com.example.aiexercise.data.model.AskerProfile
import com.example.aiexercise.data.model.GuidedExchange
import com.example.aiexercise.data.model.KnowledgePoint
import com.example.aiexercise.data.model.PaperQuestion
import com.example.aiexercise.data.model.ResearchBundle
import com.example.aiexercise.data.model.SolutionDigest
import com.example.aiexercise.data.model.WebHit
import com.example.aiexercise.domain.AppError
import com.example.aiexercise.domain.Outcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/* ------------------------------------------------------------------ */
/* AI 输入 / 输出契约（JSON）                                           */
/* ------------------------------------------------------------------ */

/** AI 产出的一条“不会的知识点”草稿。 */
@Serializable
data class KpDraft(
    val title: String = "",
    val aspect: String = "",
    val subject: String = "",
    val detail: String = "",
    val reason: String = "",
)

@Serializable
data class KpGenerationPayload(
    val analysis: String = "",
    val subject: String = "",
    val knowledgePoints: List<KpDraft> = emptyList(),
)

@Serializable
data class GradePayload(
    val score: Int = 0,
    val correct: Boolean = false,
    val correctAnswer: String = "",
    val analysis: String = "",
    val errorSteps: List<String> = emptyList(),
    val relatedKnowledgePoints: List<String> = emptyList(),
)

@Serializable
data class PaperPayload(
    val title: String = "",
    val aspect: String = "",
    val summary: String = "",
    val questions: List<PaperQuestion> = emptyList(),
    /** 答题卡结构（规范 JSON：大题 → 题 → 可选小题；数字/数组/字符串）。 */
    val answerSheet: JsonElement? = null,
)

/** 多页扫描分割：一页页 OCR 文本 → 一道道题。 */
@Serializable
data class SegmentRequest(
    val pagesText: String,
    /** 逐页 OCR 文本（优先于 pagesText；跨页合并由分割器处理）。 */
    val pages: List<String> = emptyList(),
    val pageCount: Int = 0,
    val subjectHint: String = "",
    val asker: AskerProfile = AskerProfile(),
)

@Serializable
data class SegmentDraft(
    val sectionTitle: String = "",
    val questionText: String = "",
    val pageFrom: Int = 1,
    val pageTo: Int = 1,
    val referenceAnswer: String = "",
    val studentAnswer: String = "",
)

@Serializable
data class SegmentPayload(
    val questions: List<SegmentDraft> = emptyList(),
    val note: String = "",
)

@Serializable
data class AnalyzeRequest(
    val questionText: String,
    val referenceAnswer: String = "",
    val studentAnswer: String = "",
    val subjectHint: String = "",
    val asker: AskerProfile = AskerProfile(),
    /**
     * 自动检索结果（网络解析 + 教材知识库）。
     * 由 [com.example.aiexercise.domain.ResearchAssistant] 在 AI 分类前生成，
     * 作为知识点分类的额外依据；为空表示本次没有检索上下文。
     */
    val research: ResearchBundle? = null,
)

/** 「AI 从网络搜索结果中筛出本题解析」的请求。 */
@Serializable
data class SolutionFilterRequest(
    val questionText: String,
    val referenceAnswer: String = "",
    val studentAnswer: String = "",
    val webHits: List<WebHit> = emptyList(),
)

@Serializable
data class GradeRequest(
    val questionText: String,
    val referenceAnswer: String,
    val studentAnswer: String,
    val asker: AskerProfile = AskerProfile(),
)

@Serializable
data class PaperRequest(
    val aspect: String,
    val count: Int,
    val asker: AskerProfile,
    val knowledgePoints: List<KnowledgePoint> = emptyList(),
)

/* ------------------------------------------------------------------ */
/* AI 服务抽象（可替换点：离线规则引擎 / OpenAI 兼容接口 / 任意云厂商）      */
/* ------------------------------------------------------------------ */

interface AiService {
    /** 稳定标识，用于持久化与测试。 */
    val id: String

    /** 展示给用户的名称，例如「离线规则引擎」「OpenAI 兼容接口（gpt-4o-mini）」。 */
    val displayName: String

    val isOffline: Boolean

    suspend fun analyze(request: AnalyzeRequest): Outcome<KpGenerationPayload>

    /**
     * 从网络搜索命中里筛选出「本题解析」（新流程的第二步）：
     * 不是简单拼接搜索结果，而是由 AI 判断相关性、只保留与题目/答案一致的解析。
     */
    suspend fun distillSolution(request: SolutionFilterRequest): Outcome<SolutionDigest>

    /**
     * 多页扫描的题目分割：识别大题与题号，跨页的题合并成一条（pageFrom != pageTo）。
     * 离线实现用 [com.example.aiexercise.domain.QuestionSegmenter]，远程实现走提示词。
     */
    suspend fun segmentQuestions(request: SegmentRequest): Outcome<SegmentPayload>

    /** 提取练习卷答题卡里学生的作答（练习后扫描批改）。 */
    suspend fun readAnswers(request: AnswerReadRequest): Outcome<AnswerReadPayload>

    /** 引导式提问的一步：只提问与提示，不给直接答案。 */
    suspend fun guidedStep(request: GuidedRequest): Outcome<GuidedPayload>

    suspend fun grade(request: GradeRequest): Outcome<GradePayload>

    suspend fun generatePaper(request: PaperRequest): Outcome<PaperPayload>
}


/* ------------------------------------------------------------------ */
/* 练习批改与引导式提问（模块三）                                        */
/* ------------------------------------------------------------------ */

/** 扫描「练习卷答题卡」并提取学生的作答。 */
@Serializable
data class AnswerReadRequest(
    val pagesText: List<String> = emptyList(),
    val paperQuestions: List<PaperQuestion> = emptyList(),
    val answerSheetJson: String = "",
)

/** 提取出的一条作答：index 对应练习卷第几题（1 起）。 */
@Serializable
data class AnswerDraft(
    val index: Int = 0,
    val questionHint: String = "",
    val answerText: String = "",
)

@Serializable
data class AnswerReadPayload(
    val answers: List<AnswerDraft> = emptyList(),
    val note: String = "",
)

/** 引导式提问请求：只给上下文与历史，要求 AI 不给直接答案。 */
@Serializable
data class GuidedRequest(
    val questionText: String = "",
    val questionTitle: String = "",
    val referenceAnswer: String = "",
    val studentAnswer: String = "",
    val knowledgePointTitle: String = "",
    val stage: Int = 1,
    val exchanges: List<GuidedExchange> = emptyList(),
)

@Serializable
data class GuidedPayload(
    val question: String = "",
    val feedback: String = "",
    val resolved: Boolean = false,
    val stage: Int = 0,
)

/**
 * AI 结果合法性校验：格式不合法 → 可读错误。
 * 校验失败时上层会重试一次（仅远程实现），仍失败则返回 [AppError.AiFormatError]。
 */
object AiPayloadValidator {

    fun validateKnowledgePoints(payload: KpGenerationPayload): Outcome<KpGenerationPayload> {
        val cleaned = payload.knowledgePoints
            .filter { it.title.isNotBlank() }
            .map {
                it.copy(
                    title = it.title.trim().take(40),
                    aspect = it.aspect.trim().ifBlank { payload.subject.trim().ifBlank { "综合" } },
                    subject = it.subject.trim().ifBlank { payload.subject.trim().ifBlank { "综合" } },
                    detail = it.detail.trim(),
                    reason = it.reason.trim(),
                )
            }
            .distinctBy { it.title.lowercase() + "|" + it.aspect.lowercase() }
        if (cleaned.isEmpty()) return Outcome.Failure(AppError.AiEmptyKnowledgePoints)
        return Outcome.Success(payload.copy(knowledgePoints = cleaned))
    }

    fun validateGrade(payload: GradePayload): Outcome<GradePayload> {        if (payload.analysis.isBlank() && payload.errorSteps.isEmpty() && payload.correctAnswer.isBlank()) {
            return Outcome.Failure(AppError.AiFormatError("批改结果缺少 analysis / errorSteps / correctAnswer"))
        }
        return Outcome.Success(payload.copy(score = payload.score.coerceIn(0, 100)))
    }

    /**
     * 校验「网络解析筛选」结果：
     * - relevant=true 却没有解析正文 → 视为格式不合法（远程实现会重试）；
     * - relevant=false 且正文为空 → 合法的“没筛出匹配解析”；
     * - confidence 归一到 0..1，来源标题去重限长。
     */
    fun validateSolutionDigest(payload: SolutionDigest): Outcome<SolutionDigest> {
        val solution = payload.solutionMarkdown.trim()
        if (payload.relevant && solution.isBlank()) {
            return Outcome.Failure(AppError.AiFormatError("解析筛选结果标记为相关，但 solutionMarkdown 为空"))
        }
        return Outcome.Success(
            payload.copy(
                solutionMarkdown = solution,
                confidence = payload.confidence.coerceIn(0.0, 1.0),
                matchedTitles = payload.matchedTitles
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(5),
            ),
        )
    }

    /**
     * 校验题目分割结果：
     * - 丢掉空题面；一题都没有 → 可读的“未识别”错误；
     * - 页号归一到 1..pageCount，pageTo 不小于 pageFrom；按起始页排序。
     */
    fun validateSegments(payload: SegmentPayload, pageCount: Int): Outcome<SegmentPayload> {
        fun clampPage(value: Int): Int {
            val atLeastOne = value.coerceAtLeast(1)
            return if (pageCount > 0) atLeastOne.coerceAtMost(pageCount) else atLeastOne
        }
        val cleaned = payload.questions
            .filter { it.questionText.isNotBlank() }
            .map {
                val from = clampPage(it.pageFrom)
                val to = clampPage(it.pageTo).coerceAtLeast(from)
                it.copy(
                    sectionTitle = it.sectionTitle.trim(),
                    questionText = it.questionText.trim(),
                    pageFrom = from,
                    pageTo = to,
                )
            }
            .sortedBy { it.pageFrom }
        if (cleaned.isEmpty()) {
            return Outcome.Failure(AppError.UnrecognizedInput("没有从扫描内容里分割出题目，请重拍或改用手动输入"))
        }
        return Outcome.Success(payload.copy(questions = cleaned))
    }


    /** 校验答题卡识别结果：题目序号归一到 0..questionCount，作答去空白。 */
    fun validateAnswers(payload: AnswerReadPayload, questionCount: Int): Outcome<AnswerReadPayload> {
        val cleaned = payload.answers
            .map {
                it.copy(
                    index = it.index.coerceIn(0, questionCount.coerceAtLeast(0)),
                    questionHint = it.questionHint.trim().take(60),
                    answerText = it.answerText.trim().take(600),
                )
            }
            .filter { it.answerText.isNotBlank() }
            .distinctBy { it.index }
        if (cleaned.isEmpty()) {
            return Outcome.Failure(
                AppError.UnrecognizedInput("答题卡没有识别出任何作答内容，请重拍清晰些，或改用云端 AI / modlens 识别手写体。"),
            )
        }
        return Outcome.Success(payload.copy(answers = cleaned, note = payload.note.trim().take(200)))
    }

    /** 校验引导式提问结果：必须有一句提问；stage 归一到 1..4。 */
    fun validateGuided(payload: GuidedPayload): Outcome<GuidedPayload> {
        val question = payload.question.trim()
        if (question.isEmpty()) {
            return Outcome.Failure(AppError.AiFormatError("引导式提问结果缺少 question 字段"))
        }
        return Outcome.Success(
            payload.copy(
                question = question.take(400),
                feedback = payload.feedback.trim().take(400),
                stage = payload.stage.coerceIn(1, 4),
            ),
        )
    }

    fun validatePaper(payload: PaperPayload): Outcome<PaperPayload> {
        val questions = payload.questions
            .filter { it.questionMarkdown.isNotBlank() && it.answerMarkdown.isNotBlank() }
            .mapIndexed { index, question ->
                question.copy(
                    id = question.id.ifBlank { "Q${index + 1}" },
                    questionMarkdown = question.questionMarkdown.trim(),
                    answerMarkdown = question.answerMarkdown.trim(),
                    explanationMarkdown = question.explanationMarkdown.trim(),
                    type = question.type.ifBlank { "solution" },
                    difficulty = question.difficulty.ifBlank { "medium" },
                )
            }
        if (questions.isEmpty()) {
            return Outcome.Failure(AppError.GenerationInvalid("questions 为空或题面/答案缺失"))
        }
        return Outcome.Success(payload.copy(questions = questions))
    }
}
