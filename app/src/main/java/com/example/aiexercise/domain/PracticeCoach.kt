package com.example.aiexercise.domain

import com.example.aiexercise.ai.AiService
import com.example.aiexercise.ai.AnswerReadRequest
import com.example.aiexercise.ai.GradeRequest
import com.example.aiexercise.ai.GuidedRequest
import com.example.aiexercise.data.model.AppData
import com.example.aiexercise.data.model.GuidedExchange
import com.example.aiexercise.data.model.GuidedSession
import com.example.aiexercise.data.model.KnowledgePoint
import com.example.aiexercise.data.model.PracticeReview
import com.example.aiexercise.data.model.PracticeWrong
import java.util.UUID

data class PracticeReviewOutcome(
    val data: AppData,
    val review: PracticeReview,
    val knowledgePoints: List<KnowledgePoint>,
)

data class GuidedOutcome(
    val data: AppData,
    val session: GuidedSession,
)

/**
 * 模块三：练习 → 批改 → 订正 → 引导式提问的领域服务。
 *
 * 1. [review]：扫描练习卷答题卡（多页 OCR 文本）→ AI 按题号提取作答 → 逐题批改 →
 *    列出仍不会的知识点；第 2 轮起评估订正质量。
 * 2. [openGuided] / [replyGuided]：对答错的题发起引导式提问（AI 只提问不点破）；
 *    会话收尾时把过程信息并入「不会的知识点列表」。
 * 3. [guardHomework] / [markHomeworkAsked]：作业与知识点列表大相径庭时的确认守卫。
 */
class PracticeCoach(
    private val ai: AiService,
    private val config: ToolConfig = ToolConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {

    /** 扫描批改一份练习卷的答题卡。 */
    suspend fun review(
        data: AppData,
        paperId: String,
        pagesText: List<String>,
    ): Outcome<PracticeReviewOutcome> {
        val paper = data.papers.firstOrNull { it.id == paperId }
            ?: return Outcome.Failure(AppError.PracticePaperMissing)
        if (paper.questions.isEmpty()) {
            return Outcome.Failure(AppError.GenerationInvalid("这份练习卷没有题目，无法批改"))
        }
        if (pagesText.none { it.isNotBlank() }) {
            return Outcome.Failure(AppError.UnrecognizedInput("请先扫描答题卡照片（可多页），再开始批改。"))
        }
        val read = when (
            val result = ai.readAnswers(
                AnswerReadRequest(
                    pagesText = pagesText,
                    paperQuestions = paper.questions,
                    answerSheetJson = paper.answerSheetJson,
                ),
            )
        ) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value
        }

        val now = clock()
        val previousReview = data.practiceReviews
            .filter { it.paperId == paperId }
            .maxByOrNull { it.round }
        val warnings = mutableListOf<String>()
        val wrongs = mutableListOf<PracticeWrong>()
        val relatedIds = mutableListOf<String>()
        var graded = 0
        var scoreSum = 0

        paper.questions.forEachIndexed { i, question ->
            val index = i + 1
            val answer = read.answers.firstOrNull { it.index == index }?.answerText
                ?: read.answers.getOrNull(i)?.takeIf { it.index == 0 }?.answerText
                ?: ""
            val grade = when (
                val result = ai.grade(
                    GradeRequest(
                        questionText = question.questionMarkdown,
                        referenceAnswer = question.answerMarkdown,
                        studentAnswer = answer,
                        asker = data.asker,
                    ),
                )
            ) {
                is Outcome.Failure -> {
                    warnings += "第 $index 题批改失败：${result.error.userMessage}"
                    null
                }

                is Outcome.Success -> result.value
            } ?: return@forEachIndexed

            graded += 1
            scoreSum += grade.score
            val passed = grade.score >= config.passScore || grade.correct
            val previousWrong = previousReview?.wrong?.firstOrNull { it.questionId == question.id }
            val check = if (previousWrong != null) {
                CorrectionGuard.check(answer, question.answerMarkdown.ifBlank { grade.correctAnswer })
            } else {
                null
            }
            if (question.knowledgePointId.isNotBlank()) relatedIds += question.knowledgePointId

            if (!passed) {
                wrongs += PracticeWrong(
                    questionId = question.id,
                    questionTitle = question.knowledgePointTitle.ifBlank { "第 $index 题" },
                    studentAnswer = answer,
                    correctAnswer = question.answerMarkdown.ifBlank { grade.correctAnswer },
                    score = grade.score,
                    correctionQuality = (check?.quality ?: CorrectionQuality.NONE).name,
                    previousRoundWrong = previousWrong != null,
                    knowledgePointId = question.knowledgePointId,
                    knowledgePointTitle = question.knowledgePointTitle,
                    explanationMarkdown = grade.analysis,
                    mayAskGuided = check?.mayAskGuided ?: true,
                )
            }
        }

        if (graded == 0) {
            return Outcome.Failure(
                AppError.UnrecognizedInput("答题卡没有批改出任何题目，请确认扫描的是这份练习卷的作答。"),
            )
        }

        val score = scoreSum / graded
        val round = (previousReview?.round ?: 0) + 1
        val review = PracticeReview(
            id = idGenerator(),
            paperId = paperId,
            round = round,
            createdAt = now,
            totalQuestions = paper.questions.size,
            gradedQuestions = graded,
            score = score,
            passed = wrongs.isEmpty() && score >= config.passScore,
            wrong = wrongs,
            relatedKnowledgePointIds = relatedIds.distinct(),
            hasBlockedQuestions = wrongs.any { it.previousRoundWrong && !it.mayAskGuided },
            note = buildString {
                append(if (round <= 1) "首次批改" else "第 $round 轮订正扫描")
                if (read.note.isNotBlank()) append("；${read.note}")
                if (warnings.isNotEmpty()) append("；${warnings.joinToString("；")}")
            }.take(300),
        )

        val updatedPoints = applyReviewToKnowledgePoints(data.knowledgePoints, wrongs, previousReview, now)
        val reviews = (data.practiceReviews + review).takeLast(MAX_REVIEWS)
        val newData = data.copy(practiceReviews = reviews, knowledgePoints = updatedPoints)
        val sorted = relatedIds.distinct()
            .mapNotNull { id -> updatedPoints.firstOrNull { it.id == id } }
            .sortedByDescending { ForgettingCurve.urgency(it.timer, it.mastery, now, config.forgetting) }
        return Outcome.Success(PracticeReviewOutcome(newData, review, sorted))
    }

    /** 对某道错题发起（或恢复）引导式提问。 */
    fun openGuided(data: AppData, reviewId: String, questionId: String): Outcome<GuidedOutcome> {
        val review = data.practiceReviews.firstOrNull { it.id == reviewId }
            ?: return Outcome.Failure(AppError.Unexpected("找不到这次批改记录，请重新扫描批改。"))
        val wrong = review.wrong.firstOrNull { it.questionId == questionId }
            ?: return Outcome.Failure(AppError.NoWrongQuestion)
        if (!wrong.mayAskGuided) return Outcome.Failure(AppError.CorrectionTooPoor)

        val now = clock()
        val existing = data.guidedSessions.firstOrNull {
            it.paperId == review.paperId && it.questionId == questionId && !it.resolved
        }
        if (existing != null) return Outcome.Success(GuidedOutcome(data, existing))

        val paper = data.papers.firstOrNull { it.id == review.paperId }
        val question = paper?.questions?.firstOrNull { it.id == questionId }
        val opening = LocalGuidedTutor.opening(
            questionText = question?.questionMarkdown.orEmpty(),
            kpTitle = wrong.knowledgePointTitle,
            wrongAnswer = wrong.studentAnswer,
        )
        val session = GuidedSession(
            id = idGenerator(),
            paperId = review.paperId,
            questionId = questionId,
            questionTitle = wrong.questionTitle,
            questionText = question?.questionMarkdown.orEmpty(),
            knowledgePointId = wrong.knowledgePointId,
            knowledgePointTitle = wrong.knowledgePointTitle,
            stage = 1,
            exchanges = listOf(GuidedExchange("tutor", opening)),
            createdAt = now,
            updatedAt = now,
        )
        val newData = data.copy(guidedSessions = (data.guidedSessions + session).takeLast(MAX_SESSIONS))
        return Outcome.Success(GuidedOutcome(newData, session))
    }

    /** 回答引导式提问：AI 给下一步引导；收尾时把过程并入不会的知识点列表。 */
    suspend fun replyGuided(data: AppData, sessionId: String, userText: String): Outcome<GuidedOutcome> {
        val session = data.guidedSessions.firstOrNull { it.id == sessionId }
            ?: return Outcome.Failure(AppError.Unexpected("找不到这次提问会话，请重新发起。"))
        if (session.resolved) return Outcome.Success(GuidedOutcome(data, session))
        val text = userText.trim()
        if (text.isEmpty()) return Outcome.Failure(AppError.UnrecognizedInput("先写一句你自己的想法，再发送。"))

        val now = clock()
        val withUser = session.copy(
            exchanges = session.exchanges + GuidedExchange("student", text),
            updatedAt = now,
        )
        val payload = when (
            val result = ai.guidedStep(
                GuidedRequest(
                    questionText = session.questionText,
                    questionTitle = session.questionTitle,
                    studentAnswer = text,
                    knowledgePointTitle = session.knowledgePointTitle,
                    stage = withUser.stage,
                    exchanges = withUser.exchanges,
                ),
            )
        ) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value
        }

        var updatedSession = withUser.copy(
            stage = payload.stage,
            resolved = payload.resolved,
            exchanges = withUser.exchanges + GuidedExchange(
                "tutor",
                listOf(payload.feedback, payload.question).filter { it.isNotBlank() }.joinToString("\n\n"),
            ),
            updatedAt = now,
        )
        var points = data.knowledgePoints
        if (payload.resolved) {
            points = mergeGuidedInsight(points, updatedSession, now)
            updatedSession = updatedSession.copy(knowledgePointId = findInsightPointId(points, updatedSession))
        }
        val newData = data.copy(
            knowledgePoints = points,
            guidedSessions = data.guidedSessions.map { if (it.id == session.id) updatedSession else it },
        )
        return Outcome.Success(GuidedOutcome(newData, updatedSession))
    }

    /** 作业与知识点列表一致性守卫（首次必问）。 */
    fun guardHomework(data: AppData, scannedQuestions: List<String>): HomeworkGuardResult =
        HomeworkGuard.decide(
            scannedQuestions = scannedQuestions,
            knownQuestions = data.exercises.map { it.questionText },
            askedOnce = data.homeworkAskedOnce,
        )

    /** 记录“已问过/已忽略”，避免每次都弹确认。 */
    fun markHomeworkAsked(data: AppData, ignored: Boolean): AppData = data.copy(
        homeworkAskedOnce = true,
        homeworkIgnoredCount = data.homeworkIgnoredCount + if (ignored) 1 else 0,
    )

    private fun applyReviewToKnowledgePoints(
        points: List<KnowledgePoint>,
        wrongs: List<PracticeWrong>,
        previousReview: PracticeReview?,
        now: Long,
    ): List<KnowledgePoint> {
        if (wrongs.isEmpty() && previousReview == null) return points
        val wrongByKp = wrongs.filter { it.knowledgePointId.isNotBlank() }.associateBy { it.knowledgePointId }
        val previousWrongKps = previousReview?.wrong.orEmpty().mapNotNull { it.knowledgePointId.ifBlank { null } }.toSet()
        return points.map { point ->
            val wrong = wrongByKp[point.id]
            when {
                wrong != null -> point.copy(
                    mastery = (point.mastery - MASTERY_WRONG_PENALTY).coerceAtLeast(MIN_MASTERY),
                    timer = point.timer.copy(
                        nextReviewAt = minOf(
                            point.timer.nextReviewAt.takeIf { it > 0L } ?: Long.MAX_VALUE,
                            now + config.failedGradeAccelerateMs,
                        ),
                    ),
                    updatedAt = now,
                )

                point.id in previousWrongKps -> {
                    val reviewed = ForgettingCurve.onReview(point.timer, PASS_QUALITY, now, config.forgetting)
                    point.copy(
                        mastery = (point.mastery + MASTERY_CORRECT_BONUS).coerceAtMost(1.0),
                        timer = reviewed,
                        updatedAt = now,
                    )
                }

                else -> point
            }
        }
    }

    private fun mergeGuidedInsight(points: List<KnowledgePoint>, session: GuidedSession, now: Long): List<KnowledgePoint> {
        val title = session.knowledgePointTitle.ifBlank { "订正薄弱点：${session.questionTitle.take(24)}" }
        val summary = session.exchanges
            .filter { it.role == "student" }
            .takeLast(3)
            .joinToString("；") { it.text.take(60) }
            .ifBlank { "通过引导式提问梳理了解题步骤。" }
        val existing = KnowledgePointRules.findExisting(points, title, ASPECT_GUIDED)
        return if (existing != null) {
            points.map {
                if (it.id != existing.id) {
                    it
                } else {
                    it.copy(
                        detail = KnowledgePointRules.mergeDetail(it.detail, "订正提问：$summary"),
                        mastery = (it.mastery + MASTERY_GUIDED_BONUS).coerceAtMost(1.0),
                        updatedAt = now,
                    )
                }
            }
        } else {
            points + KnowledgePoint(
                id = idGenerator(),
                title = title,
                aspect = ASPECT_GUIDED,
                subject = points.firstOrNull()?.subject ?: "综合",
                detail = "订正提问中梳理出的薄弱点：$summary",
                evidence = "来源：练习卷订正 + 引导式提问",
                mastery = 0.2,
                timer = ForgettingCurve.initialTimer(now, config.forgetting),
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    private fun KnowledgePoint.refreshedAtHint(now: Long): KnowledgePoint = copy(updatedAt = now)

    private fun findInsightPointId(points: List<KnowledgePoint>, session: GuidedSession): String {
        val key = session.knowledgePointTitle.ifBlank { "订正薄弱点：${session.questionTitle.take(24)}" }
        return KnowledgePointRules.findExisting(points, key, ASPECT_GUIDED)?.id.orEmpty()
    }

    companion object {
        const val MAX_REVIEWS = 60
        const val MAX_SESSIONS = 40
        const val ASPECT_GUIDED = "订正提问"
        private const val PASS_QUALITY = 4
        private const val MASTERY_WRONG_PENALTY = 0.05
        private const val MASTERY_CORRECT_BONUS = 0.05
        private const val MASTERY_GUIDED_BONUS = 0.03
        private const val MIN_MASTERY = 0.05
    }
}
