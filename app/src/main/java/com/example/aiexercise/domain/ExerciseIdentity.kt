package com.example.aiexercise.domain

import java.security.MessageDigest
import kotlin.math.min

/**
 * “同一习题”判定。
 *
 * 策略（三级，从严到宽）：
 * 1. 规范化文本的 SHA-256 指纹完全相同 → 同一题；
 * 2. 文本长度比例 ≥ 0.6 且 bigram Jaccard 相似度 ≥ 阈值（默认 0.85）→ 同一题；
 *    —— 用于 OCR 错别字、漏字、标点差异导致的“看起来一样”的题；
 * 3. 规范化后长度 < 6 的短文本只认同指纹，避免把“1. 求值”这类题误判成同一题。
 *
 * 题号前缀（“1.”“第3题”“(2)”）与标点/空白/全半角差异会在规范化时被消除。
 */
object ExerciseIdentity {

    private val leadingIndex = Regex("^((第[一二三四五六七八九十百零]+题)|([0-9]+[.、)）:：]))")
    private val noise = Regex("[\\s，。；：！？、,.!?;:'\"“”‘’（）()\\[\\]【】{}<>《》·—\\-_=+/\\\\|~@#$%^&*`]")

    /** 统一格式、去掉题号与标点，供相似度比较。 */
    fun normalize(raw: String): String {
        val halfWidth = buildString(raw.length) {
            for (ch in raw) {
                append(
                    when {
                        ch in '！'..'～' -> (ch.code - 0xFEE0).toChar() // 全角 → 半角
                        ch == '\u3000' -> ' '
                        else -> ch
                    },
                )
            }
        }
        return noise.replace(leadingIndex.replace(halfWidth.trim().lowercase(), ""), "")
    }

    fun fingerprint(raw: String): String {
        val normalized = normalize(raw)
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** bigram 集合的 Jaccard 相似度，0..1。 */
    fun similarity(a: String, b: String): Double {
        val left = bigrams(normalize(a))
        val right = bigrams(normalize(b))
        if (left.isEmpty() && right.isEmpty()) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val intersection = left.count { it in right }
        val union = left.size + right.size - intersection
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    fun isSameExercise(a: String, b: String, threshold: Double = 0.85): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        if (min(na.length, nb.length) < 6) return false
        val lengthRatio = min(na.length, nb.length).toDouble() / maxOf(na.length, nb.length)
        if (lengthRatio < 0.6) return false
        return similarity(na, nb) >= threshold
    }

    private fun bigrams(normalized: String): Set<String> {
        if (normalized.length < 2) return if (normalized.isEmpty()) emptySet() else setOf(normalized)
        val result = HashSet<String>(normalized.length)
        for (index in 0 until normalized.length - 1) {
            result += normalized.substring(index, index + 2)
        }
        return result
    }
}
