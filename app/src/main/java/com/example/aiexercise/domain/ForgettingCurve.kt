package com.example.aiexercise.domain

import com.example.aiexercise.data.model.ReviewTimer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * 遗忘曲线参数（可替换点）。
 *
 * 算法：简化 SM-2 + 指数遗忘模型。
 * - 复习质量 quality ∈ [0,5]；
 * - quality < passQuality 视为遗忘（lapse），间隔重置为 1 天；
 * - 通过则间隔按 ease 增长，ease 按 SM-2 公式微调；
 * - 记忆保持率 R(t) = e^(-t/S)，S = intervalDays * stabilityFactor。
 */
data class ForgettingCurveConfig(
    /** 新知识点首次复习间隔（天） */
    val initialIntervalDays: Double = 1.0,
    /** 第二次通过后的间隔（天） */
    val secondIntervalDays: Double = 3.0,
    /** 最小 / 默认 / 最大难度系数 */
    val minEase: Double = 1.3,
    val defaultEase: Double = 2.5,
    val maxEase: Double = 2.8,
    /** quality >= 该值算“记住” */
    val passQuality: Int = 3,
    /** 遗忘后重置的间隔（天） */
    val lapseIntervalDays: Double = 1.0,
    /** 复习间隔上限（天），避免无限增长 */
    val maxIntervalDays: Double = 180.0,
    /** 记忆稳定度系数：S = intervalDays * stabilityFactor */
    val stabilityFactor: Double = 1.5,
    /** 到期判定：当前保持率低于该值时提示“该复习了” */
    val retentionDueThreshold: Double = 0.75,
)

object ForgettingCurve {

    /** 新知识点：1 天后首次复习，掌握度从低谷开始。 */
    fun initialTimer(now: Long, config: ForgettingCurveConfig = ForgettingCurveConfig()): ReviewTimer =
        ReviewTimer(
            nextReviewAt = now + daysToMillis(config.initialIntervalDays),
            intervalDays = config.initialIntervalDays,
            ease = config.defaultEase,
        )

    /**
     * 一次复习/自评后的计时器（SM-2）。
     * quality: 0 完全忘记 … 5 非常熟练。
     */
    fun onReview(
        timer: ReviewTimer,
        quality: Int,
        now: Long,
        config: ForgettingCurveConfig = ForgettingCurveConfig(),
    ): ReviewTimer {
        val q = quality.coerceIn(0, 5)
        val reviewedCount = timer.reviewCount + 1
        if (q < config.passQuality) {
            val ease = (timer.ease - 0.2).coerceIn(config.minEase, config.maxEase)
            return timer.copy(
                nextReviewAt = now + daysToMillis(config.lapseIntervalDays),
                intervalDays = config.lapseIntervalDays,
                ease = ease,
                reviewCount = reviewedCount,
                lastReviewedAt = now,
                lastQuality = q,
                lapseCount = timer.lapseCount + 1,
            )
        }
        val base = when (timer.reviewCount) {
            0 -> config.initialIntervalDays
            1 -> config.secondIntervalDays
            else -> timer.intervalDays * timer.ease
        }
        val interval = base.coerceIn(config.initialIntervalDays, config.maxIntervalDays)
        val ease = (timer.ease + (0.1 - (5 - q) * 0.08)).coerceIn(config.minEase, config.maxEase)
        return timer.copy(
            nextReviewAt = now + daysToMillis(interval),
            intervalDays = interval,
            ease = ease,
            reviewCount = reviewedCount,
            lastReviewedAt = now,
            lastQuality = q,
            lapseCount = timer.lapseCount,
        )
    }

    /** 掌握度：按复习质量加权更新，越低越接近 0。 */
    fun masteryAfterReview(oldMastery: Double, quality: Int): Double {
        val q = quality.coerceIn(0, 5)
        val delta = when {
            q >= 5 -> 0.15
            q == 4 -> 0.08
            q == 3 -> 0.0
            q == 2 -> -0.12
            else -> -0.2
        }
        return (oldMastery + delta).coerceIn(0.0, 1.0)
    }

    /** 当前记忆保持率，0..1。 */
    fun retention(
        timer: ReviewTimer,
        now: Long,
        config: ForgettingCurveConfig = ForgettingCurveConfig(),
    ): Double {
        val anchor = timer.lastReviewedAt ?: (timer.nextReviewAt - daysToMillis(timer.intervalDays))
        val elapsedDays = max(0.0, (now - anchor).toDouble() / DAY_MS)
        val stability = max(0.5, timer.intervalDays * config.stabilityFactor)
        return exp(-elapsedDays / stability).coerceIn(0.0, 1.0)
    }

    fun isDue(
        timer: ReviewTimer,
        now: Long,
        config: ForgettingCurveConfig = ForgettingCurveConfig(),
    ): Boolean = now >= timer.nextReviewAt || retention(timer, now, config) < config.retentionDueThreshold

    fun overdueDays(timer: ReviewTimer, now: Long): Double =
        max(0.0, (now - timer.nextReviewAt).toDouble() / DAY_MS)

    /**
     * 复习紧迫度 0..1，练习卷选题按它降序。
     * 组成：保持率缺口 0.35 + 逾期 0.25 + 掌握度缺口 0.20 + 已到期加成 0.20。
     */
    fun urgency(
        timer: ReviewTimer,
        mastery: Double,
        now: Long,
        config: ForgettingCurveConfig = ForgettingCurveConfig(),
    ): Double {
        val retentionDeficit = (1.0 - retention(timer, now, config)).coerceIn(0.0, 1.0)
        val overdue = (overdueDays(timer, now) / 7.0).coerceIn(0.0, 1.0)
        val masteryDeficit = (1.0 - mastery).coerceIn(0.0, 1.0)
        val dueBonus = if (now >= timer.nextReviewAt) 0.20 else 0.0
        return (0.35 * retentionDeficit + 0.25 * overdue + 0.20 * masteryDeficit + dueBonus)
            .coerceIn(0.0, 1.0)
    }

    /** 人类可读的下次复习时间。 */
    fun nextReviewLabel(timer: ReviewTimer, now: Long): String {
        val diff = timer.nextReviewAt - now
        return when {
            diff <= 0 -> {
                val overdue = (-diff).toDouble() / DAY_MS
                if (overdue < 1) "已到期（今天复习）" else "已逾期 ${overdue.roundToLong()} 天"
            }
            diff < DAY_MS -> "约 ${max(1L, diff / (60 * 60 * 1000))} 小时后复习"
            else -> "约 ${(diff.toDouble() / DAY_MS).roundToLong()} 天后复习"
        }
    }

    internal fun daysToMillis(days: Double): Long = (days * DAY_MS).roundToLong()

    /** 保留率对应的“已过天数”，用于展示，不参与核心逻辑。 */
    fun retentionHalfLifeDays(timer: ReviewTimer, config: ForgettingCurveConfig = ForgettingCurveConfig()): Double {
        val stability = max(0.5, timer.intervalDays * config.stabilityFactor)
        return stability * ln(2.0).pow(1.0) / 1.0
    }
}
