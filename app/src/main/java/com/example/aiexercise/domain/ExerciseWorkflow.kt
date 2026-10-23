package com.example.aiexercise.domain

import com.example.aiexercise.ai.AiJson
import com.example.aiexercise.ai.AiService
import com.example.aiexercise.ai.AnalyzeRequest
import com.example.aiexercise.ai.GradeRequest
import com.example.aiexercise.ai.KpDraft
import com.example.aiexercise.ai.KpGenerationPayload
import com.example.aiexercise.ai.PaperPayload
import com.example.aiexercise.ai.PaperRequest
import com.example.aiexercise.data.model.AppData
import com.example.aiexercise.data.model.Exercise
import com.example.aiexercise.data.model.ExerciseSource
import com.example.aiexercise.data.model.GradeResult
import com.example.aiexercise.data.model.KnowledgePoint
import com.example.aiexercise.data.model.PaperKnowledgePointRef
import com.example.aiexercise.data.model.PracticePaper
import com.example.aiexercise.data.model.ResearchBundle
import java.util.UUID

/** 扫描输入：OCR 文本 / 手动输入 + 可选的参考答案与作答。 */
data class ScanInput(
    val rawText: String,
    val referenceAnswer: String = "",
    val studentAnswer: String = "",
    val source: ExerciseSource = ExerciseSource.MANUAL,
    val imagePath: String? = null,
    val subjectHint: String = "",
)

data class ScanResult(
    val data: AppData,
    val exercise: Exercise,
    val knowledgePoints: List<KnowledgePoint>,
    val isDuplicate: Boolean,
    val duplicatedFromExerciseId: String? = null,
    val refreshedKnowledgePointCount: Int = 0,
    val warning: AppError? = null,
    /** 本次 AI 分类前自动检索到的“本题解析 / 教材依据”，会随习题持久化。 */
    val research: ResearchBundle? = null,
)

data class GradeOutcome(
    val data: AppData,
    val exerciseId: String,
    val grade: GradeResult,
    val relatedKnowledgePoints: List<KnowledgePoint>,
)

data class PaperOutcome(
    val data: AppData,
    val paper: PracticePaper,
    val json: String,
    val selectedKnowledgePoints: List<KnowledgePoint>,
)

data class ReviewOutcome(
    val data: AppData,
    val knowledgePoint: KnowledgePoint,
    val reviewedAt: Long,
)

/**
 * 习题工具的核心工作流（纯 Kotlin，可单测）：
 *
 * 1. [scan]            扫描 → 判重 →（新题）AI 生成知识点 /（同题）特判刷新计时器；
 * 2. [grade]           批改 → 更新知识点计时器（不及格提前复习）→ 返回相关知识点；
 * 3. [generatePaper]   按遗忘曲线紧迫度选题 → AI 生成结构化练习卷（JSON）；
 * 4. [recordReview]    用户自评/复习 → 更新遗忘曲线计时器；
 * 5. [availableAspects] 列出可出卷的“方面”。
 *
 * 所有方法都是“输入旧 AppData → 输出新 AppData”，不修改入参、不做持久化，
 * 由调用方（AppViewModel / 测试）决定何时保存。
 */
class ExerciseToolkit(
    private val ai: AiService,
    private val config: ToolConfig = ToolConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    /** 可选的“自动资料检索”：AI 分类前先找本题解析与教材依据（失败不影响分类）。 */
    private val research: ResearchSource? = null,
) {

    /* ----------------------------- 扫描 ----------------------------- */

    suspend fun scan(
        data: AppData,
        input: ScanInput,
        onStage: (String) -> Unit = {},
    ): Outcome<ScanResult> {
        val text = input.rawText.trim()
        if (text.length < 4) {
            return Outcome.Failure(AppError.UnrecognizedInput("识别文本长度=${text.length}"))
        }
        val now = clock()
        val existing = findSameExercise(data, text)
        val referenceAnswer = input.referenceAnswer.trim().ifBlank { existing?.referenceAnswer.orEmpty() }
        val studentAnswer = input.studentAnswer.trim().ifBlank { existing?.studentAnswer.orEmpty() }
        return if (existing == null) {
            createNewExercise(data, text, input, referenceAnswer, studentAnswer, now, onStage)
        } else {
            refreshDuplicateExercise(data, existing, input, referenceAnswer, studentAnswer, now, onStage)
        }
    }

    fun findSameExercise(data: AppData, questionText: String): Exercise? {
        val fingerprint = ExerciseIdentity.fingerprint(questionText)
        data.exercises.firstOrNull { it.fingerprint == fingerprint }?.let { return it }
        return data.exercises.firstOrNull {
            ExerciseIdentity.isSameExercise(it.questionText, questionText, config.similarityThreshold)
        }
    }

    private suspend fun createNewExercise(
        data: AppData,
        text: String,
        input: ScanInput,
        referenceAnswer: String,
        studentAnswer: String,
        now: Long,
        onStage: (String) -> Unit,
    ): Outcome<ScanResult> {
        // 固定流水线第 1~3 步：网络搜索本题解析 → AI 过滤 → 知识库依据。
        val bundle = researchSafely(text, referenceAnswer, studentAnswer, input.subjectHint, onStage)
        onStage("AI 正在生成不会的知识点…")
        val payload = when (
            val result = ai.analyze(
                AnalyzeRequest(
                    questionText = text,
                    referenceAnswer = referenceAnswer,
                    studentAnswer = studentAnswer,
                    subjectHint = input.subjectHint,
                    asker = data.asker,
                    research = bundle,
                ),
            )
        ) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value
        }
        val exercise = Exercise(
            id = idGenerator(),
            questionText = text,
            fingerprint = ExerciseIdentity.fingerprint(text),
            subject = resolveSubject(payload, input, data),
            referenceAnswer = referenceAnswer,
            studentAnswer = studentAnswer,
            source = input.source,
            imagePath = input.imagePath,
            createdAt = now,
            lastSeenAt = now,
            scanCount = 1,
            lastResearch = bundle,
        )
        val knowledgePoints = upsertKnowledgePoints(
            existing = data.knowledgePoints,
            drafts = payload.knowledgePoints,
            exerciseId = exercise.id,
            now = now,
            applyMasteryCap = true,
        )
        val newData = data.copy(
            exercises = data.exercises + exercise,
            knowledgePoints = knowledgePoints,
        )
        return Outcome.Success(
            ScanResult(
                data = newData,
                exercise = exercise,
                knowledgePoints = relatedKnowledgePoints(newData, exercise.id),
                isDuplicate = false,
                research = bundle,
            ),
        )
    }

    /**
     * 连续扫描同一习题的特判：
     * - 不新建习题、不重复创建知识点，scanCount / refreshCount 自增；
     * - 关联知识点的遗忘曲线计时器重置为 1 天后，掌握度压回 [ToolConfig.duplicateMasteryCap] 以下；
     * - 若本次提供了新的参考答案（或此前没有任何关联知识点），才重新调用 AI 做一次知识点合并；
     * - 习题记录合并本次的作答/图片（空值不覆盖旧值）。
     */
    private suspend fun refreshDuplicateExercise(
        data: AppData,
        existing: Exercise,
        input: ScanInput,
        referenceAnswer: String,
        studentAnswer: String,
        now: Long,
        onStage: (String) -> Unit,
    ): Outcome<ScanResult> {
        val relatedBefore = data.knowledgePoints.filter { existing.id in it.sourceExerciseIds }
        var knowledgePoints = data.knowledgePoints.map { point ->
            if (existing.id !in point.sourceExerciseIds) {
                point
            } else {
                point.copy(
                    timer = KnowledgePointRules.refreshTimer(point.timer, now, config.forgetting),
                    mastery = maxOf(0.05, minOf(point.mastery, config.duplicateMasteryCap)),
                    updatedAt = now,
                    refreshCount = point.refreshCount + 1,
                )
            }
        }
        val referenceChanged =
            input.referenceAnswer.trim().isNotEmpty() && input.referenceAnswer.trim() != existing.referenceAnswer.trim()
        var warning: AppError? = null
        var subject = existing.subject
        var researchBundle: ResearchBundle? = existing.lastResearch
        if (relatedBefore.isEmpty() || referenceChanged) {
            researchBundle = researchSafely(
                existing.questionText,
                referenceAnswer,
                studentAnswer,
                input.subjectHint,
                onStage,
            )
            onStage("AI 正在更新不会的知识点…")
            val request = AnalyzeRequest(
                questionText = existing.questionText,
                referenceAnswer = referenceAnswer,
                studentAnswer = studentAnswer,
                subjectHint = input.subjectHint,
                asker = data.asker,
                research = researchBundle,
            )
            when (val result = ai.analyze(request)) {
                is Outcome.Failure -> {
                    if (relatedBefore.isEmpty()) return result
                    warning = result.error
                }
                is Outcome.Success -> {
                    val payload = result.value
                    if (subject.isBlank()) subject = resolveSubject(payload, input, data)
                    knowledgePoints = upsertKnowledgePoints(
                        existing = knowledgePoints,
                        drafts = payload.knowledgePoints,
                        exerciseId = existing.id,
                        now = now,
                        applyMasteryCap = true,
                    )
                }
            }
        }
        val updatedExercise = existing.copy(
            subject = subject,
            referenceAnswer = referenceAnswer,
            studentAnswer = studentAnswer,
            imagePath = input.imagePath ?: existing.imagePath,
            lastSeenAt = now,
            scanCount = existing.scanCount + 1,
            refreshCount = existing.refreshCount + 1,
            lastResearch = researchBundle,
        )
        val newData = data.copy(
            exercises = data.exercises.map { if (it.id == existing.id) updatedExercise else it },
            knowledgePoints = knowledgePoints,
        )
        return Outcome.Success(
            ScanResult(
                data = newData,
                exercise = updatedExercise,
                knowledgePoints = relatedKnowledgePoints(newData, existing.id),
                isDuplicate = true,
                duplicatedFromExerciseId = existing.id,
                refreshedKnowledgePointCount = relatedBefore.size,
                warning = warning,
                research = researchBundle,
            ),
        )
    }

    /**
     * 资料检索失败只记录在 bundle 的 warning 里，绝不阻断知识点分类；
     * 异常也在此收口（检索属于增强能力）。
     */
    private suspend fun researchSafely(
        questionText: String,
        referenceAnswer: String,
        studentAnswer: String,
        subjectHint: String,
        onStage: (String) -> Unit,
    ): ResearchBundle? {
        val source = research ?: return null
        return runCatching {
            source.research(
                questionText = questionText,
                referenceAnswer = referenceAnswer,
                studentAnswer = studentAnswer,
                subjectHint = subjectHint,
                onStage = onStage,
            )
        }.getOrNull()
    }

    /** 知识点 upsert：命中则合并并刷新计时器，未命中则新建。 */
    private fun upsertKnowledgePoints(
        existing: List<KnowledgePoint>,
        drafts: List<KpDraft>,
        exerciseId: String,
        now: Long,
        applyMasteryCap: Boolean,
    ): List<KnowledgePoint> {
        val working = existing.toMutableList()
        drafts.forEach { draft ->
            val match = KnowledgePointRules.findExisting(working, draft.title, draft.aspect)
            if (match == null) {
                val subject = draft.subject.ifBlank { "综合" }
                working += KnowledgePoint(
                    id = idGenerator(),
                    title = draft.title.trim(),
                    aspect = draft.aspect.trim().ifBlank { subject },
                    subject = subject,
                    detail = draft.detail.trim(),
                    evidence = draft.reason.trim(),
                    sourceExerciseIds = listOf(exerciseId),
                    mastery = MASTERY_ON_CREATE,
                    timer = ForgettingCurve.initialTimer(now, config.forgetting),
                    createdAt = now,
                    updatedAt = now,
                )
            } else {
                val index = working.indexOfFirst { it.id == match.id }
                working[index] = match.copy(
                    detail = KnowledgePointRules.mergeDetail(match.detail, draft.detail),
                    evidence = KnowledgePointRules.mergeEvidence(match.evidence, draft.reason),
                    sourceExerciseIds = (match.sourceExerciseIds + exerciseId).distinct(),
                    mastery = if (applyMasteryCap) {
                        maxOf(0.05, minOf(match.mastery, config.duplicateMasteryCap))
                    } else {
                        match.mastery
                    },
                    timer = KnowledgePointRules.refreshTimer(match.timer, now, config.forgetting),
                    updatedAt = now,
                    refreshCount = match.refreshCount + 1,
                    archived = false,
                )
            }
        }
        return working
    }

    private fun resolveSubject(payload: KpGenerationPayload, input: ScanInput, data: AppData): String =
        payload.subject.trim().ifBlank {
            input.subjectHint.trim().ifBlank {
                payload.knowledgePoints.firstOrNull()?.subject?.trim().orEmpty().ifBlank {
                    data.asker.gradeLevel.trim().ifBlank { "综合" }
                }
            }
        }

    /* ----------------------------- 批改 ----------------------------- */

    suspend fun grade(
        data: AppData,
        exerciseId: String,
        studentAnswerOverride: String? = null,
    ): Outcome<GradeOutcome> {
        val exercise = data.exercises.firstOrNull { it.id == exerciseId }
            ?: return Outcome.Failure(AppError.Unexpected("习题不存在：$exerciseId"))
        val studentAnswer = studentAnswerOverride?.trim().orEmpty().ifBlank { exercise.studentAnswer.trim() }
        if (studentAnswer.isEmpty()) return Outcome.Failure(AppError.MissingStudentAnswer)
        if (exercise.referenceAnswer.trim().isEmpty()) return Outcome.Failure(AppError.MissingReferenceAnswer)
        val payload = when (
            val result = ai.grade(
                GradeRequest(
                    questionText = exercise.questionText,
                    referenceAnswer = exercise.referenceAnswer,
                    studentAnswer = studentAnswer,
                    asker = data.asker,
                ),
            )
        ) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value
        }
        val now = clock()
        val score = payload.score.coerceIn(0, 100)
        val passed = score >= config.passScore
        val grade = GradeResult(
            id = idGenerator(),
            exerciseId = exercise.id,
            score = score,
            passed = passed,
            correctAnswer = payload.correctAnswer.ifBlank { exercise.referenceAnswer },
            studentAnswer = studentAnswer,
            analysisMarkdown = payload.analysis.ifBlank { "（AI 未返回解析）" },
            errorSteps = payload.errorSteps,
            relatedKnowledgePointTitles = payload.relatedKnowledgePoints,
            gradedAt = now,
        )
        // 不及格 → 相关知识点提前复习（遗忘曲线计时器加速），并轻微下调掌握度
        val knowledgePoints = if (passed) {
            data.knowledgePoints
        } else {
            data.knowledgePoints.map { point ->
                if (exercise.id in point.sourceExerciseIds) {
                    point.copy(
                        timer = point.timer.copy(
                            nextReviewAt = minOf(
                                point.timer.nextReviewAt,
                                now + config.failedGradeAccelerateMs,
                            ),
                        ),
                        mastery = maxOf(0.05, point.mastery - FAILED_GRADE_MASTERY_PENALTY),
                        updatedAt = now,
                    )
                } else {
                    point
                }
            }
        }
        val newData = data.copy(
            exercises = data.exercises.map {
                if (it.id == exercise.id) it.copy(studentAnswer = studentAnswer, lastSeenAt = now) else it
            },
            grades = data.grades.filterNot { it.exerciseId == exercise.id } + grade,
            knowledgePoints = knowledgePoints,
        )
        return Outcome.Success(
            GradeOutcome(
                data = newData,
                exerciseId = exercise.id,
                grade = grade,
                relatedKnowledgePoints = relatedKnowledgePoints(newData, exercise.id),
            ),
        )
    }

    /* --------------------------- 练习卷生成 --------------------------- */

    suspend fun generatePaper(
        data: AppData,
        aspect: String,
        count: Int = config.defaultPaperCount,
        onlyDue: Boolean = false,
    ): Outcome<PaperOutcome> {
        val requestedAspect = aspect.trim()
        val matched = data.knowledgePoints.filter {
            !it.archived && KnowledgePointRules.matchesAspect(it, requestedAspect)
        }
        if (matched.isEmpty()) {
            return Outcome.Failure(AppError.NoKnowledgePointForAspect(requestedAspect, availableAspects(data)))
        }
        val now = clock()
        val candidates = if (onlyDue) {
            matched.filter { ForgettingCurve.isDue(it.timer, now, config.forgetting) }
        } else {
            matched
        }
        if (candidates.isEmpty()) {
            return Outcome.Failure(
                AppError.NoKnowledgePointForAspect("$requestedAspect（当前没有到期知识点）", availableAspects(data)),
            )
        }
        val selected = candidates
            .sortedByDescending {
                ForgettingCurve.urgency(it.timer, it.mastery, now, config.forgetting)
            }
            .take(maxOf(1, minOf(count, config.maxKnowledgePointsPerPaper)))
        val questionCount = count.coerceIn(1, config.maxQuestionsPerPaper)
        val payload = when (
            val result = ai.generatePaper(
                PaperRequest(
                    aspect = requestedAspect.ifBlank { "全部" },
                    count = questionCount,
                    asker = data.asker,
                    knowledgePoints = selected,
                ),
            )
        ) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value
        }
        val validated = when (val result = validatePaperPayload(payload)) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value
        }
        val paper = PracticePaper(
            id = idGenerator(),
            aspect = requestedAspect.ifBlank { "全部" },
            title = validated.title.ifBlank { "「${requestedAspect.ifBlank { "全部" }}」专项练习卷" },
            summary = validated.summary,
            generatedAt = now,
            generatedAtIso = TimeFormat.iso(now),
            requestedCount = questionCount,
            knowledgePoints = selected.map {
                PaperKnowledgePointRef(
                    id = it.id,
                    title = it.title,
                    aspect = it.aspect,
                    urgency = ForgettingCurve.urgency(it.timer, it.mastery, now, config.forgetting),
                    nextReviewAt = it.timer.nextReviewAt,
                )
            },
            questions = validated.questions,
        )
        val newData = data.copy(papers = (data.papers + paper).takeLast(config.maxStoredPapers))
        return Outcome.Success(
            PaperOutcome(
                data = newData,
                paper = paper,
                json = AiJson.encodePaper(paper),
                selectedKnowledgePoints = selected,
            ),
        )
    }

    private fun validatePaperPayload(payload: PaperPayload): Outcome<PaperPayload> {
        val questions = payload.questions.filter {
            it.questionMarkdown.isNotBlank() && it.answerMarkdown.isNotBlank()
        }
        if (questions.isEmpty()) {
            return Outcome.Failure(AppError.GenerationInvalid("AI 返回的 questions 为空，或题面/答案缺失"))
        }
        return Outcome.Success(payload.copy(questions = questions))
    }

    /* --------------------------- 复习 / 维度 --------------------------- */

    /** 用户自评复习质量（0..5），更新对应知识点的遗忘曲线计时器。 */
    fun recordReview(data: AppData, knowledgePointId: String, quality: Int): Outcome<ReviewOutcome> {
        val point = data.knowledgePoints.firstOrNull { it.id == knowledgePointId }
            ?: return Outcome.Failure(AppError.Unexpected("知识点不存在：$knowledgePointId"))
        val now = clock()
        val updated = point.copy(
            timer = ForgettingCurve.onReview(point.timer, quality, now, config.forgetting),
            mastery = ForgettingCurve.masteryAfterReview(point.mastery, quality),
            updatedAt = now,
        )
        val newData = data.copy(
            knowledgePoints = data.knowledgePoints.map { if (it.id == point.id) updated else it },
        )
        return Outcome.Success(ReviewOutcome(data = newData, knowledgePoint = updated, reviewedAt = now))
    }

    fun relatedKnowledgePoints(data: AppData, exerciseId: String): List<KnowledgePoint> {
        val now = clock()
        return data.knowledgePoints
            .filter { !it.archived && exerciseId in it.sourceExerciseIds }
            .sortedByDescending { ForgettingCurve.urgency(it.timer, it.mastery, now, config.forgetting) }
    }

    /** 所有可用于出卷的“方面”（去重、排序）。 */
    fun availableAspects(data: AppData): List<String> =
        data.knowledgePoints
            .filter { !it.archived }
            .flatMap { listOf(it.aspect, it.subject) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

    private companion object {
        const val MASTERY_ON_CREATE = 0.2
        const val FAILED_GRADE_MASTERY_PENALTY = 0.05
    }
}
