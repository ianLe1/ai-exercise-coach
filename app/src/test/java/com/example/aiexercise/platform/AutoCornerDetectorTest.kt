package com.example.aiexercise.platform

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCornerDetectorTest {

    private fun image(width: Int, height: Int, background: Int): IntArray =
        IntArray(width * height) { background }

    private fun fillRect(
        pixels: IntArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        color: Int,
    ) {
        for (y in top until bottom) {
            for (x in left until right) {
                pixels[y * width + x] = color
            }
        }
    }

    private fun gray(value: Int): Int = (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    @Test
    fun detectsWhitePaperOnGrayBackground() {
        val width = 300
        val height = 400
        val pixels = image(width, height, gray(90))
        fillRect(pixels, width, 40, 50, 260, 350, gray(245))

        val corners = AutoCornerDetector.detect(width, height, pixels)
        assertNotNull(corners)
        val points = corners!!
        assertTrue("左上应接近 (40,50)，实际 ${points[0]}", points[0].x < 80f && points[0].y < 90f)
        assertTrue("右下应接近 (260,350)，实际 ${points[2]}", points[2].x > 220f && points[2].y > 310f)
    }

    @Test
    fun rejectsUniformImage() {
        val pixels = image(200, 200, gray(128))
        assertNull(AutoCornerDetector.detect(200, 200, pixels))
    }

    @Test
    fun rejectsFullyBrightImage() {
        val pixels = image(200, 200, gray(250))
        assertNull(AutoCornerDetector.detect(200, 200, pixels))
    }

    @Test
    fun rejectsTinyImage() {
        val pixels = image(8, 8, gray(0))
        assertNull(AutoCornerDetector.detect(8, 8, pixels))
    }
}
