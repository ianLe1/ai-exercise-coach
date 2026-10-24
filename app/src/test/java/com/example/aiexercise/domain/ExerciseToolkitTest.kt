package com.example.aiexercise.domain

import com.example.aiexercise.ai.AiJson
import com.example.aiexercise.ai.LocalAiService
import com.example.aiexercise.domain.AnswerSheetCodec
import com.example.aiexercise.data.model.AppData
import com.example.aiexercise.data.model.ExerciseSource
import com.example.aiexercise.data.model.ResearchBundle
import com.example.aiexercise.data.model.SolutionDigest
import com.example.aiexercise.data.model.WebHit
import com.example.aiexercise.failureError
import com.example.aiexercise.successValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验收测试：用离线 AI 引擎完整跑一遍
 * “扫描 → 生成知识点与计时器 → 批改 → 知识产权汇总 → 连续扫描特判 → 生成练习卷（JSON/Markdown）”。
 */
class ExerciseToolkitTest {

    private var now = 1_700_000_000_000L
    private var sequence = 0

    private val toolkit = ExerciseToolkit(
        ai = LocalAiService(),
        config = ToolConfig(),
        clock = { now },
        idGenerator = { "id-${sequence++}" },
    )

    private val question = "已知函数 f(x)=x²-4x+3，求它在 [0,3] 上的最大值与最小值。"
    private val reference = "f(x)=(x-2)²-1，最小值 f(2)=-1，最大值 f(0)=3。"
    private val student = "最小值 -1，最大值 3。"

    @Test
    fun scanPersistsResearchBundleAndDuplicateScanKeepsIt() = runBlocking {
        val bundle = ResearchBundle(
            query = "题目 解析 答案",
            webHits = listOf(WebHit("解析", "https://example.com/1", "最小值 -1，最大值 3。")),
            digest = SolutionDigest(relevant = true, solutionMarkdown = "## 解析", confidence = 0.8),
            searchedAt = now,
            webState = ResearchBundle.WEB_OK,
        )
        val source = object : ResearchSource {
            override suspend fun research(
                questionText: String,
                referenceAnswer: String,
                studentAnswer: String,
                subjectHint: String,
                onStage: (String) -> Unit,
            ): ResearchBundle = bundle
        }
        val toolkitWithResearch = ExerciseToolkit(
            ai = LocalAiService(),
            config = ToolConfig(),
            clock = { now },
            idGenerator = { "id-${sequence++}" },
            research = source,
        )

        val first = toolkitWithResearch.scan(AppData(), scanInput()).successValue()
        assertEquals(bundle, first.research)
        assertEquals(bundle, first.exercise.lastResearch)
        assertEquals(bundle, first.data.exercises.single().lastResearch)

        val second = toolkitWithResearch.scan(first.data, scanInput()).successValue()
        assertTrue(second.isDuplicate)
        assertEquals(1, second.data.exercises.size)
        assertEquals(first.exercise.id, second.exercise.id)
        assertEquals(bundle, second.exercise.lastResearch)
    }

    private fun scanInput(text: String = question) = ScanInput(
        rawText = text,
        referenceAnswer = reference,
        studentAnswer = student,
        source = ExerciseSource.MANUAL,
    )

    @Test
    fun scanCreatesExerciseAndKnowledgePointsWithTimers() = runBlocking {
        val scan = toolkit.scan(AppData(), scanInput()).successValue()

        assertEquals(1, scan.data.exercises.size)
        assertEquals(1, scan.data.exercises.single().scanCount)
        assertFalse(scan.isDuplicate)
        assertTrue(scan.exercise.fingerprint.isNotBlank())
        assertTrue(scan.knowledgePoints.isNotEmpty())
        scan.data.knowledgePoints.forEach { point ->
            assertEquals(now + DAY_MS, point.timer.nextReviewAt)
            assertEquals(1.0, point.timer.intervalDays, 1e-9)
            assertEquals(listOf(scan.exercise.id), point.sourceExerciseIds)
            assertEquals(0.2, point.mastery, 1e-9)
        }
        assertEquals(scan.data.knowledgePoints.size, scan.knowledgePoints.size)
    }

    @Test
    fun scanningSameExerciseTwiceIsSpecialCased() = runBlocking {
        val first = toolkit.scan(AppData(), scanInput()).successValue()

        now += 3 * DAY_MS
        // OCR 再次识别同一题：多了空格与标点，判重应命中
        val second = toolkit.scan(first.data, scanInput(text = "  $question？")).successValue()

        assertTrue(second.isDuplicate)
        assertEquals(1, second.data.exercises.size)
        assertEquals(2, second.data.exercises.single().scanCount)
        assertEquals(first.data.exercises.single().id, second.exercise.id)
        // 不重复创建知识点：id 集合完全一致
        assertEquals(
            first.data.knowledgePoints.map { it.id }.toSet(),
            second.data.knowledgePoints.map { it.id }.toSet(),
        )
        assertEquals(first.data.knowledgePoints.size, second.refreshedKnowledgePointCount)
        second.data.knowledgePoints.forEach { point ->
            assertEquals(now + DAY_MS, point.timer.nextReviewAt)
            assertEquals(1.0, point.timer.intervalDays, 1e-9)
            assertTrue(point.mastery <= ToolConfig().duplicateMasteryCap)
            assertEquals(1, point.refreshCount)
        }
    }

    @Test
    fun differentQuestionCreatesNewEntry() = runBlocking {
        val first = toolkit.scan(AppData(), scanInput()).successValue()
        val second = toolkit.scan(first.data, scanInput(text = "计算 2+3 的值。")).successValue()

        assertFalse(second.isDuplicate)
        assertEquals(2, second.data.exercises.size)
        assertNotEquals(first.exercise.id, second.exercise.id)
    }

    @Test
    fun gradePassesAndListsRelatedKnowledgePoints() = runBlocking {
        val scan = toolkit.scan(
            AppData(),
            ScanInput("计算 2+3 的值。", "5", "5"),
        ).successValue()

        val outcome = toolkit.grade(scan.data, scan.exercise.id).successValue()

        assertEquals(100, outcome.grade.score)
        assertTrue(outcome.grade.passed)
        assertTrue(outcome.grade.analysisMarkdown.isNotBlank())
        assertTrue(outcome.relatedKnowledgePoints.isNotEmpty())
        assertEquals(scan.knowledgePoints.map { it.id }, outcome.relatedKnowledgePoints.map { it.id })
        assertEquals(1, outcome.data.grades.size)
    }

    @Test
    fun failedGradeAcceleratesReviewAndLowersMastery() = runBlocking {
        val scan = toolkit.scan(AppData(), ScanInput("计算 2+3 的值。", "5", "5")).successValue()
        val knowledgePointId = scan.data.knowledgePoints.first().id

        var data = scan.data
        repeat(2) { data = toolkit.recordReview(data, knowledgePointId, 5).successValue().data }
        val before = data.knowledgePoints.first { it.id == knowledgePointId }
        assertEquals(now + 3 * DAY_MS, before.timer.nextReviewAt)

        now += 60_000
        val outcome = toolkit.grade(data, scan.exercise.id, "6").successValue()
        val after = outcome.data.knowledgePoints.first { it.id == knowledgePointId }

        assertFalse(outcome.grade.passed)
        assertEquals(now + DAY_MS, after.timer.nextReviewAt)
        assertTrue(after.mastery < before.mastery)
    }

    @Test
    fun paperAnswerSheetJsonMatchesRequiredShape() = runBlocking {
        val scan = toolkit.scan(AppData(), scanInput()).successValue()
        val aspect = scan.knowledgePoints.first().aspect

        val outcome = toolkit.generatePaper(scan.data, aspect, count = 3).successValue()

        assertEquals(3, outcome.paper.questions.size)
        // 对外 JSON 是「答题卡结构」：大题 → 题 →（可选小题），值只能是数字/数组/字符串/对象。
        assertTrue(outcome.json.trimStart().startsWith("{"))
        assertEquals(outcome.paper.answerSheetJson, outcome.json)
        val sheet = AnswerSheetCodec.parse(outcome.json).successValue()
        assertTrue("必须至少有一个大题", sheet.sections.isNotEmpty())
        assertTrue("至少要有一道题", sheet.questionCount > 0)
        assertEquals(3, sheet.questionCount)
        sheet.sections.forEach { (section, questions) ->
            assertTrue("大题名不能为空", section.isNotBlank())
            assertTrue("每个大题下至少一题", questions.isNotEmpty())
        }
        // 题面 / 答案 Markdown 仍保存在完整练习卷 JSON 里。
        val full = AiJson.decodePaper(outcome.fullJson)
        assertEquals(outcome.paper.id, full.id)
        assertTrue(full.questions.all { it.questionMarkdown.isNotBlank() && it.answerMarkdown.isNotBlank() })
        assertTrue(
            full.questions.any {
                it.questionMarkdown.contains("**") || it.questionMarkdown.contains("###")
            },
        )
        assertEquals(1, outcome.data.papers.size)
    }

    @Test
    fun paperGenerationFailsReadablyForUnknownAspect() = runBlocking {
        val scan = toolkit.scan(AppData(), scanInput()).successValue()

        val error = toolkit.generatePaper(scan.data, "不存在的方面").failureError()

        assertTrue(error is AppError.NoKnowledgePointForAspect)
        assertTrue(error.userMessage.contains("不存在的方面"))
        val available = (error as AppError.NoKnowledgePointForAspect).available
        assertTrue(available.isNotEmpty())
    }

    @Test
    fun reviewUpdatesForgettingCurveTimer() = runBlocking {
        val scan = toolkit.scan(AppData(), scanInput()).successValue()
        val before = scan.knowledgePoints.first()

        val outcome = toolkit.recordReview(scan.data, before.id, 5).successValue()

        assertEquals(1, outcome.knowledgePoint.timer.reviewCount)
        assertTrue(outcome.knowledgePoint.mastery > before.mastery)
        assertTrue(outcome.knowledgePoint.timer.nextReviewAt > now)
    }

    @Test
    fun tooShortTextIsRejectedWithReadableError() = runBlocking {
        val error = toolkit.scan(AppData(), ScanInput("x")).failureError()
        assertTrue(error is AppError.UnrecognizedInput)
    }

@Test
    fun multiPageScanKeepsSectionAndPageRangeMetadata() = runBlocking {
        val scan = toolkit.scan(
            AppData(),
            scanInput().copy(
                sectionTitle = "一、选择题",
                pageIndexes = listOf(1, 2),
                crossPage = true,
                scanPaperId = "paper-1",
            ),
        ).successValue()

        val exercise = scan.exercise
        assertEquals("一、选择题", exercise.sectionTitle)
        assertEquals(listOf(1, 2), exercise.pageIndexes)
        assertTrue(exercise.crossPage)
        assertEquals("paper-1", exercise.scanPaperId)

        // 再扫同一题：页码合并去重、批次保留、仍不新建条目。
        val second = toolkit.scan(
            scan.data,
            scanInput().copy(sectionTitle = "一、选择题", pageIndexes = listOf(2, 3), scanPaperId = "paper-2"),
        ).successValue()
        assertTrue(second.isDuplicate)
        assertEquals(1, second.data.exercises.size)
        assertEquals(listOf(1, 2, 3), second.exercise.pageIndexes)
        assertTrue(second.exercise.crossPage)
        assertEquals("paper-1", second.exercise.scanPaperId)
        assertEquals(1, second.data.knowledgePoints.size)
    }
}
