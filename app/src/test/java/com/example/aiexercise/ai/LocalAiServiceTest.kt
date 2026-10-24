package com.example.aiexercise.ai

import com.example.aiexercise.data.model.AskerProfile
import com.example.aiexercise.data.model.WebHit
import com.example.aiexercise.sampleKnowledgePoint
import com.example.aiexercise.successValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiServiceTest {

    private val service = LocalAiService()

    @Test
    fun distillSolutionKeepsMatchingHitAndDropsNoise() = runBlocking {
        val digest = service.distillSolution(
            SolutionFilterRequest(
                questionText = "已知函数 f(x)=x²-4x+3，求闭区间 [0,3] 上的最大值与最小值。",
                referenceAnswer = "最小值 f(2)=-1，最大值 f(0)=3。",
                webHits = listOf(
                    WebHit(
                        title = "二次函数闭区间最值解析",
                        url = "https://example.com/math/1",
                        snippet = "f(x)=(x-2)²-1，顶点 x=2，比较端点 f(0)=3 与 f(3)=0，故最小值 -1、最大值 3。",
                    ),
                    WebHit(
                        title = "暑期数学补习班招生",
                        url = "https://example.com/ad",
                        snippet = "限时优惠，报名从速，下载 App 领资料。",
                    ),
                ),
            ),
        ).successValue()

        assertTrue(digest.relevant)
        assertTrue(digest.solutionMarkdown.contains("最小值"))
        assertTrue(digest.matchedTitles.any { it.contains("二次函数") })
        assertFalse(digest.matchedTitles.any { it.contains("补习班") })
    }

    @Test
    fun distillSolutionReportsIrrelevantWhenOnlyNoiseHits() = runBlocking {
        val digest = service.distillSolution(
            SolutionFilterRequest(
                questionText = "已知函数 f(x)=x²-4x+3，求闭区间 [0,3] 上的最大值与最小值。",
                referenceAnswer = "最小值 -1，最大值 3。",
                webHits = listOf(
                    WebHit(
                        title = "暑期数学补习班招生",
                        url = "https://example.com/ad",
                        snippet = "限时优惠，报名从速，下载 App 领资料。",
                    ),
                ),
            ),
        ).successValue()

        assertFalse(digest.relevant)
        assertTrue(digest.solutionMarkdown.isBlank())
    }

    @Test
    fun analyzeMathQuestionReturnsMathKnowledgePoints() = runBlocking {
        val payload = service.analyze(
            AnalyzeRequest(
                questionText = "已知抛物线 y=x²-4x+3，求顶点坐标。",
                referenceAnswer = "顶点为 (2,-1)",
                studentAnswer = "顶点为 (2,1)",
            ),
        ).successValue()

        assertEquals("数学", payload.subject)
        assertTrue(payload.knowledgePoints.isNotEmpty())
        assertTrue(payload.knowledgePoints.any { it.aspect == "二次函数" })
        assertTrue(payload.knowledgePoints.all { it.title.isNotBlank() })
    }

    @Test
    fun analyzeEnglishQuestionReturnsEnglishSubject() = runBlocking {
        val payload = service.analyze(
            AnalyzeRequest(
                questionText = "把「我已经完成了作业」翻译成英语，并说明时态。",
                referenceAnswer = "I have finished my homework. 现在完成时。",
                studentAnswer = "I finished my homework.",
                asker = AskerProfile(name = "小明", gradeLevel = "高一", goal = "英语提分"),
            ),
        ).successValue()

        assertEquals("英语", payload.subject)
        assertTrue(payload.knowledgePoints.isNotEmpty())
    }

    @Test
    fun analyzeWithoutReferenceAnswerStillReturnsFallbackKnowledgePoint() = runBlocking {
        val payload = service.analyze(
            AnalyzeRequest(questionText = "阅读材料并回答问题：作者为什么这样写？"),
        ).successValue()

        assertTrue(payload.knowledgePoints.isNotEmpty())
    }

    @Test
    fun gradeComparesNumbers() = runBlocking {
        val correct = service.grade(GradeRequest("计算 2+3 的值。", "5", "5")).successValue()
        assertEquals(100, correct.score)
        assertTrue(correct.correct)
        assertTrue(correct.errorSteps.isEmpty())

        val wrong = service.grade(GradeRequest("计算 2+3 的值。", "5", "6")).successValue()
        assertEquals(0, wrong.score)
        assertFalse(wrong.correct)
        assertTrue(wrong.errorSteps.isNotEmpty())
        assertTrue(wrong.analysis.isNotBlank())
    }

    @Test
    fun generatePaperProducesMarkdownQuestions() = runBlocking {
        val point = sampleKnowledgePoint(now = 0)
        val payload = service.generatePaper(
            PaperRequest(
                aspect = "二次函数",
                count = 3,
                asker = AskerProfile(),
                knowledgePoints = listOf(point),
            ),
        ).successValue()

        assertEquals(3, payload.questions.size)
        assertTrue(payload.questions.all { it.questionMarkdown.isNotBlank() })
        assertTrue(payload.questions.all { it.answerMarkdown.isNotBlank() })
        assertTrue(payload.questions.any { it.questionMarkdown.contains("**") })
        assertTrue(payload.questions.all { it.knowledgePointId == point.id })
    }

    @Test
    fun segmentQuestionsSplitsNumberedPagesAndMergesCrossPageQuestion() = runBlocking {
        val pages = listOf(
            "1. 已知函数 f(x)=x²-4x+3，求 f(x) 在闭区间上的最大值与",
            "最小值。\n2. 解方程 x²-5x+6=0。",
        )
        val payload = service.segmentQuestions(
            SegmentRequest(
                pagesText = pages.mapIndexed { i, text -> "=== 第 ${i + 1} 页 ===\n$text" }.joinToString("\n"),
                pages = pages,
                pageCount = pages.size,
                subjectHint = "高二",
            ),
        ).successValue()

        assertEquals(2, payload.questions.size)
        assertEquals(1, payload.questions[0].pageFrom)
        assertEquals(2, payload.questions[0].pageTo)
        assertTrue(payload.questions[0].questionText.contains("最小值"))
        assertEquals(2, payload.questions[1].pageFrom)
    }

    @Test
    fun generatePaperAlsoReturnsAnswerSheetStructure() = runBlocking {
        val request = PaperRequest(
            aspect = "二次函数",
            count = 3,
            asker = AskerProfile(),
            knowledgePoints = listOf(sampleKnowledgePoint()),
        )
        val payload = service.generatePaper(request).successValue()

        assertEquals(3, payload.questions.size)
        val sheet = payload.answerSheet ?: error("离线出卷也必须给出答题卡结构")
        val parsed = com.example.aiexercise.domain.AnswerSheetCodec.decode(sheet).successValue()
        assertTrue(parsed.sections.isNotEmpty())
        assertEquals(3, parsed.questionCount)
    }
}
