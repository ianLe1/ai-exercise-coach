package com.example.aiexercise.platform

import kotlin.math.max
import kotlin.math.min

/**
 * 自动四角检测（纯 Kotlin，可 JVM 测试）：拍照后给矫正界面一个初始四边形，用户仍可拖动微调。
 *
 * 思路参考 DocScanner 的“自动边缘检测”，这里用轻量近似：
 * 1. 灰度 + Otsu 二值化，取较亮的一侧作为纸张掩码；
 * 2. 掩码占比不合理（几乎全亮/全暗）时放弃，回退到内缩默认框；
 * 3. 用“到四角方向最远”的极值点求四边形顶点（tl 最小 x+y、br 最大 x+y、tr 最大 x-y、bl 最小 x-y）；
 * 4. 校验面积、避免退化。
 */
object AutoCornerDetector {

    data class Point(val x: Float, val y: Float)

    /** 返回顺序：左上、右上、右下、左下。检测失败返回 null。 */
    fun detect(width: Int, height: Int, argb: IntArray): List<Point>? {
        if (width < 16 || height < 16 || argb.size < width * height) return null

        val gray = IntArray(width * height)
        var minGray = 255
        var maxGray = 0
        for (i in gray.indices) {
            val pixel = argb[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val value = (r * 299 + g * 587 + b * 114) / 1000
            gray[i] = value
            if (value < minGray) minGray = value
            if (value > maxGray) maxGray = value
        }
        if (maxGray - minGray < 24) return null

        val threshold = otsu(gray, minGray, maxGray)
        // 纸张通常更亮；若亮区过大（整张照片）或过小（几乎全暗）都判失败
        var bright = 0
        for (value in gray) if (value > threshold) bright++
        val ratio = bright.toFloat() / gray.size
        if (ratio < 0.10f || ratio > 0.92f) return null

        var tl = -1
        var tr = -1
        var br = -1
        var bl = -1
        var minSum = Int.MAX_VALUE
        var maxSum = Int.MIN_VALUE
        var minDiff = Int.MAX_VALUE
        var maxDiff = Int.MIN_VALUE
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (gray[row + x] <= threshold) continue
                val sum = x + y
                val diff = x - y
                if (sum < minSum) {
                    minSum = sum
                    tl = row + x
                }
                if (sum > maxSum) {
                    maxSum = sum
                    br = row + x
                }
                if (diff > maxDiff) {
                    maxDiff = diff
                    tr = row + x
                }
                if (diff < minDiff) {
                    minDiff = diff
                    bl = row + x
                }
            }
        }
        if (tl < 0 || tr < 0 || br < 0 || bl < 0) return null

        val points = listOf(
            Point((tl % width).toFloat(), (tl / width).toFloat()),
            Point((tr % width).toFloat(), (tr / width).toFloat()),
            Point((br % width).toFloat(), (br / width).toFloat()),
            Point((bl % width).toFloat(), (bl / width).toFloat()),
        )
        return if (isReasonable(points, width, height)) points else null
    }

    private fun otsu(gray: IntArray, minGray: Int, maxGray: Int): Int {
        val histogram = IntArray(256)
        for (value in gray) histogram[value.coerceIn(0, 255)]++
        val total = gray.size
        var sum = 0L
        for (i in 0..255) sum += i.toLong() * histogram[i]
        var sumBackground = 0L
        var weightBackground = 0
        var best = 0.0
        var threshold = (minGray + maxGray) / 2
        for (t in 0..255) {
            weightBackground += histogram[t]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break
            sumBackground += t.toLong() * histogram[t]
            val meanBackground = sumBackground.toDouble() / weightBackground
            val meanForeground = (sum - sumBackground).toDouble() / weightForeground
            val between = weightBackground.toDouble() * weightForeground *
                (meanBackground - meanForeground) * (meanBackground - meanForeground)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }

    private fun isReasonable(points: List<Point>, width: Int, height: Int): Boolean {
        val area = polygonArea(points)
        val imageArea = width.toFloat() * height
        if (area < imageArea * 0.15f) return false
        // 四边形每条边不能过短，避免检测出三角形/退化点
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val length = kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
            if (length < min(width, height) * 0.15) return false
        }
        // 中心点必须落在图像内，且上下边/左右边长度相差不能太离谱
        val top = distance(points[0], points[1])
        val bottom = distance(points[3], points[2])
        val left = distance(points[0], points[3])
        val right = distance(points[1], points[2])
        val aspectOk = max(top, bottom) / max(1f, min(top, bottom)) < 3.2f &&
            max(left, right) / max(1f, min(left, right)) < 3.2f
        return aspectOk
    }

    private fun polygonArea(points: List<Point>): Float {
        var sum = 0f
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return kotlin.math.abs(sum) / 2f
    }

    private fun distance(a: Point, b: Point): Float =
        kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
}
