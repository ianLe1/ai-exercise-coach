package com.example.aiexercise.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多页 OCR 文本 → 一道道题目（含大题归属与跨页合并）。 */
class QuestionSegmenterTest {

    @Test
    fun splitsNumberedQuestionsOnOnePage() {
        val page = """
            1. 已知 f(x)=x²-4x+3，求最小值。
            2. 解方程 x²-5x+6=0。
        """.trimIndent()
        val segments = QuestionSegmenter.segment(listOf(page))
        assertEquals(2, segments.size)
        assertTrue(segments[0].text.startsWith("1."))
        assertTrue(segments[1].text.contains("x²-5x+6=0"))
        assertEquals(1, segments[0].pageFrom)
        assertFalse(segments[0].crossPage)
    }

    @Test
    fun assignsSectionTitleToFollowingQuestions() {
        val page = """
            一、选择题
            1. 下面哪个是二次函数？
            2. 抛物线的对称轴是？
        """.trimIndent()
        val segments = QuestionSegmenter.segment(listOf(page))
        assertEquals(2, segments.size)
        assertEquals("一、选择题", segments[0].sectionTitle)
        assertEquals("一、选择题", segments[1].sectionTitle)
    }

    @Test
    fun mergesQuestionAcrossPageBreak() {
        val page1 = "1. 已知函数 f(x)=x²-4x+3，求 f(x) 在闭区间 [0,3] 上的最大值与"
        val page2 = "最小值。\n2. 第二题：求导数。"
        val segments = QuestionSegmenter.segment(listOf(page1, page2))
        assertEquals(2, segments.size)
        val first = segments[0]
        assertTrue("跨页题应把下一页内容拼进来", first.text.contains("最小值"))
        assertEquals(1, first.pageFrom)
        assertEquals(2, first.pageTo)
        assertTrue(first.crossPage)
        assertEquals(2, segments[1].pageFrom)
    }

    @Test
    fun fallsBackToSingleSegmentWhenNoNumbering() {
        val page = "已知函数 f(x)=x²-4x+3，求最小值。没有任何题号。"
        val segments = QuestionSegmenter.segment(listOf(page))
        assertEquals(1, segments.size)
        assertEquals(page, segments[0].text.trim())
        assertEquals(1, segments[0].pageFrom)
        assertEquals(1, segments[0].pageTo)
    }

    @Test
    fun multiplePagesWithoutNumberingDegradeToWholeDocument() {
        val segments = QuestionSegmenter.segment(listOf("第一页内容", "第二页内容"))
        assertEquals(1, segments.size)
        assertEquals(1, segments[0].pageFrom)
        assertEquals(2, segments[0].pageTo)
        assertTrue(segments[0].text.contains("第一页内容"))
        assertTrue(segments[0].text.contains("第二页内容"))
    }

    @Test
    fun blankPagesProduceNoSegments() {
        assertTrue(QuestionSegmenter.segment(emptyList()).isEmpty())
        assertTrue(QuestionSegmenter.segment(listOf("", "   ")).isEmpty())
    }

    @Test
    fun looksIncompleteDetectsTruncatedSentence() {
        assertTrue(QuestionSegmenter.looksIncomplete("求 f(x) 在闭区间上的最大值与"))
        assertFalse(QuestionSegmenter.looksIncomplete("求 f(x) 在闭区间上的最大值。"))
    }

    @Test
    fun skipsPageHeadersAndKeepsSectionTitle() {
        val page1 = """
            高二数学 · 课堂练习（第 1 页）
            一、解答题
            1. 已知函数 f(x)=x²-4x+3，求 f(x) 在闭区间 [0,3] 上的最大值与
        """.trimIndent()
        val page2 = """
            高二数学 · 课堂练习（第 2 页）
            最小值。
        """.trimIndent()
        val segments = QuestionSegmenter.segment(listOf(page1, page2))
        assertEquals(1, segments.size)
        val segment = segments[0]
        assertEquals("一、解答题", segment.sectionTitle)
        assertTrue("页眉不应混进题干", !segment.text.contains("课堂练习"))
        assertTrue(segment.text.contains("最小值"))
        assertEquals(2, segment.pageTo)
    }

    @Test
    fun sectionHeadingsWithCommaStillRecognized() {
        val page = """
            二，填空题
            1. 填空：1+1=___。
        """.trimIndent()
        val segments = QuestionSegmenter.segment(listOf(page))
        assertEquals(1, segments.size)
        assertEquals("二，填空题", segments[0].sectionTitle)
    }

    @Test
    fun sectionTitleSurvivesWhenOcrMergesHeaderAndHeading() {
        val page = """
            高二数学 · 课堂练习（第 1 页） 一、解答题
            1. 已知函数 f(x)=x²-4x+3，求最小值。
        """.trimIndent()
        val segments = QuestionSegmenter.segment(listOf(page))
        assertEquals(1, segments.size)
        assertEquals("一、解答题", segments[0].sectionTitle)
        assertTrue(!segments[0].text.contains("课堂练习"))
    }

    @Test
    fun ocrNoiseBeforeSectionHeadingStillRecognized() {
        // ML Kit 有时把“一、”识别成竖线
        val page = """
            高二数学·课堂练习(第1页)
            |解答题
            1. 已知函数 f(x)=x²-4x+3，求最小值。
        """.trimIndent()
        val segments = QuestionSegmenter.segment(listOf(page))
        assertEquals(1, segments.size)
        assertEquals("解答题", segments[0].sectionTitle)
    }
}
