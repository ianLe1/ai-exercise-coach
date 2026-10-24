package com.example.aiexercise.ai

import com.example.aiexercise.data.model.PaperQuestion
import com.example.aiexercise.data.model.ResearchBundle
import com.example.aiexercise.data.model.SolutionDigest
import com.example.aiexercise.data.model.WebHit
import com.example.aiexercise.domain.AnswerSheetCodec
import com.example.aiexercise.domain.AppError
import com.example.aiexercise.domain.ExerciseIdentity
import com.example.aiexercise.domain.ForgettingCurve
import com.example.aiexercise.domain.LocalGuidedTutor
import com.example.aiexercise.domain.Outcome
import com.example.aiexercise.domain.QuestionSegmenter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 离线规则引擎（默认实现，可替换点）。
 *
 * 用途：在没有配置任何大模型 API Key 时也能完整跑通验收流程（离线演示 / 单元测试 / 兜底）。
 * 能力边界：知识点来自内置学科关键词规则，批改基于数值比对 + 文本相似度，练习卷为模板生成；
 * 需要更细粒度、更接近真实老师水平时，在「设置」切换到云端 AI（[OpenAiCompatibleAiService]）。
 */
class LocalAiService : AiService {

    override val id: String = "offline-rules"
    override val displayName: String = "离线规则引擎（无需 API Key）"
    override val isOffline: Boolean = true

    /* ---------------------------- 知识点分析 ---------------------------- */

    override suspend fun analyze(request: AnalyzeRequest): Outcome<KpGenerationPayload> {
        val base = "${request.questionText}\n${request.referenceAnswer}\n${request.studentAnswer}"
        val subject = guessSubject(base)
        val matched = matchRules(base).take(MAX_POINTS)
        val drafts = if (matched.isEmpty()) {
            fallbackDrafts(request, subject, base)
        } else {
            matched.map { rule ->
                KpDraft(
                    title = rule.title,
                    aspect = rule.aspect,
                    subject = rule.subject,
                    detail = rule.detail,
                    reason = evidenceFor(request),
                )
            }
        }
        val enrichedDrafts = applyResearch(drafts, request.research)
        val analysis = buildString {
            append("依据题目、参考答案与提问者（${request.asker.name}，${request.asker.gradeLevel}）信息，")
            append("从题干与作答中识别出 ${enrichedDrafts.size} 个待巩固知识点。")
            if (matched.isEmpty()) append("（离线规则未命中具体学科规则，返回通用知识点。）")
            request.research?.let { bundle ->
                if (bundle.digest.relevant) append("已结合 AI 从网络检索结果中筛出的题目解析。")
                if (bundle.kbCitations.isNotEmpty()) {
                    append("已参考教材/教师用书知识库的 ${bundle.kbCitations.size} 条依据。")
                }
            }
        }
        val payload = KpGenerationPayload(
            analysis = analysis,
            subject = subject,
            knowledgePoints = enrichedDrafts,
        )
        return AiPayloadValidator.validateKnowledgePoints(payload)
    }

    /**
     * 离线版的「AI 过滤网络解析」：用数值命中 + 题干/答案关键词覆盖度给每条搜索结果打分，
     * 只保留与本题一致的内容，并拼成一段 Markdown 解析。
     * （云端实现由大模型直接判断相关性，见 [OpenAiCompatibleAiService.distillSolution]。）
     */
    override suspend fun distillSolution(request: SolutionFilterRequest): Outcome<SolutionDigest> {
        if (request.webHits.isEmpty()) {
            return Outcome.Success(SolutionDigest(relevant = false))
        }
        val scored = request.webHits
            .map { hit -> hit to hitScore(hit, request) }
            .sortedByDescending { it.second }
        val best = scored.first()
        val matched = scored.filter { it.second >= MIN_HIT_SCORE }
        if (best.second < MIN_HIT_SCORE || matched.isEmpty()) {
            return Outcome.Success(
                SolutionDigest(
                    relevant = false,
                    confidence = best.second,
                    matchedTitles = emptyList(),
                ),
            )
        }
        val kept = matched.take(MAX_DIGEST_SOURCES)
        val markdown = buildString {
            appendLine("## 题目解析（从网络检索结果中筛选）")
            appendLine()
            appendLine(kept.first().first.snippet.trim())
            appendLine()
            if (kept.size > 1) {
                appendLine("**同类解析补充**")
                kept.drop(1).forEach { (hit, _) ->
                    appendLine("- ${hit.title}：${hit.snippet.take(80)}")
                }
                appendLine()
            }
            append("> 筛选依据：题干关键词与参考答案数值匹配度 ${(best.second * 100).roundToInt()}%；来源仅供复习参考。")
        }
        return AiPayloadValidator.validateSolutionDigest(
            SolutionDigest(
                relevant = true,
                solutionMarkdown = markdown,
                confidence = best.second,
                matchedTitles = kept.map { it.first.title }.filter { it.isNotBlank() },
            ),
        )
    }

    /** 把网络解析 + 教材依据并入知识点条目（离线实现的可解释性来源）。 */
    private fun applyResearch(drafts: List<KpDraft>, bundle: ResearchBundle?): List<KpDraft> {
        if (bundle == null) return drafts
        val citations = bundle.kbCitations
        return drafts.mapIndexed { index, draft ->
            val kb = citations.firstOrNull { it.subject == draft.subject || it.subject == draft.aspect }
                ?: citations.firstOrNull()
            val tags = buildList {
                if (bundle.digest.relevant) add("网络解析已由 AI 筛选纳入参考")
                if (kb != null) add("教材依据：《${kb.title}》${kb.citationLabel()}")
            }
            if (tags.isEmpty()) {
                draft
            } else {
                draft.copy(
                    reason = (draft.reason.trim().trimEnd('；') + "；" + tags.joinToString("；")).trim('；'),
                    detail = if (index == 0 && kb != null && kb.snippet.isNotBlank()) {
                        (draft.detail.trim() + "\n\n【教材依据】" + kb.snippet.take(180) +
                            "（${kb.title} · ${kb.citationLabel()}）").trim()
                    } else {
                        draft.detail
                    },
                )
            }
        }
    }

    /* ------------------------------- 批改 ------------------------------- */

    override suspend fun grade(request: GradeRequest): Outcome<GradePayload> {
        val reference = request.referenceAnswer.trim()
        val student = request.studentAnswer.trim()
        val (score, comparison) = scoreAnswer(reference, student)
        val passed = score >= PASS_SCORE
        val errorSteps = mutableListOf<String>()
        if (student.isEmpty()) errorSteps += "未作答"
        if (!passed) {
            errorSteps += comparison
            if (student.length * 2 < reference.length) {
                errorSteps += "作答明显短于参考答案，关键步骤可能缺失"
            }
        }
        val related = matchRules("${request.questionText}\n$reference")
            .map { it.title }
            .ifEmpty { listOf("${guessSubject(request.questionText)}核心概念与解题步骤") }
            .take(MAX_POINTS)
        val analysis = buildString {
            appendLine("## 批改结果")
            appendLine()
            appendLine("- 得分：**$score / 100**（${if (passed) "通过" else "未通过"}）")
            appendLine("- 判分依据：$comparison")
            appendLine()
            appendLine("## 参考答案")
            appendLine()
            appendLine(reference)
            appendLine()
            appendLine("## 你的作答")
            appendLine()
            appendLine(student.ifBlank { "（空）" })
            appendLine()
            appendLine("## 改进建议")
            appendLine()
            errorSteps.forEach { appendLine("- $it") }
            appendLine("- 回到相关知识点卡片，按遗忘曲线安排复习并重做同类题。")
        }
        val payload = GradePayload(
            score = score,
            correct = passed,
            correctAnswer = reference,
            analysis = analysis,
            errorSteps = errorSteps,
            relatedKnowledgePoints = related,
        )
        return AiPayloadValidator.validateGrade(payload)
    }

    /* ----------------------------- 多页扫描分割 ----------------------------- */

    override suspend fun segmentQuestions(request: SegmentRequest): Outcome<SegmentPayload> {
        val pages = request.pages.ifEmpty { listOf(request.pagesText) }
        val segments = QuestionSegmenter.segment(pages)
        if (segments.isEmpty()) {
            return Outcome.Failure(AppError.UnrecognizedInput("扫描内容为空或没有识别出题目"))
        }
        val payload = SegmentPayload(
            questions = segments.map {
                SegmentDraft(
                    sectionTitle = it.sectionTitle,
                    questionText = it.text,
                    pageFrom = it.pageFrom,
                    pageTo = it.pageTo,
                )
            },
            note = "离线规则分割：按题号 / 大题标题切分，跨页续接自动合并",
        )
        return AiPayloadValidator.validateSegments(payload, pages.size)
    }

    /* ----------------------------- 练习卷生成 ----------------------------- */

    override suspend fun generatePaper(request: PaperRequest): Outcome<PaperPayload> {
        if (request.knowledgePoints.isEmpty()) {
            return Outcome.Failure(AppError.GenerationInvalid("没有可考察的知识点"))
        }
        val questions = ArrayList<PaperQuestion>(request.count)
        val basePoint = request.knowledgePoints.first()
        val newCount = request.newPointCount.coerceIn(0, request.count / 3)
        val newStart = request.count - newCount
        repeat(request.count) { index ->
            val point = if (index >= newStart) {
                // 新知识点：同年级常考但不在「不会的知识点」列表里，用来保证整卷完整
                basePoint.copy(
                    id = "new-${index + 1}",
                    title = "新知识点（${request.aspect}常考）",
                    mastery = 0.0,
                )
            } else if (request.reviewPoints.isNotEmpty() && index % 5 == 3) {
                // 会的知识点：穿插巩固
                request.reviewPoints[index % request.reviewPoints.size]
            } else {
                request.knowledgePoints[index % request.knowledgePoints.size]
            }
            val difficulty = DIFFICULTIES[index % DIFFICULTIES.size]
            val id = "L-${index + 1}"
            val section = when {
                index < (request.count * 8 + 19) / 20 -> "一、选择题"
                index < ((request.count * 8 + 19) / 20 + (request.count * 6 + 19) / 20) -> "二、填空题"
                else -> "三、解答题"
            }
            val type = when (section) {
                "一、选择题" -> "choice"
                "二、填空题" -> "recall"
                else -> "application"
            }
            val question = when (type) {
                "choice" -> choiceQuestion(id, index + 1, point, difficulty)
                "application" -> applicationQuestion(id, index + 1, point, difficulty)
                else -> recallQuestion(id, index + 1, point, difficulty)
            }
            questions += question.copy(section = section)
        }
        val titles = request.knowledgePoints.joinToString("、") { it.title }
        val payload = PaperPayload(
            title = "「${request.aspect}」综合复习试卷（离线模板 · 100 分 / 90 分钟）",
            aspect = request.aspect,
            summary = "主考 ${request.knowledgePoints.size} 个不会的知识点：$titles；" +
                "另穿插 ${request.reviewPoints.size} 个相对熟练的知识点巩固，并补充 $newCount 道新知识点题，保证整卷完整。" +
                "本卷由离线规则引擎模板生成；在「设置」中配置云端 AI 后可生成学科化题目。",
            questions = questions,
            // 答题卡结构：数字=答题行数、数组=选择题选项、字符串=填空（下划线）
            answerSheet = AnswerSheetCodec.encode(AnswerSheetCodec.derive(questions)),
        )
        return AiPayloadValidator.validatePaper(payload)
    }

    private fun recallQuestion(
        id: String,
        number: Int,
        point: com.example.aiexercise.data.model.KnowledgePoint,
        difficulty: String,
    ): PaperQuestion = PaperQuestion(
        id = id,
        type = "recall",
        difficulty = difficulty,
        questionMarkdown = buildString {
            append("请在不看资料的情况下，写出「${point.title}」的**定义 / 公式 / 关键步骤**，并注明 1 个适用条件。")
        },
        answerMarkdown = "**参考要点：** " + point.detail.ifBlank { "以教材定义为准，覆盖“定义 → 公式 → 适用条件”三部分。" },
        explanationMarkdown = "对照参考要点，标出遗漏部分；按「定义 → 公式 → 适用条件 → 例子」四步复述一遍。",
        knowledgePointId = point.id,
        knowledgePointTitle = point.title,
    )

    private fun choiceQuestion(
        id: String,
        number: Int,
        point: com.example.aiexercise.data.model.KnowledgePoint,
        difficulty: String,
    ): PaperQuestion = PaperQuestion(
        id = id,
        type = "choice",
        difficulty = difficulty,
        questionMarkdown = buildString {
            appendLine("处理「${point.title}」相关问题时，下列做法最恰当的是（　）")
            appendLine()
            appendLine("A. 只背结论，不推导过程")
            appendLine("B. 先辨明适用条件，再代入公式或方法")
            appendLine("C. 直接套用上一道类似题的解法")
            appendLine("D. 跳过检验，直接写答案")
        },
        options = listOf("A. 只背结论，不推导过程", "B. 先辨明适用条件，再代入公式或方法", "C. 直接套用上一道类似题的解法", "D. 跳过检验，直接写答案"),
        answerMarkdown = "**B**",
        explanationMarkdown = "先判断适用条件再使用公式/方法是避免「会公式但做错题」的关键；其余选项都会放大同类错误。",
        knowledgePointId = point.id,
        knowledgePointTitle = point.title,
    )

    private fun applicationQuestion(
        id: String,
        number: Int,
        point: com.example.aiexercise.data.model.KnowledgePoint,
        difficulty: String,
    ): PaperQuestion = PaperQuestion(
        id = id,
        type = "application",
        difficulty = difficulty,
        questionMarkdown = buildString {
            appendLine("从你最近的错题中找一道考查「${point.title}」的题，完成：")
            appendLine()
            appendLine("1. 写出它考查的核心概念；")
            appendLine("2. 给出完整解题步骤；")
            appendLine("3. 标出你上次出错的那一步，并说明原因。")
            appendLine()
            append("> 提示：${point.detail.ifBlank { "先复习该知识点的定义与适用条件。" }}")
        },
        answerMarkdown = "开放题。参考要点：核心概念（${point.title}）+ 完整步骤 + 易错点归因，三者缺一不可。",
        explanationMarkdown = "检查清单：概念是否准确、步骤是否完整、错因是否具体（概念混淆 / 条件遗漏 / 计算失误）。",
        knowledgePointId = point.id,
        knowledgePointTitle = point.title,
    )

    /* ------------------------------- 内部工具 ------------------------------- */

    private data class KpRule(
        val keywords: List<String>,
        val title: String,
        val aspect: String,
        val subject: String,
        val detail: String,
    )

    private fun matchRules(text: String): List<KpRule> {
        val lower = text.lowercase()
        return RULES.filter { rule -> rule.keywords.any { lower.contains(it.lowercase()) } }
    }

    private fun fallbackDrafts(
        request: AnalyzeRequest,
        subject: String,
        base: String,
    ): List<KpDraft> {
        val safeAspect = subject.ifBlank { "综合" }
        val first = KpDraft(
            title = if (subject == "综合") "题目关键信息提取与解题步骤" else "${subject}题的核心概念与解题思路",
            aspect = safeAspect,
            subject = subject,
            detail = "结合题干、参考答案与作答，复习该题涉及的核心概念、公式与规范步骤。",
            reason = evidenceFor(request),
        )
        val second = if (request.studentAnswer.isNotBlank() &&
            ExerciseIdentity.similarity(request.studentAnswer, request.referenceAnswer) < 0.6
        ) {
            KpDraft(
                title = "${safeAspect}题中“会而不对”的易错点排查",
                aspect = safeAspect,
                subject = subject,
                detail = "对照参考答案定位偏差：概念混淆、条件遗漏还是计算失误，并记录到错题本。",
                reason = "作答与参考答案存在偏差，说明存在稳定性问题",
            )
        } else {
            null
        }
        return listOfNotNull(first, second)
    }

    private fun evidenceFor(request: AnalyzeRequest): String {
        val student = request.studentAnswer.trim()
        val reference = request.referenceAnswer.trim()
        return when {
            reference.isEmpty() -> "缺少参考答案，依据题干文本推断（补录答案可提高准确度）"
            student.isEmpty() -> "提问者尚未作答，先标记为「不会」，待批改后校准"
            ExerciseIdentity.similarity(student, reference) < 0.6 ->
                "作答与参考答案不一致（文本相似度 ${(ExerciseIdentity.similarity(student, reference) * 100).roundToInt()}%），判定为未掌握"
            else -> "作答接近参考答案，但该知识点尚无复习记录，先标记为待巩固"
        }
    }

    private fun guessSubject(text: String): String {
        val lower = text.lowercase()
        return when {
            SUBJECT_KEYWORDS.math.any { lower.contains(it) } -> "数学"
            SUBJECT_KEYWORDS.english.any { lower.contains(it) } -> "英语"
            SUBJECT_KEYWORDS.physics.any { lower.contains(it) } -> "物理"
            SUBJECT_KEYWORDS.chemistry.any { lower.contains(it) } -> "化学"
            SUBJECT_KEYWORDS.chinese.any { lower.contains(it) } -> "语文"
            else -> "综合"
        }
    }

    private fun scoreAnswer(reference: String, student: String): Pair<Int, String> {
        if (student.isEmpty()) return 0 to "未作答"
        val refNumbers = extractNumbers(reference)
        val stuNumbers = extractNumbers(student)
        if (refNumbers.isNotEmpty() && stuNumbers.isNotEmpty()) {
            val matched = refNumbers.count { r -> stuNumbers.any { s -> nearlyEqual(s, r) } }
            val ratio = matched.toDouble() / refNumbers.size
            val score = (ratio * 100).roundToInt().coerceIn(0, 100)
            val comparison = "数值比对：参考答案关键数值命中 $matched/${refNumbers.size}"
            return score to comparison
        }
        val similarity = ExerciseIdentity.similarity(reference, student)
        val coverage = keywordCoverage(reference, student)
        val score = ((similarity * 0.6 + coverage * 0.4) * 100).roundToInt().coerceIn(0, 100)
        return score to "文本比对：相似度 ${(similarity * 100).roundToInt()}%，要点覆盖 ${(coverage * 100).roundToInt()}%"
    }

    /**
     * 给一条网络命中打“与本题相关”的分：参考答案数值命中 + 题干/答案关键词覆盖 − 噪声词惩罚。
     * 返回 0..1；离线实现只把分数够高的命中当作“本题解析”。
     */
    private fun hitScore(hit: WebHit, request: SolutionFilterRequest): Double {
        val text = hit.title + "\n" + hit.snippet
        if (text.isBlank()) return 0.0
        val refNumbers = extractNumbers(request.referenceAnswer)
        val hitNumbers = extractNumbers(text)
        val numberPart = if (refNumbers.isEmpty()) {
            0.0
        } else {
            refNumbers.count { reference -> hitNumbers.any { value -> nearlyEqual(value, reference) } }
                .toDouble() / refNumbers.size * 0.45
        }
        val questionPart = keywordCoverage(request.questionText, text) * 0.35
        val answerPart = if (request.referenceAnswer.isBlank()) {
            0.0
        } else {
            keywordCoverage(request.referenceAnswer, text) * 0.2
        }
        val noisePart = if (NOISE_WORDS.any { text.contains(it) }) -0.25 else 0.0
        return (numberPart + questionPart + answerPart + noisePart).coerceIn(0.0, 1.0)
    }

    private fun extractNumbers(text: String): List<Double> =        Regex("-?\\d+(?:\\.\\d+)?").findAll(text).mapNotNull { it.value.toDoubleOrNull() }.toList()

    private fun nearlyEqual(a: Double, b: Double): Boolean =
        abs(a - b) <= maxOf(0.001, abs(b) * 0.01)

    private fun keywordCoverage(reference: String, student: String): Double {
        val ref = ExerciseIdentity.normalize(reference)
        val stu = ExerciseIdentity.normalize(student)
        if (ref.length < 2) return 0.0
        val chunks = (0 until ref.length - 1).map { ref.substring(it, it + 2) }.toSet()
        if (chunks.isEmpty()) return 0.0
        val hit = chunks.count { stu.contains(it) }
        return hit.toDouble() / chunks.size
    }

    private object SUBJECT_KEYWORDS {
        val math = listOf(
            "函数", "方程", "不等式", "数列", "几何", "概率", "导数", "向量", "三角函数", "集合",
            "抛物线", "顶点", "对称轴", "sin", "cos", "tan", "log", "最大值", "最小值",
        )
        val english = listOf("时态", "从句", "语态", "动词", "英语", "english", "grammar", "translate", "tense")
        val physics = listOf("物理", "加速度", "牛顿", "电路", "电流", "电压", "浮力", "压强", "动能", "透镜", "折射", "受力")
        val chemistry = listOf("化学", "配平", "离子", "氧化", "还原", "摩尔", "mol", "溶液", "ph值")
        val chinese = listOf("古诗", "文言文", "阅读理解", "作文", "修辞", "意象", "实词", "虚词")
    }


    /* ------------------------- 练习答题卡 + 引导式提问 ------------------------- */

    override suspend fun readAnswers(request: AnswerReadRequest): Outcome<AnswerReadPayload> {
        val lines = request.pagesText
            .flatMap { it.lines() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val numbered = Regex("^(?:第)?(\\d{1,3})[.、:：)）]?\\s*(.*)$")
        val byNumber = lines.mapNotNull { line ->
            val match = numbered.find(line) ?: return@mapNotNull null
            val index = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val rest = match.groupValues[2].trim()
            if (rest.isEmpty()) null else AnswerDraft(index = index, questionHint = line.take(24), answerText = rest)
        }
        val answers = if (byNumber.isNotEmpty()) {
            byNumber.distinctBy { it.index }
        } else {
            lines.filter { it.length > 1 }.mapIndexed { i, line ->
                AnswerDraft(index = i + 1, questionHint = line.take(24), answerText = line)
            }
        }.take(MAX_ANSWER_DRAFTS)
        val payload = AnswerReadPayload(
            answers = answers,
            note = "离线规则：按题号或行顺序提取答题卡文字；手写体建议切到云端 AI 或 modlens 视觉桥。",
        )
        return AiPayloadValidator.validateAnswers(payload, request.paperQuestions.size)
    }

    override suspend fun solve(request: SolveRequest): Outcome<SolvePayload> {
        val question = request.questionText.trim()
        if (question.length < 4) {
            return Outcome.Failure(AppError.UnrecognizedInput("题目内容太短，无法生成参考答案"))
        }
        val subject = guessSubject(question)
        val matched = matchRules(question).take(2)
        val steps = buildString {
            appendLine("1. 读题：圈出已知条件、所求量与取值范围。")
            if (matched.isEmpty()) {
                appendLine("2. 选定方法：把题目拆成「已知 → 公式/定理 → 未知」，必要时画图或列式。")
            } else {
                matched.forEachIndexed { index, rule ->
                    appendLine("${index + 2}. 关键点（${rule.title}）：${rule.detail}")
                }
            }
            appendLine("3. 代入计算，检查单位、范围与特殊情况，最后写出结论。")
        }
        val answer = matched.firstOrNull()?.detail?.takeIf { it.isNotBlank() }
            ?: "按「已知条件 → 对应公式/定理 → 计算 → 检验」给出完整步骤，并写出最终结论。"
        return AiPayloadValidator.validateSolution(
            SolvePayload(
                referenceAnswer = "参考要点：$answer",
                stepsMarkdown = steps.trim(),
                confidence = if (matched.isEmpty()) 0.4 else 0.6,
            ),
        )
    }

    override suspend fun guidedStep(request: GuidedRequest): Outcome<GuidedPayload> {
        val turn = LocalGuidedTutor.next(request.stage, request.exchanges.lastOrNull()?.text.orEmpty())
        return AiPayloadValidator.validateGuided(
            GuidedPayload(
                question = turn.question,
                feedback = turn.feedback,
                resolved = turn.resolved,
                stage = turn.stage,
            ),
        )
    }

    private companion object {
        const val MAX_POINTS = 3
        const val PASS_SCORE = 60

        /** 网络解析筛选中，命中分数低于该阈值的内容视为与本题无关。 */
        const val MIN_HIT_SCORE = 0.12

        /** 一次最多采纳的搜索结果条数（拼进解析里）。 */
        const val MAX_DIGEST_SOURCES = 3
        const val MAX_ANSWER_DRAFTS = 20

        /** 明显与“题目解析”无关的噪声词（离线过滤用）。 */
        val NOISE_WORDS = listOf("招聘", "广告", "下载", "优惠", "小说", "游戏", "彩票", "直播", "短视频")

        val DIFFICULTIES = listOf("easy", "medium", "hard")

        val RULES = listOf(
            KpRule(
                keywords = listOf("二次函数", "抛物线"),
                title = "二次函数的图象与性质（顶点/对称轴）",
                aspect = "二次函数",
                subject = "数学",
                detail = "配方为 y=a(x-h)²+k 后，顶点 (h,k)、对称轴 x=h；a>0 开口向上，a<0 开口向下。",
            ),
            KpRule(
                keywords = listOf("最大值", "最小值", "最值"),
                title = "闭区间上的最值求法",
                aspect = "二次函数",
                subject = "数学",
                detail = "闭区间求最值必须同时比较顶点与两个端点的函数值，不能只代入顶点。",
            ),
            KpRule(
                keywords = listOf("一元二次方程", "判别式", "解方程", "求根"),
                title = "一元二次方程与判别式",
                aspect = "方程与不等式",
                subject = "数学",
                detail = "ax²+bx+c=0 的判别式 Δ=b²-4ac 决定实根个数；求根公式与因式分解要会互验。",
            ),
            KpRule(
                keywords = listOf("不等式", "解集", "取值范围"),
                title = "不等式求解与取值范围",
                aspect = "方程与不等式",
                subject = "数学",
                detail = "注意乘除负数变号、端点是否可取，以及与函数值域问题的转化。",
            ),
            KpRule(
                keywords = listOf("定义域", "值域", "单调性", "奇偶性"),
                title = "函数的定义域、值域与单调性",
                aspect = "函数基础",
                subject = "数学",
                detail = "先定定义域再谈性质；单调性可用定义法或导数判断。",
            ),
            KpRule(
                keywords = listOf("三角函数", "正弦", "余弦", "sin", "cos", "tan"),
                title = "三角函数公式与图象",
                aspect = "三角函数",
                subject = "数学",
                detail = "和差化积、二倍角公式与图象变换（平移/伸缩）是高频失分点。",
            ),
            KpRule(
                keywords = listOf("等差数列", "等比数列", "通项", "前n项"),
                title = "数列通项与求和",
                aspect = "数列",
                subject = "数学",
                detail = "等差/等比基本量法；求和注意裂项、错位相减的适用条件。",
            ),
            KpRule(
                keywords = listOf("概率", "随机变量", "期望", "分布"),
                title = "概率与随机变量",
                aspect = "概率统计",
                subject = "数学",
                detail = "区分互斥与独立事件；期望与方差公式要结合分布列。",
            ),
            KpRule(
                keywords = listOf("导数", "求导", "切线", "极值"),
                title = "导数与函数极值",
                aspect = "导数",
                subject = "数学",
                detail = "先求定义域与导函数，再解 f'(x)=0 并用符号变化判断极值；切线问题注意切点。",
            ),
            KpRule(
                keywords = listOf("向量", "数量积", "坐标运算"),
                title = "平面向量的运算与应用",
                aspect = "平面向量",
                subject = "数学",
                detail = "坐标运算与几何意义结合；数量积可求夹角与垂直条件。",
            ),
            KpRule(
                keywords = listOf("时态", "一般现在时", "过去时", "完成时", "tense"),
                title = "英语时态辨析与使用",
                aspect = "时态与语态",
                subject = "英语",
                detail = "先判断时间标志词与动作状态（进行/完成/习惯），再选时态；主谓一致同步检查。",
            ),
            KpRule(
                keywords = listOf("从句", "定语从句", "宾语从句", "状语从句"),
                title = "从句结构与连接词",
                aspect = "从句",
                subject = "英语",
                detail = "先判断从句类型与成分缺失，再选关系词/连接词；注意语序与介词搭配。",
            ),
            KpRule(
                keywords = listOf("非谓语", "不定式", "分词", "动名词"),
                title = "非谓语动词",
                aspect = "非谓语动词",
                subject = "英语",
                detail = "依据与逻辑主语的关系（主动/被动）和动作时间（先/同时/后）选择 to do / doing / done。",
            ),
            KpRule(
                keywords = listOf("加速度", "牛顿", "受力", "摩擦力"),
                title = "牛顿运动定律与受力分析",
                aspect = "力学",
                subject = "物理",
                detail = "先画受力图，再正交分解；加速度方向与合力方向一致。",
            ),
            KpRule(
                keywords = listOf("电路", "电流", "电压", "电阻", "欧姆"),
                title = "电路分析与欧姆定律",
                aspect = "电学",
                subject = "物理",
                detail = "识别串并联结构，明确电表测量对象；动态电路按“局部 → 整体 → 局部”分析。",
            ),
            KpRule(
                keywords = listOf("浮力", "压强", "液体"),
                title = "浮力与压强",
                aspect = "力学",
                subject = "物理",
                detail = "区分固体压强 p=F/S 与液体压强 p=ρgh；浮力用阿基米德原理或平衡条件。",
            ),
            KpRule(
                keywords = listOf("化学方程式", "配平", "反应物", "生成物"),
                title = "化学方程式配平",
                aspect = "化学方程式",
                subject = "化学",
                detail = "先写对化学式，再用最小公倍数/观察法配平，最后检查原子守恒与条件符号。",
            ),
            KpRule(
                keywords = listOf("离子", "氧化", "还原", "物质的量", "mol"),
                title = "离子反应与氧化还原",
                aspect = "化学反应原理",
                subject = "化学",
                detail = "离子方程式注意拆分规则与电荷守恒；氧化还原先标化合价，再配电子守恒。",
            ),
            KpRule(
                keywords = listOf("文言文", "实词", "虚词", "翻译"),
                title = "文言实词、虚词与句式",
                aspect = "文言文阅读",
                subject = "语文",
                detail = "结合语境推断词义，注意词类活用、特殊句式；翻译做到字字落实、语序调整。",
            ),
            KpRule(
                keywords = listOf("古诗", "鉴赏", "意象", "作者情感"),
                title = "古诗词鉴赏",
                aspect = "古诗词鉴赏",
                subject = "语文",
                detail = "按“意象 → 手法 → 情感”答题；注意标题、注释与题材暗示。",
            ),
        )
    }
}
