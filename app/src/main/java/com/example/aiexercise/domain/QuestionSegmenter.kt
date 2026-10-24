package com.example.aiexercise.domain

/**
 * 扫描分割出来的一道题（可能跨页）。
 *
 * @param sectionTitle 所属大题（如“一、选择题”），没有识别到则为空
 * @param text 题干文本（跨页时已把下一页的续接内容拼进来）
 * @param pageFrom / pageTo 第几页开始、第几页结束（从 1 开始）
 */
data class QuestionSegment(
    val sectionTitle: String,
    val text: String,
    val pageFrom: Int,
    val pageTo: Int,
) {
    val crossPage: Boolean get() = pageTo > pageFrom
}

/**
 * 多页 OCR 文本 → 一道道题目的纯 Kotlin 分割器（离线可用，可 JVM 单测）。
 *
 * 规则：
 * 1. 识别大题标题（“一、选择题”“第二大题（填空）”等），作为后续题目所属大题；
 * 2. 识别题号（“1.”“1、”“第2题”等）作为一道题的开始；
 * 3. 小题（“（1）”“①”）不切分，留在当前题里；
 * 4. 页边界续接：上一题没写完、下一页开头不是新题/新大题，就把下一页开头并进上一题，标记跨页；
 * 5. 一页都切不出来时退化为“整份扫描 = 一道题”，避免丢内容。
 *
 * AI 分割（[com.example.aiexercise.ai.AiService.segmentQuestions]）失败时用它兜底。
 */
object QuestionSegmenter {

    private val QUESTION_START = Regex(
        """^\s*(?:第\s*\d{1,3}\s*题|\(?\d{1,3}\)?\s*[.、)）．]\s*|\d{1,3}\s+[^\d])""",
    )

    private val SECTION_START = Regex(
        """^\s*(?:[一二三四五六七八九十]+\s*[、.．,，:：]|第\s*[一二三四五六七八九十\d]+\s*(?:大题|部分)|[\s\W]*(?:选择|填空|判断|计算|解答|应用|实验|简答|阅读|作文)题(?:\s*[（(].*)?$)""",
    )

    /** 页眉/页码噪声：例如“高二数学 · 课堂练习（第 2 页）”“- 3 -”。 */
    private val PAGE_HEADER = Regex("""(第\s*\d{1,3}\s*页|-\s*\d{1,3}\s*-)""")

    private val ANSWER_TAIL = Regex("""[。．.！？!?；;：:"'”）)】\]]\s*$""")

    fun segment(pages: List<String>): List<QuestionSegment> {
        val cleaned = pages.map { it.replace("\r\n", "\n").replace('\r', '\n') }
        if (cleaned.all { it.isBlank() }) return emptyList()

        val segments = mutableListOf<QuestionSegment>()
        var currentText = StringBuilder()
        var currentSection = ""
        var pageFrom = 1
        var pageTo = 1
        var hasOpen = false
        var questionCountInSection = 0

        fun flush() {
            val text = currentText.toString().trim()
            if (text.isNotEmpty()) {
                segments += QuestionSegment(currentSection, text, pageFrom, pageTo)
            }
            currentText = StringBuilder()
            hasOpen = false
        }

        cleaned.forEachIndexed { pageIdx, pageText ->
            val pageNo = pageIdx + 1
            val lines = pageText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            for (rawLine in lines) {
                // 页眉、页码不进入题干，也不当作大题标题；
                // 若 OCR 把页眉和正文并成一行，则截掉页眉部分、保留后面的正文。
                val line = stripPageHeader(rawLine)
                if (line.isEmpty() || line.all { it.isDigit() }) continue
                val isQuestionStart = QUESTION_START.containsMatchIn(line)
                val isSectionStart = !isQuestionStart && SECTION_START.containsMatchIn(line) && line.length <= 40

                if (isQuestionStart) {
                    flush()
                    hasOpen = true
                    pageFrom = pageNo
                    pageTo = pageNo
                    questionCountInSection += 1
                    currentText.append(line.trim())
                } else if (isSectionStart) {
                    flush()
                    currentSection = line.trim()
                        .trimStart('|', '—', '-', '·', '、', '，', ',', '.', ':', '：', ' ')
                    questionCountInSection = 0
                } else if (hasOpen) {
                    // 当前题还没结束：这一行是续写（含小题、跨页续接）
                    currentText.append('\n').append(line.trim())
                    pageTo = pageNo
                } else {
                    // 第一个题号之前的标题/姓名栏等内容：不进入题干
                }
            }
        }
        flush()

        if (segments.isEmpty()) {
            val all = cleaned.joinToString("\n") { it.trim() }.trim()
            if (all.isEmpty()) return emptyList()
            return listOf(QuestionSegment("", all, 1, cleaned.size))
        }
        return segments
    }

    /** 去掉页眉/页码片段；若该行只有页眉则返回空串。 */
    private fun stripPageHeader(line: String): String {
        val match = PAGE_HEADER.find(line) ?: return line.trim()
        val after = line.substring(match.range.last + 1)
            .trim()
            .trim(' ', '·', '-', '—', ':', '：', '(', ')', '（', '）', '.', '、', '，', ',')
        return after
    }

    /** 页面文本是否像是“没写完”（用于给 UI/日志提示跨页合并的原因）。 */
    fun looksIncomplete(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isNotEmpty() && !ANSWER_TAIL.containsMatchIn(trimmed)
    }
}
