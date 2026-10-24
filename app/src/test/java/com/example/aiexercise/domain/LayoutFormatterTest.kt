package com.example.aiexercise.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutFormatterTest {

    @Test
    fun questionNumbersBecomeBoldBlocksAndOptionsBecomeListItems() {
        val markdown = LayoutFormatter.toMarkdown(
            """
            1. 已知函数 f(x)=x²-4x+3，求最小值。
            A. -1
            B. 1
            （1）写出对称轴；
            """.trimIndent(),
        )
        assertTrue(markdown.contains("**1. 已知函数"))
        assertTrue(markdown.contains("- A. -1"))
        assertTrue(markdown.contains("- B. 1"))
        assertTrue(markdown.contains("（1）写出对称轴"))
    }

    @Test
    fun sectionHeadingsBecomeThirdLevelHeadings() {
        val markdown = LayoutFormatter.toMarkdown("一、选择题\n1. 计算 2+3 的值。")
        assertTrue(markdown.contains("### 一、选择题"))
        assertTrue(markdown.contains("**1. 计算 2+3 的值。**"))
    }

    @Test
    fun plainTextRoundTripKeepsLineOrder() {
        val plain = "1. 计算 2+3 的值。\nA. 5\nB. 6"
        val markdown = LayoutFormatter.toMarkdown(plain)
        assertEquals("1. 计算 2+3 的值。\nA. 5\nB. 6", LayoutFormatter.toPlainText(markdown))
    }

    @Test
    fun markdownDetection() {
        assertTrue(LayoutFormatter.looksLikeMarkdown("**1.** 题目"))
        assertTrue(LayoutFormatter.looksLikeMarkdown("- A. 选项"))
        assertTrue(LayoutFormatter.looksLikeMarkdown("公式 \$x^2\$"))
    }
}
