package com.example.aiexercise.domain

import com.example.aiexercise.ai.AiService
import com.example.aiexercise.ai.AnalyzeRequest
import com.example.aiexercise.ai.AnswerDraft
import com.example.aiexercise.ai.AnswerReadPayload
import com.example.aiexercise.ai.AnswerReadRequest
import com.example.aiexercise.ai.GradePayload
import com.example.aiexercise.ai.GradeRequest
import com.example.aiexercise.ai.GuidedPayload
import com.example.aiexercise.ai.GuidedRequest
import com.example.aiexercise.ai.KpGenerationPayload
import com.example.aiexercise.ai.PaperPayload
import com.example.aiexercise.ai.PaperRequest
import com.example.aiexercise.ai.SegmentPayload
import com.example.aiexercise.ai.SegmentRequest
import com.example.aiexercise.ai.SolveRequest
import com.example.aiexercise.ai.SolvePayload
import com.example.aiexercise.ai.SolutionFilterRequest
import com.example.aiexercise.data.model.AppData
import com.example.aiexercise.data.model.KnowledgePoint
import com.example.aiexercise.data.model.PaperKnowledgePointRef
import com.example.aiexercise.data.model.PaperQuestion
import com.example.aiexercise.data.model.PracticePaper
import com.example.aiexercise.data.model.PracticeReview
import com.example.aiexercise.data.model.PracticeWrong
import com.example.aiexercise.data.model.SolutionDigest
import com.example.aiexercise.data.model.ReviewTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模块三的验收流：完成练习 → 扫描答题卡 → 批改 → 仍不会的知识点，
 * 以及订正后再扫（轮次递增 + 订正质量特判 + 引导式提问并入知识点）。
 */
class PracticeCoachTest {

    private val now = 1_700_000_000_000L

    private val kp1 = KnowledgePoint(
        id = "kp1",
        title = "闭区间上的最值求法",
        aspect = "二次函数",
        detail = "比较顶点与两个端点",
        mastery = 0.5,
        timer = ReviewTimer(nextReviewAt = now + 86_400_000L),
    )
    private val kp2 = KnowledgePoint(
        id = "kp2",
        title = "一元二次方程的解法",
        aspect = "方程",
        detail = "因式分解 / 求根公式",
        mastery = 0.5,
        timer = ReviewTimer(nextReviewAt = now + 86_400_000L),
    )

    private val q1 = PaperQuestion(
        id = "q1",
        type = "application",
        difficulty = "medium",
        questionMarkdown = "求 f(x)=x²-4x+3 在 [0,3] 上的最大值与最小值。",
        answerMarkdown = "最大值 3，最小值 -1",
        knowledgePointId = "kp1",
        knowledgePointTitle = "闭区间上的最值求法",
    )
    private val q2 = PaperQuestion(
        id = "q2",
        type = "application",
        difficulty = "easy",
        questionMarkdown = "解方程 x²-5x+6=0。",
        answerMarkdown = "x=2 或 x=3",
        knowledgePointId = "kp2",
        knowledgePointTitle = "一元二次方程的解法",
    )
    private val paper = PracticePaper(
        id = "p1",
        aspect = "二次函数",
        title = "二次函数专项练习卷",
        knowledgePoints = listOf(
            PaperKnowledgePointRef(id = "kp1", title = kp1.title, aspect = kp1.aspect),
            PaperKnowledgePointRef(id = "kp2", title = kp2.title, aspect = kp2.aspect),
        ),
        questions = listOf(q1, q2),
    )

    private fun baseData() = AppData(
        knowledgePoints = listOf(kp1, kp2),
        papers = listOf(paper),
    )

    /** 按调用顺序返回预设批改结果，方便模拟「第 1 题对、第 2 题错」。 */
    private class FakeAi(
        var answers: List<AnswerDraft> = emptyList(),
        var grades: List<GradePayload> = emptyList(),
        var guided: GuidedPayload = GuidedPayload(),
    ) : AiService {
        override val id: String = "fake"
        override val displayName: String = "假 AI"
        override val isOffline: Boolean = true
        private var gradeIndex = 0

        override suspend fun analyze(request: AnalyzeRequest) = Outcome.Failure(AppError.Unexpected("未使用"))
        override suspend fun grade(request: GradeRequest): Outcome<GradePayload> {
            val payload = grades.getOrElse(gradeIndex) { GradePayload() }
            gradeIndex += 1
            return Outcome.Success(payload)
        }

        override suspend fun generatePaper(request: PaperRequest) =
            Outcome.Failure(AppError.Unexpected("未使用"))

        override suspend fun distillSolution(request: SolutionFilterRequest) =
            Outcome.Success(SolutionDigest())

        override suspend fun segmentQuestions(request: SegmentRequest) =
            Outcome.Failure(AppError.Unexpected("未使用"))

        override suspend fun readAnswers(request: AnswerReadRequest) =
            Outcome.Success(AnswerReadPayload(answers = answers, note = "假答题卡"))

        override suspend fun solve(request: SolveRequest): Outcome<SolvePayload> =
            Outcome.Success(SolvePayload(referenceAnswer = "参考要点：测试用参考答案"))

        override suspend fun guidedStep(request: GuidedRequest) = Outcome.Success(guided)
    }

    private fun pass() = GradePayload(score = 100, correct = true, correctAnswer = "最大值 3，最小值 -1", analysis = "很好")
    private fun fail(analysis: String = "漏掉端点") =
        GradePayload(score = 20, correct = false, correctAnswer = "x=2 或 x=3", analysis = analysis)

    @Test
    fun reviewGradesPaperAndListsStillUnknownKnowledgePoints() = kotlinx.coroutines.runBlocking {
        val ai = FakeAi(
            answers = listOf(AnswerDraft(1, "第1题", "最大值 3，最小值 -1"), AnswerDraft(2, "第2题", "0")),
            grades = listOf(pass(), fail()),
        )
        val coach = PracticeCoach(ai, clock = { now }, idGenerator = { "review-1" })
        val outcome = coach.review(baseData(), paperId = "p1", pagesText = listOf("答题卡文字"))
        assertTrue(outcome is Outcome.Success)
        val value = (outcome as Outcome.Success).value
        val review = value.review
        assertEquals(1, review.round)
        assertEquals(2, review.gradedQuestions)
        assertEquals(1, review.wrong.size)
        assertEquals("q2", review.wrong.first().questionId)
        assertTrue(review.relatedKnowledgePointIds.contains("kp2"))
        // 仍不会的知识点：kp2（mp期被提前、掌握度下调）
        val updatedKp2 = value.knowledgePoints.first { it.id == "kp2" }
        assertTrue(updatedKp2.mastery < 0.5)
        assertTrue(updatedKp2.timer.nextReviewAt <= now + 2L * 86_400_000L)
        assertEquals(1, value.data.practiceReviews.size)
        assertTrue(value.knowledgePoints.any { it.id == "kp2" })
    }

    @Test
    fun secondScanIsRoundTwoAndPoorCorrectionBlocksGuidedQuestion() = kotlinx.coroutines.runBlocking {
        val previous = PracticeReview(
            id = "r1",
            paperId = "p1",
            round = 1,
            totalQuestions = 2,
            gradedQuestions = 2,
            score = 60,
            wrong = listOf(
                PracticeWrong(
                    questionId = "q2",
                    questionTitle = kp2.title,
                    studentAnswer = "0",
                    correctAnswer = "x=2 或 x=3",
                    score = 20,
                    knowledgePointId = "kp2",
                    previousRoundWrong = false,
                ),
            ),
            relatedKnowledgePointIds = listOf("kp2"),
        )
        val data = baseData().copy(practiceReviews = listOf(previous))
        val ai = FakeAi(
            answers = listOf(AnswerDraft(1, "第1题", "最大值 3，最小值 -1"), AnswerDraft(2, "第2题", "猜的")),
            grades = listOf(pass(), fail("订正仍错")),
        )
        val coach = PracticeCoach(ai, clock = { now }, idGenerator = { "review-2" })
        val outcome = coach.review(data, paperId = "p1", pagesText = listOf("订正后答题卡"))
        assertTrue(outcome is Outcome.Success)
        val review = (outcome as Outcome.Success).value.review
        assertEquals(2, review.round)
        val wrong = review.wrong.first { it.questionId == "q2" }
        assertTrue(wrong.previousRoundWrong)
        assertEquals(CorrectionQuality.LOW.name, wrong.correctionQuality)
        assertFalse(wrong.mayAskGuided)
        assertTrue(review.hasBlockedQuestions)

        // 订正质量太差：拒绝引导式提问
        val blocked = coach.openGuided((outcome).value.data, review.id, "q2")
        assertTrue(blocked is Outcome.Failure)
        assertEquals(AppError.CorrectionTooPoor, (blocked as Outcome.Failure).error)
    }

    @Test
    fun openGuidedStartsTutorSessionAndResolvedMergeAddsKnowledgePoint() = kotlinx.coroutines.runBlocking {
        val wrong = PracticeWrong(
            questionId = "q2",
            questionTitle = kp2.title,
            studentAnswer = "0",
            correctAnswer = "x=2 或 x=3",
            score = 20,
            knowledgePointId = "kp2",
            knowledgePointTitle = kp2.title,
            mayAskGuided = true,
        )
        val previous = PracticeReview(
            id = "r1",
            paperId = "p1",
            round = 1,
            totalQuestions = 2,
            gradedQuestions = 2,
            score = 60,
            wrong = listOf(wrong),
            relatedKnowledgePointIds = listOf("kp2"),
        )
        val data = baseData().copy(practiceReviews = listOf(previous))
        val ai = FakeAi(
            guided = GuidedPayload(
                question = "先说说你打算用哪种方法？",
                feedback = "思路对了一半",
                resolved = true,
                stage = 2,
            ),
        )
        val coach = PracticeCoach(ai, clock = { now }, idGenerator = { "guided-1" })

        val opened = coach.openGuided(data, "r1", "q2")
        assertTrue(opened is Outcome.Success)
        val session = (opened as Outcome.Success).value.session
        assertEquals(1, session.stage)
        assertTrue(session.exchanges.first().text.isNotBlank())

        val replied = coach.replyGuided(opened.value.data, session.id, "用因式分解：x²-5x+6=(x-2)(x-3)")
        assertTrue(replied is Outcome.Success)
        val result = (replied as Outcome.Success).value
        assertTrue(result.session.resolved)
        assertNotNull(result.session.knowledgePointId)
        // 引导过程信息并入不会的知识点列表
        assertTrue(result.data.knowledgePoints.any { it.id == result.session.knowledgePointId })
    }
}
