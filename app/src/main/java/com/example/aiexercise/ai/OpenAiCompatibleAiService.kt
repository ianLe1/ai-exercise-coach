package com.example.aiexercise.ai

import com.example.aiexercise.data.model.KnowledgePoint
import com.example.aiexercise.data.model.SolutionDigest
import com.example.aiexercise.domain.AppError
import com.example.aiexercise.domain.ForgettingCurve
import com.example.aiexercise.domain.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** 云端 AI 配置（「设置」页可改；对应 OpenAI 兼容的 /chat/completions 协议）。 */
data class AiRemoteConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val temperature: Double = 0.2,
    val timeoutMs: Int = 60_000,
)

/**
 * OpenAI 兼容接口实现（可替换点）。
 *
 * 兼容 OpenAI / DeepSeek / Moonshot / 通义 / Ollama / 本地 vLLM 等所有
 * `POST {baseUrl}/chat/completions` 协议的服务。
 *
 * 稳定性处理：
 * - 返回内容先剥离 ```json 代码块、再截取首尾花括号解析；
 * - 解析/校验失败自动重试 1 次；
 * - 4xx（除 429）不重试，直接返回可读错误。
 */
class OpenAiCompatibleAiService(
    private val config: AiRemoteConfig,
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val maxRetries: Int = 1,
) : AiService {

    override val id: String = "openai-compatible"

    override val displayName: String =
        "云端 AI（${config.model.ifBlank { "未配置模型" }}）"

    override val isOffline: Boolean = false

    /* ------------------------------- 三个任务 ------------------------------- */

    override suspend fun analyze(request: AnalyzeRequest): Outcome<KpGenerationPayload> =
        callJson(
            taskName = "知识点生成",
            systemPrompt = KNOWLEDGE_SYSTEM_PROMPT,
            userPrompt = buildKnowledgePointPrompt(request),
            parse = { raw ->
                AiJson.parseKnowledgePoints(raw)?.takeIf { payload ->
                    payload.knowledgePoints.any { it.title.isNotBlank() }
                }
            },
        )

    override suspend fun distillSolution(request: SolutionFilterRequest): Outcome<SolutionDigest> =
        callJson(
            taskName = "网络解析筛选",
            systemPrompt = DISTILL_SYSTEM_PROMPT,
            userPrompt = buildSolutionFilterPrompt(request),
            parse = { raw ->
                AiJson.parseSolutionDigest(raw)?.let { payload ->
                    AiPayloadValidator.validateSolutionDigest(payload).let {
                        (it as? Outcome.Success)?.value
                    }
                }
            },
        )

    override suspend fun grade(request: GradeRequest): Outcome<GradePayload> =
        callJson(
            taskName = "批改",
            systemPrompt = GRADE_SYSTEM_PROMPT,
            userPrompt = buildGradePrompt(request),
            parse = { raw -> AiJson.parseGrade(raw)?.takeIf { it.analysis.isNotBlank() || it.errorSteps.isNotEmpty() } },
        )

    override suspend fun generatePaper(request: PaperRequest): Outcome<PaperPayload> =
        callJson(
            taskName = "练习卷生成",
            systemPrompt = PAPER_SYSTEM_PROMPT,
            userPrompt = buildPaperPrompt(request),
            parse = { raw ->
                AiJson.parsePaper(raw)?.takeIf { payload ->
                    payload.questions.isNotEmpty() && payload.questions.all {
                        it.questionMarkdown.isNotBlank() && it.answerMarkdown.isNotBlank()
                    }
                }
            },
        )

    override suspend fun segmentQuestions(request: SegmentRequest): Outcome<SegmentPayload> =
        callJson(
            taskName = "扫描题目分割",
            systemPrompt = SEGMENT_SYSTEM_PROMPT,
            userPrompt = buildSegmentPrompt(request),
            parse = { raw ->
                AiJson.parseSegments(raw)?.let { payload ->
                    (AiPayloadValidator.validateSegments(payload, request.pageCount) as? Outcome.Success)?.value
                }
            },
        )


    override suspend fun readAnswers(request: AnswerReadRequest): Outcome<AnswerReadPayload> =
        callJson(
            taskName = "答题卡识别",
            systemPrompt = ANSWER_READ_SYSTEM_PROMPT,
            userPrompt = buildAnswerReadPrompt(request),
            parse = { raw ->
                AiJson.parseAnswers(raw)?.let { payload ->
                    (AiPayloadValidator.validateAnswers(payload, request.paperQuestions.size) as? Outcome.Success)?.value
                }
            },
        )

    override suspend fun solve(request: SolveRequest): Outcome<SolvePayload> =
        callJson(
            taskName = "生成参考答案",
            systemPrompt = SOLVE_SYSTEM_PROMPT,
            userPrompt = buildSolvePrompt(request),
            parse = { raw ->
                AiJson.parseSolution(raw)?.let { payload ->
                    (AiPayloadValidator.validateSolution(payload) as? Outcome.Success)?.value
                }
            },
        )

    override suspend fun guidedStep(request: GuidedRequest): Outcome<GuidedPayload> =
        callJson(
            taskName = "引导式提问",
            systemPrompt = GUIDED_SYSTEM_PROMPT,
            userPrompt = buildGuidedPrompt(request),
            parse = { raw ->
                AiJson.parseGuided(raw)?.let { payload ->
                    (AiPayloadValidator.validateGuided(payload) as? Outcome.Success)?.value
                }
            },
        )

    /* ------------------------------ HTTP 调用 ------------------------------ */

    private suspend fun <T> callJson(
        taskName: String,
        systemPrompt: String,
        userPrompt: String,
        parse: (String) -> T?,
    ): Outcome<T> {
        val configurationError = validateConfiguration()
        if (configurationError != null) return Outcome.Failure(configurationError)
        val url = chatCompletionsUrl(config.baseUrl)
        val body = AiJson.json.encodeToString(
            ChatRequestBody.serializer(),
            ChatRequestBody(
                model = config.model,
                temperature = config.temperature,
                messages = listOf(
                    ChatMessage(role = "system", content = systemPrompt),
                    ChatMessage(role = "user", content = userPrompt),
                ),
            ),
        )
        val headers = buildMap {
            put("Content-Type", "application/json")
            if (config.apiKey.isNotBlank()) put("Authorization", "Bearer ${config.apiKey}")
        }
        var lastError = "未知错误"
        repeat(maxRetries + 1) { attempt ->
            try {
                val result = transport.postJson(url, headers, body, config.timeoutMs)
                if (result.statusCode !in 200..299) {
                    val detail = extractErrorMessage(result.body)
                    lastError = "HTTP ${result.statusCode}${if (detail.isNotBlank()) "：$detail" else ""}"
                    val nonRetryable = result.statusCode in 400..499 && result.statusCode != 429
                    if (nonRetryable) return Outcome.Failure(AppError.AiNetworkError("$taskName 失败 $lastError"))
                } else {
                    val content = extractContent(result.body)
                    if (content.isNullOrBlank()) {
                        lastError = "响应缺少 choices[0].message.content"
                    } else {
                        val parsed = parse(content)
                        if (parsed != null) return Outcome.Success(parsed)
                        lastError = "模型返回内容不符合约定 JSON 结构（第 ${attempt + 1}/${maxRetries + 1} 次）"
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                lastError = exception.message ?: exception.javaClass.simpleName
            }
            if (attempt < maxRetries) delay(RETRY_DELAY_MS)
        }
        return Outcome.Failure(AppError.AiFormatError("$taskName 失败：$lastError"))
    }

    private fun validateConfiguration(): AppError? {
        if (config.baseUrl.isBlank() || config.model.isBlank()) {
            return AppError.AiNotConfigured("baseUrl/model 为空")
        }
        if (config.apiKey.isBlank() && !isLocalEndpoint(config.baseUrl)) {
            return AppError.AiNotConfigured("apiKey 为空")
        }
        return null
    }

    private fun isLocalEndpoint(baseUrl: String): Boolean =
        listOf("localhost", "127.0.0.1", "10.0.2.2", "0.0.0.0").any { baseUrl.contains(it) }

    private fun chatCompletionsUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        return if (normalized.endsWith("/chat/completions")) normalized else "$normalized/chat/completions"
    }

    private fun extractContent(body: String): String? {
        val response = runCatching {
            AiJson.json.decodeFromString(ChatResponseBody.serializer(), body)
        }.getOrNull() ?: return null
        return response.choices.firstOrNull()?.message?.content
    }

    private fun extractErrorMessage(body: String): String {
        if (body.isBlank()) return ""
        val parsed = runCatching {
            AiJson.json.decodeFromString(ChatResponseBody.serializer(), body)
        }.getOrNull()
        return parsed?.error?.message?.take(200) ?: body.take(200)
    }

    /* ------------------------------- Prompt ------------------------------- */

    private fun buildKnowledgePointPrompt(request: AnalyzeRequest): String {
        val asker = request.asker
        return buildString {
            appendLine("请阅读下面这道习题的信息，输出该提问者「不会的知识点」。")
            appendLine()
            appendLine("【提问者】")
            appendLine("- 姓名/称呼：${asker.name.ifBlank { "我" }}")
            appendLine("- 学段年级：${asker.gradeLevel.ifBlank { "未填写" }}")
            if (asker.goal.isNotBlank()) appendLine("- 学习目标：${asker.goal}")
            appendLine()
            appendLine("【习题】")
            appendLine(
                clip(
                    request.questionMarkdown.ifBlank { request.questionText },
                    3000,
                ),
            )
            appendLine()
            appendLine("【参考答案】")
            appendLine(request.referenceAnswer.ifBlank { "（未提供）" }.let { clip(it, 2000) })
            appendLine()
            appendLine("【提问者的作答】")
            appendLine(request.studentAnswer.ifBlank { "（未提供）" }.let { clip(it, 1000) })
            if (request.subjectHint.isNotBlank()) {
                appendLine()
                appendLine("【学科提示】${request.subjectHint}")
            }
            request.research?.let { research ->
                appendLine()
                appendLine("【自动检索到的本题解析（已由 AI 从网络结果中筛选）】")
                appendLine(
                    if (research.digest.relevant) clip(research.digest.solutionMarkdown, 2500)
                    else "（本次未筛出与本题匹配的解析）",
                )
                if (research.kbCitations.isNotEmpty()) {
                    appendLine()
                    appendLine("【教材 / 教师用书知识库依据】")
                    research.kbCitations.take(4).forEach { citation ->
                        appendLine("- 《${citation.title}》${citation.citationLabel()}：${clip(citation.snippet, 300)}")
                    }
                }
                appendLine()
                appendLine("要求：classify 时以上检索内容只作为参考证据，不得照抄成知识点标题；若检索内容与题目矛盾，以题目与参考答案为准。")
            }
            appendLine()
            appendLine("要求：")
            appendLine("1. 只输出 JSON，不要输出 JSON 之外的任何文字；")
            appendLine("2. JSON 结构：")
            appendLine(
                """
                {
                  "analysis": "一段简要分析：提问者为什么不会、卡在哪一步",
                  "subject": "学科，例如 数学",
                  "knowledgePoints": [
                    {
                      "title": "知识点名称（不是题目复述，5~20 字）",
                      "aspect": "所属方面，例如 二次函数、时态、电路",
                      "subject": "学科",
                      "detail": "该知识点的核心定义/公式/步骤（可执行的复习要点）",
                      "reason": "为什么判断提问者不会它（引用其作答/答案依据）"
                    }
                  ]
                }
                """.trimIndent(),
            )
            append("3. knowledgePoints 输出 1~3 条，按重要性从高到低排序；aspect 是之后出练习卷的维度，务必具体。")
        }
    }

    private fun buildSolutionFilterPrompt(request: SolutionFilterRequest): String = buildString {
        appendLine("下面是从网络搜索到的若干条结果，请判断哪些内容真正是「这道题」的解析，并整理成一段解析。")
        appendLine()
        appendLine("【题目】")
        appendLine(clip(request.questionText, 2000))
        appendLine()
        appendLine("【参考答案】")
        appendLine(request.referenceAnswer.ifBlank { "（未提供，可依据题目本身判断）" }.let { clip(it, 1500) })
        appendLine()
        appendLine("【网络搜索结果】")
        request.webHits.forEachIndexed { index, hit ->
            appendLine("命中 ${index + 1}：${hit.title}")
            appendLine("URL：${hit.url}")
            appendLine(clip(hit.snippet, 600))
            appendLine()
        }
        appendLine("要求：")
        appendLine("1. 只输出 JSON，不要输出任何解释文字；")
        appendLine("2. 过滤规则：与本题考点/答案一致的才保留；广告、下载站、同类不同题、无实质解析的内容一律剔除；")
        appendLine("3. 若没有任何结果真正解析了这道题，relevant 输出 false 且 solutionMarkdown 留空，不要硬编解析；")
        appendLine("4. JSON 结构：")
        appendLine(
            """
            {
              "relevant": true,
              "solutionMarkdown": "整理后的本题解析（Markdown，可分步骤，仅使用搜索结果中可信的内容）",
              "confidence": 0.0,
              "matchedTitles": ["被采纳的来源标题"]
            }
            """.trimIndent(),
        )
    }

    private fun buildGradePrompt(request: GradeRequest): String {
        val asker = request.asker
        return buildString {
            appendLine("请批改下面这道习题的作答。")
            appendLine()
            appendLine("【提问者】${asker.name.ifBlank { "我" }}（${asker.gradeLevel.ifBlank { "未填写" }}）")
            appendLine()
            appendLine("【习题】")
            appendLine(clip(request.questionText, 3000))
            appendLine()
            appendLine("【参考答案】")
            appendLine(clip(request.referenceAnswer, 3000))
            appendLine()
            appendLine("【提问者的作答】")
            appendLine(clip(request.studentAnswer, 3000))
            appendLine()
            appendLine("要求：只输出 JSON，不要输出 JSON 之外的任何文字；结构如下：")
            appendLine(
                """
                {
                  "score": 0到100的整数,
                  "correct": true 或 false,
                  "correctAnswer": "规范答案（可以比参考答案更完整）",
                  "analysis": "Markdown 格式的批改解析，包含结论、关键步骤点评、错因与改进建议",
                  "errorSteps": ["出错步骤 1", "出错步骤 2"],
                  "relatedKnowledgePoints": ["与本次作答相关的不会知识点名称，1~3 条"]
                }
                """.trimIndent(),
            )
            append("注意：analysis 使用 Markdown（标题、列表、粗体），不要把 JSON 包在代码块里。")
        }
    }

    private fun buildSegmentPrompt(request: SegmentRequest): String = buildString {
        val pages = if (request.pages.isNotEmpty()) request.pages else listOf(request.pagesText)
        appendLine("下面是一次多页扫描得到的逐页 OCR 文本，请把它分割成一道道题目。")
        appendLine("要求：")
        appendLine("1. 只输出 JSON，不要输出任何解释；")
        appendLine("2. 按“大题 → 题”切分；小题（（1）（2）等）留在所属题目里，不要单独切开；")
        appendLine("3. 一道题如果跨页（上一页没写完、下一页开头是它的续写），必须合并成一条，pageFrom/pageTo 填对应页码；")
        appendLine("4. 页眉、姓名/班级栏、页码等噪声不要放进题干；")
        appendLine("5. JSON 结构：")
        appendLine(
            """
            {
              "questions": [
                {
                  "sectionTitle": "一、选择题",
                  "questionText": "题干原文（含小题；可保留题号）",
                  "pageFrom": 1,
                  "pageTo": 2,
                  "referenceAnswer": "如果 OCR 里有参考答案就填，没有就留空",
                  "studentAnswer": "如果 OCR 里有学生作答就填，没有就留空"
                }
              ],
              "note": "分割说明（可选）"
            }
            """.trimIndent(),
        )
        appendLine()
        appendLine("【学科提示】${request.subjectHint.ifBlank { "未提供" }}")
        appendLine("【页码总数】${request.pageCount}")
        appendLine()
        pages.forEachIndexed { index, text ->
            appendLine("=== 第 ${index + 1} 页 ===")
            appendLine(text.take(6000))
            appendLine()
        }
    }

    private fun buildPaperPrompt(request: PaperRequest): String {
        val asker = request.asker
        val now = System.currentTimeMillis()
        val review = request.reviewPoints.joinToString("\n") { point ->
            "- title=${point.title}；aspect=${point.aspect}；掌握度=${"%.2f".format(point.mastery)}（已掌握，用来穿插巩固）"
        }
        val points = request.knowledgePoints.joinToString("\n") { point ->
            "- id=${point.id}；title=${point.title}；aspect=${point.aspect}；" +
                "掌握度=${"%.2f".format(point.mastery)}；复习紧迫度=${"%.2f".format(ForgettingCurve.urgency(point.timer, point.mastery, now))}；" +
                "复习要点=${point.detail.ifBlank { "无" }}"
        }
        return buildString {
            appendLine("请为下面这位学生生成一整张**正规试卷**（不是小练习）：总分 100 分、考试时间 90 分钟，")
            appendLine()
            appendLine("【学生】${asker.name.ifBlank { "我" }}（${asker.gradeLevel.ifBlank { "未填写" }}），学习目标：${asker.goal.ifBlank { "未填写" }}")
            appendLine()
            appendLine("【练习卷方面】${request.aspect}")
            appendLine("【题量】${request.count} 题，按正规试卷分大题：一、选择题（${(request.count * 8 + 19) / 20} 题，每题 4 分）；二、填空题（${(request.count * 6 + 19) / 20} 题，每题 4 分）；三、解答题（其余题，每题 8~12 分）")
            appendLine()
            appendLine("【必考：不会的知识点（已按遗忘曲线紧迫度排序，约占 60%）】")
            appendLine(points)
            if (review.isNotBlank()) {
                appendLine()
                appendLine("【穿插：已经掌握的知识点（约占 20%，用于巩固、保证整卷完整）】")
                appendLine(review)
            }
            appendLine()
            appendLine("【新知识点（约占 ${request.newPointCount} 题）】")
            appendLine("补充同年级常考、但上面列表里没有的新知识点题目（不要与上面知识点重复）；这些题的 knowledgePointId 留空，knowledgePointTitle 写新知识点名称。")
            appendLine()
            appendLine("要求：")
            appendLine("1. 只输出 JSON，不要输出 JSON 之外的任何文字；")
            appendLine("2. 题面 questionMarkdown 必须使用 Markdown（标题、列表、粗体；公式可写 LaTeX，如 \$x^2\$）；")
            appendLine("2.1 每题必须给出 section（一、选择题 / 二、填空题 / 三、解答题）与 score（本题分值，全卷合计必须正好 100 分）；")
            appendLine("3. 每道题必须给出 answerMarkdown 与 explanationMarkdown，选择题必须给出 4 个选项；")
            appendLine("3.1 answerSheet 是“答题卡结构”，只描述答题区，不含题干：数字=答题区行数，数组=选择题选项，字符串=填空题模板（用 ___ 表示填空位置）；必须有大题与题，小题可有可无；")
            appendLine("4. 题目要覆盖上面的知识点，优先考察最紧迫的；难度混合 easy/medium/hard；")
            appendLine("4.1 这是一整张试卷：questionMarkdown 里要带试卷标题、考试时间/总分说明，并在每道题前标出「一、选择题」「二、填空题」「三、解答题」分节与小问；")
            appendLine("4.3 整卷必须完整：约 60% 题覆盖「不会的知识点」、约 20% 题用「已掌握的知识点」巩固、其余为「新知识点」；不要全部考错题；")
            appendLine("4.2 选择题必须 4 个选项 A/B/C/D；填空题用 ___ 标空；解答题要有完整设问与求解过程分步；")
            appendLine("5. JSON 结构：")
            appendLine(
                """
                {
                  "title": "练习卷标题",
                  "aspect": "方面",
                  "summary": "本卷说明（考了哪些知识点、为什么现在练）",
                  "questions": [
                    {
                      "id": "Q1",
                      "type": "choice 或 solution",
                      "section": "一、选择题 / 二、填空题 / 三、解答题（必须三选一）",
                      "score": 4,
                      "difficulty": "easy/medium/hard",
                      "questionMarkdown": "Markdown 题面",
                      "options": ["A. ...", "B. ..."],
                      "answerMarkdown": "答案",
                      "explanationMarkdown": "解析",
                      "knowledgePointId": "对应知识点 id",
                      "knowledgePointTitle": "对应知识点名称"
                    }
                  ],
                  "answerSheet": {
                    "第一大题": {
                      "第1题": 5,
                      "第2题": {
                        "第2题第1小题": ["选项A", "选项B", "选项C"],
                        "第2题第2小题": "填空题___"
                      }
                    }
                  }
                }
                """.trimIndent(),
            )
        }
    }

    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…（内容过长已截断）"

    @Serializable
    private data class ChatMessage(val role: String, val content: String)

    @Serializable
    private data class ChatRequestBody(
        val model: String,
        val temperature: Double,
        val messages: List<ChatMessage>,
        val stream: Boolean = false,
    )

    @Serializable
    private data class ChatChoice(val message: ChatMessage? = null)

    @Serializable
    private data class ChatErrorBody(val message: String = "")

    @Serializable
    private data class ChatResponseBody(
        val choices: List<ChatChoice> = emptyList(),
        val error: ChatErrorBody? = null,
    )


    private fun buildAnswerReadPrompt(request: AnswerReadRequest): String = buildString {
        appendLine("这是一份练习卷的答题卡扫描 OCR 文本。请按题号提取学生的手写作答：")
        appendLine("页数：${request.pagesText.size}")
        request.pagesText.forEachIndexed { i, page ->
            appendLine("=== 第 ${i + 1} 页 ===")
            appendLine(page.take(4000))
        }
        appendLine("题目清单（按顺序，供你对照题号）：")
        request.paperQuestions.forEachIndexed { i, q ->
            appendLine("第 ${i + 1} 题（id=${q.id}）：${q.questionMarkdown.take(120).replace("\n", " ")}")
        }
        append("只输出 JSON：{\"answers\":[{\"index\":1,\"questionHint\":\"题号或题干预览\",\"answerText\":\"学生作答原文\"}],\"note\":\"\"}")
    }

    private fun buildSolvePrompt(request: SolveRequest): String = buildString {
        appendLine("请解答下面这道题，给出可核对的参考答案。学生作答在纸上，不需要参考学生的写法。")
        appendLine("题目：${request.questionText.take(1000)}")
        if (request.subjectHint.isNotBlank()) appendLine("学科提示：${request.subjectHint}")
        appendLine("要求：参考答案要具体（数值/结论/关键式子），步骤清晰但不必长篇讲解。")
    }

    private fun buildGuidedPrompt(request: GuidedRequest): String = buildString {
        appendLine("学生做错了这道题，正在订正。请用引导式提问帮他一步步想出来，**不要直接给答案或完整解法**。")
        appendLine("题目：${request.questionText.take(800)}")
        if (request.referenceAnswer.isNotBlank()) appendLine("参考答案（仅供你判断，不要直接透露）：${request.referenceAnswer.take(600)}")
        if (request.studentAnswer.isNotBlank()) appendLine("学生当前作答：${request.studentAnswer.take(400)}")
        if (request.knowledgePointTitle.isNotBlank()) appendLine("关联知识点：${request.knowledgePointTitle}")
        appendLine("当前引导阶段：${request.stage}（共 3 步，第 4 步收尾）")
        if (request.exchanges.isNotEmpty()) {
            appendLine("历史对话：")
            request.exchanges.takeLast(8).forEachIndexed { i, exchange ->
                appendLine("${i + 1}. ${exchange.role}：${exchange.text.take(200)}")
            }
        }
        appendLine("要求：一次只问一个问题；语气简短；给一点提示但不点破关键答案；若学生已经说对关键步骤，feedback 里肯定他并进入下一步；第 3 步之后 resolved=true 做收尾总结。")
        append("只输出 JSON：{\"question\":\"下一步的引导问题\",\"feedback\":\"对上一句的简短点评\",\"resolved\":false,\"stage\":2}")
    }

    private companion object {
        const val RETRY_DELAY_MS = 350L

        val KNOWLEDGE_SYSTEM_PROMPT = """
            你是一位经验丰富的中学老师兼学习教练。你的任务是：根据一道习题、它的参考答案，
            以及提问者的作答，判断提问者“不会的知识点”，并输出严格的 JSON。
            你没有反问用户的机会；信息不足时按题意推断，并在 reason 中注明依据。
        """.trimIndent()

        val GRADE_SYSTEM_PROMPT = """
            你是一位严谨的阅卷老师。请逐步对照参考答案批改学生作答，给出 0~100 的分数、
            出错步骤与改进建议，并输出严格的 JSON。解析使用 Markdown。
        """.trimIndent()

        val DISTILL_SYSTEM_PROMPT = """
            你是一位严谨的教研老师。你的任务是从网络搜索结果里挑出真正讲解“这道题”的内容，
            剔除广告、下载站、只是同类但不同的题、以及没有实质解析的页面。
            只输出严格 JSON；没有匹配内容时如实返回 relevant=false，绝不编造解析。
        """.trimIndent()

        val SEGMENT_SYSTEM_PROMPT = """
            你是一位教研员，擅长把拍照/扫描得到的试卷 OCR 文本整理成一道道题目。
            必须处理跨页题目（上一页没写完、下一页续写要合并），去掉页眉页脚噪声，
            并按“大题 → 题 → 小题”的结构输出严格 JSON。
        """.trimIndent()

        val PAPER_SYSTEM_PROMPT = """
            你是一位命题老师。请根据学生“不会的知识点”及其遗忘曲线紧迫度，生成结构化练习卷，
            题面使用 Markdown，并输出严格的 JSON。题目必须可解、答案必须正确。
        """.trimIndent()
    }

        private val ANSWER_READ_SYSTEM_PROMPT =
            "你是一名批改练习卷的老师。根据答题卡的 OCR 文本，提取每个题号下学生的手写作答原文；" +
                "识别不出就跳过该题，不要编造。只输出 JSON，不要 Markdown 围栏。"

        private val SOLVE_SYSTEM_PROMPT =
            "你是理科/文科老师，负责为题目生成可核对的参考答案。只输出 JSON：" +
                "{\"referenceAnswer\":\"最终答案与关键式子\",\"stepsMarkdown\":\"关键步骤（Markdown）\",\"confidence\":0.0~1.0}。" +
                "不要输出多余文字，不要编造题目里没有的数据。"

        private val GUIDED_SYSTEM_PROMPT =
            "你是一名耐心的中学老师，用苏格拉底式提问引导学生自己想出解题步骤；" +
                "绝不直接给出答案或完整过程；一次只问一个问题；学生说对关键点时给予肯定。只输出 JSON。"
}
