package com.example.aiexercise.domain

import com.example.aiexercise.data.model.PaperQuestion
import kotlin.math.roundToInt

/**
 * 结构化试卷规范：强制 AI 产出的试卷符合「正规整卷」结构。
 *
 * 规则（不满足则归一化；归一化后仍不合规 → 生成失败，让上层兜底）：
 * 1. 只有三大题：一、选择题；二、填空题；三、解答题；
 * 2. 每题必须属于某一个 section，且分值 > 0；
 * 3. 全卷合计 100 分；
 * 4. 选择题必须有至少 2 个选项。
 */
object PaperStructure {

    const val CHOICE = "一、选择题"
    const val FILL = "二、填空题"
    const val SOLUTION = "三、解答题"

    const val TOTAL_SCORE = 100
    const val DURATION_MINUTES = 90

    val sections: List<String> = listOf(CHOICE, FILL, SOLUTION)

    fun enforce(questions: List<PaperQuestion>): List<PaperQuestion>? {
        if (questions.isEmpty()) return null
        val normalized = questions.mapIndexed { index, question ->
            val section = canonicalSection(question, index, questions.size)
            question.copy(section = section)
        }
        val scored = assignScores(normalized)
        if (scored.any { it.score <= 0 }) return null
        if (scored.sumOf { it.score } != TOTAL_SCORE) return null
        if (scored.any { it.section == CHOICE && it.options.size < 2 }) return null
        return scored
    }

    /** 把 AI 的 section 名归一化到三大题之一；缺失/不规范时按题型推断。 */
    fun canonicalSection(question: PaperQuestion, index: Int, total: Int): String {
        val raw = question.section.trim()
        when {
            raw.contains("选择") -> return CHOICE
            raw.contains("填空") -> return FILL
            raw.contains("解答") || raw.contains("计算") || raw.contains("证明") || raw.contains("应用") -> return SOLUTION
        }
        if (question.type.contains("choice", ignoreCase = true) || question.options.isNotEmpty()) return CHOICE
        if (question.type.contains("fill", ignoreCase = true) || question.answerMarkdown.contains("___")) return FILL
        if (question.type.contains("recall", ignoreCase = true)) return FILL
        // 无任何线索：按整卷比例落位（前 40% 选择、次 30% 填空、其余解答）
        val ratio = (index + 1).toDouble() / total
        return when {
            ratio <= 0.4 -> CHOICE
            ratio <= 0.7 -> FILL
            else -> SOLUTION
        }
    }

    /** 分值：AI 给的分值合计正好 100 就保留，否则按题型权重重新分配并修正到 100。 */
    private fun assignScores(questions: List<PaperQuestion>): List<PaperQuestion> {
        val given = questions.sumOf { it.score.coerceAtLeast(0) }
        if (questions.all { it.score > 0 } && given == TOTAL_SCORE) return questions

        val weights = questions.map { weightOf(it.section) }
        val weightSum = weights.sum().coerceAtLeast(1)
        val raw = weights.map { 100.0 * it / weightSum }
        val scores = raw.map { maxOf(1, it.roundToInt()) }.toMutableList()
        var diff = TOTAL_SCORE - scores.sum()
        var guard = 0
        var cursor = scores.lastIndex
        while (diff != 0 && guard < 1000) {
            if (diff > 0) {
                scores[cursor] += 1
                diff -= 1
            } else if (scores[cursor] > 1) {
                scores[cursor] -= 1
                diff += 1
            }
            cursor = if (cursor == 0) scores.lastIndex else cursor - 1
            guard++
        }
        return questions.mapIndexed { index, question -> question.copy(score = scores[index]) }
    }

    private fun weightOf(section: String): Int = when (section) {
        CHOICE -> 4
        FILL -> 4
        else -> 8
    }

    /** 按 section 分组（保持三大题顺序，未知 section 归入解答题）。 */
    fun groupBySection(questions: List<PaperQuestion>): List<Pair<String, List<PaperQuestion>>> {
        val groups = linkedMapOf<String, MutableList<PaperQuestion>>()
        sections.forEach { groups[it] = mutableListOf() }
        questions.forEach { question ->
            val key = if (question.section in sections) question.section else SOLUTION
            groups.getOrPut(key) { mutableListOf() }.add(question)
        }
        return groups.filter { it.value.isNotEmpty() }.map { it.key to it.value.toList() }
    }
}
