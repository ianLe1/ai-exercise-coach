package com.example.aiexercise.domain

import com.example.aiexercise.data.model.ReviewTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgettingCurveTest {

    private val now = 1_700_000_000_000L

    @Test
    fun newTimerIsScheduledOneDayLater() {
        val timer = ForgettingCurve.initialTimer(now)
        assertEquals(now + DAY_MS, timer.nextReviewAt)
        assertEquals(1.0, timer.intervalDays, 1e-9)
        assertEquals(2.5, timer.ease, 1e-9)
        assertEquals(0, timer.reviewCount)
        assertFalse(ForgettingCurve.isDue(timer, now))
    }

    @Test
    fun passingReviewGrowsIntervalAndEase() {
        val timer = ForgettingCurve.initialTimer(now)
        val firstReview = ForgettingCurve.onReview(timer, 5, now + DAY_MS)
        assertEquals(1, firstReview.reviewCount)
        assertEquals(now + 2 * DAY_MS, firstReview.nextReviewAt)
        assertTrue(firstReview.ease > timer.ease)

        val secondReview = ForgettingCurve.onReview(firstReview, 5, now + 2 * DAY_MS)
        assertEquals(2, secondReview.reviewCount)
        assertTrue(secondReview.intervalDays > firstReview.intervalDays)
        assertEquals(now + 2 * DAY_MS + ForgettingCurve.daysToMillis(secondReview.intervalDays), secondReview.nextReviewAt)
    }

    @Test
    fun lowQualityReviewResetsIntervalAndCountsLapse() {
        val timer = ForgettingCurve.initialTimer(now)
        val reviewed = ForgettingCurve.onReview(timer, 1, now + DAY_MS)
        assertEquals(1.0, reviewed.intervalDays, 1e-9)
        assertEquals(now + 2 * DAY_MS, reviewed.nextReviewAt)
        assertEquals(1, reviewed.lapseCount)
        assertEquals(1, reviewed.lastQuality)
        assertTrue(reviewed.ease < timer.ease)
    }

    @Test
    fun masteryMovesWithQualityAndStaysInBounds() {
        assertEquals(0.35, ForgettingCurve.masteryAfterReview(0.2, 5), 1e-9)
        assertEquals(0.28, ForgettingCurve.masteryAfterReview(0.2, 4), 1e-9)
        assertEquals(0.2, ForgettingCurve.masteryAfterReview(0.2, 3), 1e-9)
        assertEquals(0.08, ForgettingCurve.masteryAfterReview(0.2, 2), 1e-9)
        assertEquals(0.0, ForgettingCurve.masteryAfterReview(0.2, 0), 1e-9)
        assertEquals(1.0, ForgettingCurve.masteryAfterReview(0.95, 5), 1e-9)
    }

    @Test
    fun retentionDecaysOverTime() {
        val timer = ForgettingCurve.initialTimer(now)
        val atStart = ForgettingCurve.retention(timer, now)
        val halfDay = ForgettingCurve.retention(timer, now + DAY_MS / 2)
        val oneDay = ForgettingCurve.retention(timer, now + DAY_MS)
        assertTrue(atStart > halfDay)
        assertTrue(halfDay > oneDay)
        assertTrue(oneDay < 0.75)
    }

    @Test
    fun dueAndOverdueAreDetected() {
        val timer = ReviewTimer(nextReviewAt = now + DAY_MS)
        assertFalse(ForgettingCurve.isDue(timer, now))
        assertTrue(ForgettingCurve.isDue(timer, now + 2 * DAY_MS))
        assertEquals(1.0, ForgettingCurve.overdueDays(timer, now + 2 * DAY_MS), 1e-9)
        assertEquals("已逾期 2 天", ForgettingCurve.nextReviewLabel(timer, now + 3 * DAY_MS))
    }

    @Test
    fun urgencyIsHigherForDueAndWeakerPoints() {
        val dueTimer = ReviewTimer(nextReviewAt = now)
        val freshTimer = ReviewTimer(nextReviewAt = now + 30 * DAY_MS)
        val dueUrgency = ForgettingCurve.urgency(dueTimer, 0.1, now)
        val freshUrgency = ForgettingCurve.urgency(freshTimer, 0.9, now)
        assertTrue(dueUrgency > freshUrgency)
        assertTrue(dueUrgency in 0.0..1.0)
    }
}
