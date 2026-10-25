package com.example.aiexercise.domain

import com.example.aiexercise.data.model.OcrLine
import com.example.aiexercise.data.model.ScanPage

/**
 * 把分割出的题目映射回扫描件上的归一化矩形，用于「在扫描件上框题」。
 *
 * 纯 Kotlin：输入每题题干与各页 OCR 行（含归一化坐标），输出每题的框。
 * 做法：按题序在同页行里找题干前缀所在行作为起点，下一题起点前一行作为终点，
 * 取这些行的并集；找不到时按顺序游标兜底，仍失败则该题无框（null）。
 */
object QuestionBoxMapper {

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private const val PREFIX_LENGTH = 8

    fun map(
        questions: List<QuestionRef>,
        pages: List<ScanPage>,
    ): List<Box?> {
        val result = arrayOfNulls<Box>(questions.size)
        // 按页分组（跨页题记在起始页），保持原始顺序
        val grouped = questions.indices.groupBy { index ->
            questions[index].pageFrom.coerceAtLeast(1)
        }
        for ((pageNumber, indexes) in grouped) {
            val lines = pages.getOrNull(pageNumber - 1)?.lines.orEmpty()
            if (lines.isEmpty()) continue
            val normalized = lines.map { normalize(it.text) }
            var cursor = 0
            val starts = IntArray(indexes.size)
            indexes.forEachIndexed { slot, questionIndex ->
                val prefix = normalize(questions[questionIndex].questionText).take(PREFIX_LENGTH)
                var found = -1
                if (prefix.isNotEmpty()) {
                    for (lineIndex in cursor until normalized.size) {
                        if (normalized[lineIndex].contains(prefix)) {
                            found = lineIndex
                            break
                        }
                    }
                }
                if (found < 0) found = cursor.coerceIn(0, normalized.lastIndex)
                starts[slot] = found
                cursor = (found + 1).coerceAtMost(normalized.size)
            }
            val raw = arrayOfNulls<Box>(indexes.size)
            indexes.forEachIndexed { slot, questionIndex ->
                val start = starts[slot]
                val endExclusive = if (slot + 1 < indexes.size) {
                    starts[slot + 1]
                } else {
                    lines.size
                }
                val end = (endExclusive - 1).coerceIn(start, lines.lastIndex)
                val selected = lines.subList(start, end + 1)
                if (selected.isEmpty()) return@forEachIndexed
                // 横向直接按整行切条带（作业帮式）：OCR 行框只覆盖文字本身，
                // 太窄会让用户点不中，也让「框住这道题」看着不像那么回事。
                raw[slot] = Box(
                    left = 0.02f,
                    top = selected.minOf { it.top }.coerceIn(0f, 1f),
                    right = 0.98f,
                    bottom = selected.maxOf { it.bottom }.coerceIn(0f, 1f),
                )
            }
            // 相邻框之间的空隙平分给上下两题：整列都能点到，不会点空
            raw.forEachIndexed { slot, box ->
                if (box == null) return@forEachIndexed
                val previous = raw.getOrNull(slot - 1)
                val next = raw.getOrNull(slot + 1)
                val top = if (previous != null) (previous.bottom + box.top) / 2f else box.top
                val bottom = if (next != null) (box.bottom + next.top) / 2f else box.bottom
                result[indexes[slot]] = box.copy(top = minOf(top, box.top), bottom = maxOf(bottom, box.bottom))
            }
        }
        return result.toList()
    }

    /** 去掉空白与常见标点，便于跨 OCR 的空白差异匹配。 */
    private fun normalize(text: String): String = text.filter { it.isLetterOrDigit() || it.code > 0x2E80 }

    /** 题干与所在页的最小信息，避免 domain 依赖 ai 包的具体 DTO。 */
    data class QuestionRef(val questionText: String, val pageFrom: Int)
}

/** OCR 行与归一化矩形的便捷转换（UI 侧直接用）。 */
fun OcrLine.toBox(): QuestionBoxMapper.Box? {
    if (left >= right || top >= bottom) return null
    return QuestionBoxMapper.Box(left, top, right, bottom)
}
