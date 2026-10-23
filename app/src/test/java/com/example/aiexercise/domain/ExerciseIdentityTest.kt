package com.example.aiexercise.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseIdentityTest {

    @Test
    fun normalizeRemovesWidthPunctuationAndIndexes() {
        assertEquals(
            "求函数fx2x1的最小值",
            ExerciseIdentity.normalize("求函数 f(x)=2x+1 的最小值？"),
        )
        assertEquals(
            "求x²4x3的最小值",
            ExerciseIdentity.normalize("1. 求 x²-4x+3 的最小值。"),
        )
        assertEquals(
            "abc",
            ExerciseIdentity.normalize("ＡＢＣ"),
        )
    }

    @Test
    fun fingerprintIsStableAcrossFormatting() {
        assertEquals(
            ExerciseIdentity.fingerprint("求函数 f(x)=2x+1 的最小值？"),
            ExerciseIdentity.fingerprint("求函数f(x)=2x+1的最小值"),
        )
    }

    @Test
    fun sameExerciseDetectedForMinorEdits() {
        val original = "已知函数 f(x)=x²-4x+3，求它在 [0,3] 上的最大值与最小值。"
        val ocrCopy = "已知函数f(x)=x²-4x+3 求它在[0,3]上的最大值与最小值"
        assertEquals(1.0, ExerciseIdentity.similarity(original, ocrCopy), 1e-9)
        assertTrue(ExerciseIdentity.isSameExercise(original + "？", ocrCopy))
    }

    @Test
    fun differentQuestionsAreNotTheSameExercise() {
        val a = "已知函数 f(x)=x²-4x+3，求它在 [0,3] 上的最大值与最小值。"
        val b = "已知函数 f(x)=x²-4x+3，求它的单调区间。"
        assertFalse(ExerciseIdentity.isSameExercise(a, b))
    }

    @Test
    fun shortTextsOnlyMatchOnFingerprint() {
        assertFalse(ExerciseIdentity.isSameExercise("1. 求值", "1. 化简"))
        assertTrue(ExerciseIdentity.isSameExercise("1. 求值", "2. 求值"))
    }
}
