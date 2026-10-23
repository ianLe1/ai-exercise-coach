package com.example.aiexercise.domain

import com.example.aiexercise.data.model.ReviewTimer
import com.example.aiexercise.sampleKnowledgePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgePointRulesTest {

    private val now = 1_700_000_000_000L

    @Test
    fun dedupeKeyIgnoresPunctuationAndWidth() {
        assertEquals(
            KnowledgePointRules.dedupeKey("闭区间上的最值求法", "二次函数"),
            KnowledgePointRules.dedupeKey("闭区间上的最值求法。", "二次函数"),
        )
    }

    @Test
    fun findExistingReturnsSameKeyedPoint() {
        val point = sampleKnowledgePoint(now = now)
        val found = KnowledgePointRules.findExisting(listOf(point), "闭区间上的最值求法", "二次函数")
        assertNotNull(found)
        assertEquals(point.id, found?.id)
    }

    @Test
    fun findExistingFallsBackToTitleSimilarity() {
        val point = sampleKnowledgePoint(now = now)
        val found = KnowledgePointRules.findExisting(listOf(point), "闭区间上最值求法", "二次函数")
        assertNotNull(found)
        assertEquals(point.id, found?.id)
    }

    @Test
    fun findExistingReturnsNullForDifferentPoint() {
        val point = sampleKnowledgePoint(now = now)
        assertEquals(null, KnowledgePointRules.findExisting(listOf(point), "英语时态辨析", "时态与语态"))
    }

    @Test
    fun refreshTimerPullsReviewBackToOneDay() {
        val timer = ReviewTimer(nextReviewAt = now + 30 * DAY_MS, intervalDays = 30.0, reviewCount = 4)
        val refreshed = KnowledgePointRules.refreshTimer(timer, now)
        assertEquals(now + DAY_MS, refreshed.nextReviewAt)
        assertEquals(1.0, refreshed.intervalDays, 1e-9)
        assertEquals(4, refreshed.reviewCount)
    }

    @Test
    fun matchesAspectIsLenientAndBidirectional() {
        val point = sampleKnowledgePoint(now = now)
        assertTrue(KnowledgePointRules.matchesAspect(point, ""))
        assertTrue(KnowledgePointRules.matchesAspect(point, "二次函数"))
        assertTrue(KnowledgePointRules.matchesAspect(point, "数学"))
        assertTrue(KnowledgePointRules.matchesAspect(point, "函数"))
        assertFalse(KnowledgePointRules.matchesAspect(point, "英语语法"))
    }

    @Test
    fun mergeDetailAndEvidenceAvoidDuplicates() {
        assertEquals("a", KnowledgePointRules.mergeDetail("a", ""))
        assertEquals("a", KnowledgePointRules.mergeDetail("a", "a"))
        assertEquals("a；b", KnowledgePointRules.mergeDetail("a", "b"))
        assertEquals("x", KnowledgePointRules.mergeEvidence("", "x"))
        assertEquals("x；y", KnowledgePointRules.mergeEvidence("x", "y"))
    }
}
