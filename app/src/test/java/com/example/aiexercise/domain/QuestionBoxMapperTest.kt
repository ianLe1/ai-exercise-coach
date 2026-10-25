package com.example.aiexercise.domain

import com.example.aiexercise.data.model.OcrLine
import com.example.aiexercise.data.model.ScanPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QuestionBoxMapperTest {

    private fun line(text: String, top: Float, bottom: Float, left: Float = 0.05f, right: Float = 0.95f) =
        OcrLine(text = text, left = left, top = top, right = right, bottom = bottom)

    private fun page(index: Int, lines: List<OcrLine>) =
        ScanPage(id = "p$index", index = index, imagePath = "/tmp/$index.png", lines = lines)

    @Test
    fun mapsTwoQuestionsOnSamePage() {
        val pageLines = listOf(
            line("1. 求 x 的值 2x + 3 = 7", 0.05f, 0.10f),
            line("A. 1  B. 2  C. 3  D. 4", 0.11f, 0.15f),
            line("2. 计算 3 + 4 等于多少", 0.30f, 0.35f),
            line("答：7", 0.36f, 0.40f),
        )
        val boxes = QuestionBoxMapper.map(
            questions = listOf(
                QuestionBoxMapper.QuestionRef("1. 求 x 的值 2x + 3 = 7", 1),
                QuestionBoxMapper.QuestionRef("2. 计算 3 + 4 等于多少", 1),
            ),
            pages = listOf(page(1, pageLines)),
        )
        assertEquals(2, boxes.size)
        val first = boxes[0]
        assertNotNull(first)
        assertEquals(0.05f, first!!.top, 0.001f)
        // 横向为整行条带，方便点击
        assertEquals(0.02f, first.left, 0.001f)
        assertEquals(0.98f, first.right, 0.001f)
        // 第一题与第二题之间的空隙平分：0.15 和 0.30 的中点 0.225
        assertEquals(0.225f, first.bottom, 0.001f)
        val second = boxes[1]
        assertNotNull(second)
        assertEquals(0.225f, second!!.top, 0.001f)
        assertEquals(0.40f, second.bottom, 0.001f)
    }

    @Test
    fun fallsBackToSequentialCursorWhenTextMismatch() {
        val pageLines = listOf(
            line("模糊的第一行", 0.10f, 0.14f),
            line("模糊的第二行", 0.20f, 0.24f),
        )
        val boxes = QuestionBoxMapper.map(
            questions = listOf(
                QuestionBoxMapper.QuestionRef("完全对不上的题干", 1),
                QuestionBoxMapper.QuestionRef("另一段对不上的题干", 1),
            ),
            pages = listOf(page(1, pageLines)),
        )
        assertEquals(2, boxes.size)
        assertNotNull(boxes[0])
        assertNotNull(boxes[1])
        // 顺序兜底：第一题至少覆盖第一行，第二题从后面的行开始（间隙 0.14–0.20 平分为 0.17）
        assertEquals(0.10f, boxes[0]!!.top, 0.001f)
        assertEquals(0.17f, boxes[1]!!.top, 0.001f)
    }

    @Test
    fun returnsNullWhenPageHasNoLines() {
        val boxes = QuestionBoxMapper.map(
            questions = listOf(QuestionBoxMapper.QuestionRef("1. 题目", 1)),
            pages = listOf(page(1, emptyList())),
        )
        assertEquals(1, boxes.size)
        assertNull(boxes[0])
    }

    @Test
    fun pageOutOfRangeIsIgnored() {
        val boxes = QuestionBoxMapper.map(
            questions = listOf(QuestionBoxMapper.QuestionRef("1. 题目", 5)),
            pages = listOf(page(1, listOf(line("1. 题目", 0.1f, 0.2f)))),
        )
        assertNull(boxes[0])
    }

    @Test
    fun mapsQuestionsAcrossPagesIndependently() {
        val pages = listOf(
            page(1, listOf(line("1. 第一页的题", 0.10f, 0.20f))),
            page(2, listOf(line("2. 第二页的题", 0.30f, 0.45f))),
        )
        val boxes = QuestionBoxMapper.map(
            questions = listOf(
                QuestionBoxMapper.QuestionRef("1. 第一页的题", 1),
                QuestionBoxMapper.QuestionRef("2. 第二页的题", 2),
            ),
            pages = pages,
        )
        assertEquals(0.10f, boxes[0]!!.top, 0.001f)
        assertEquals(0.30f, boxes[1]!!.top, 0.001f)
        assertEquals(0.45f, boxes[1]!!.bottom, 0.001f)
    }
}
