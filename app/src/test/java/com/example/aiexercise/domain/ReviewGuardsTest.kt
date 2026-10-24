package com.example.aiexercise.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 订正质量守卫与作业一致性守卫（模块三的两条「特判」）。 */
class ReviewGuardsTest {

    @Test
    fun emptyCorrectionIsNotAllowedToAsk() {
        val check = CorrectionGuard.check("", "最大值 3，最小值 -1")
        assertEquals(CorrectionQuality.NONE, check.quality)
        assertFalse(check.mayAskGuided)
    }

    @Test
    fun lowSimilarityCorrectionBlocksGuidedQuestion() {
        val check = CorrectionGuard.check("不知道", "最大值 3，最小值 -1")
        assertEquals(CorrectionQuality.LOW, check.quality)
        assertFalse(check.mayAskGuided)
        assertTrue(check.message.contains("重新做一遍"))
    }

    @Test
    fun closeCorrectionAllowsGuidedQuestion() {
        val check = CorrectionGuard.check("最大值 3，最小值 -1", "最大值 3，最小值 -1")
        assertEquals(CorrectionQuality.HIGH, check.quality)
        assertTrue(check.mayAskGuided)
    }

    @Test
    fun numberMatchWinsEvenWhenWordingDiffers() {
        // 文字几乎不同，但关键数值一致 → 视为订正到位（避免误伤不同表述）
        val check = CorrectionGuard.check("所以答案是 3 和 -1", "最大值 3，最小值 -1")
        assertTrue(check.mayAskGuided)
        assertTrue(check.quality == CorrectionQuality.MEDIUM || check.quality == CorrectionQuality.HIGH)
    }

    @Test
    fun firstTimeAlwaysAsks() {
        val result = HomeworkGuard.decide(
            scannedQuestions = listOf("已知函数 f(x)=x²-4x+3，求闭区间最值"),
            knownQuestions = emptyList(),
            askedOnce = false,
        )
        assertEquals(HomeworkGuardAction.ASK, result.action)
        assertTrue(result.firstTime)
        assertTrue(result.message.contains("认真做"))
    }

    @Test
    fun mismatchAsksAgainWhenHomeworkLooksUnrelated() {
        val result = HomeworkGuard.decide(
            scannedQuestions = listOf("请默写《静夜思》并解释诗意", "计算 (-3)×(4-7)"),
            knownQuestions = listOf("已知函数 f(x)=x²-4x+3，求闭区间 [0,3] 上的最大值与最小值。"),
            askedOnce = true,
        )
        assertEquals(HomeworkGuardAction.ASK, result.action)
        assertFalse(result.firstTime)
        assertTrue(result.matchedRatio < HomeworkGuard.MISMATCH_RATIO)
    }

    @Test
    fun matchingHomeworkProceedsWithoutAsking() {
        val question = "已知函数 f(x)=x²-4x+3，求闭区间 [0,3] 上的最大值与最小值。"
        val result = HomeworkGuard.decide(
            scannedQuestions = listOf(question),
            knownQuestions = listOf(question),
            askedOnce = true,
        )
        assertEquals(HomeworkGuardAction.PROCEED, result.action)
        assertEquals(1.0, result.matchedRatio, 1e-9)
    }
}
