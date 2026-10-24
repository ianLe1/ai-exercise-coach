package com.example.aiexercise.domain

/**
 * 视觉识别结果的排版整理：把 OCR 的逐行文本整理成带结构的 Markdown，
 * 供阅读与 AI 使用（题号、选项、大题标题、段落都能保留）。
 *
 * 纯 Kotlin，便于 JVM 单测；不改变原文用词与公式符号。
 */
object LayoutFormatter {

    private val QUESTION_START = Regex("^\\s*(\\d{1,3}\\s*[.、．)）]|[（(]\\s*\\d{1,3}\\s*[)）]|第\\s*[一二三四五六七八九十]+\\s*题)")
    private val OPTION_START = Regex("^\\s*([A-Fa-f]\\s*[.、．)）]|[（(]\\s*[A-Fa-f]\\s*[)）])")
    private val SECTION_START = Regex("^\\s*[一二三四五六七八九十]+\\s*[、.．]")
    private val OK_LINES = Regex("[\\s\\u00A0]+")

    fun toMarkdown(plainText: String): String {
        val lines = plainText
            .replace('\u00A0', ' ')
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""

        val out = StringBuilder()
        var previousWasQuestion = false
        for ((index, line) in lines.withIndex()) {
            val isNewQuestion = QUESTION_START.containsMatchIn(line)
            val isSection = SECTION_START.containsMatchIn(line)
            val isOption = OPTION_START.containsMatchIn(line)

            when {
                isSection -> {
                    if (out.isNotEmpty()) out.append('\n')
                    out.append("### ").append(line).append("\n\n")
                    previousWasQuestion = false
                }
                isNewQuestion -> {
                    if (out.isNotEmpty()) out.append('\n')
                    out.append("**").append(line).append("**\n\n")
                    previousWasQuestion = true
                }
                isOption -> {
                    out.append("- ").append(line).append('\n')
                    previousWasQuestion = false
                }
                else -> {
                    out.append(line)
                    // 题目/段落尾行的换行保留，避免把公式与下一行粘连
                    if (index == lines.lastIndex || previousWasQuestion) out.append('\n')
                    else out.append('\n')
                    previousWasQuestion = false
                }
            }
        }
        return out.toString()
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /** 纯文本：去掉 Markdown 标记，用于按题号分割等基于行的处理。 */
    fun toPlainText(markdown: String): String = markdown
        .replace(Regex("^#+\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("^\\s*-\\s+", RegexOption.MULTILINE), "")
        .replace("**", "")
        .lines()
        .map { it.replace(OK_LINES, " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    fun looksLikeMarkdown(text: String): Boolean =
        text.contains("**") || text.contains("\n#") || Regex("^\\s*-\\s+", RegexOption.MULTILINE).containsMatchIn(text) ||
            text.contains("\\(") || text.contains("$")
}
