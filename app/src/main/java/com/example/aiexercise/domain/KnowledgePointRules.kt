package com.example.aiexercise.domain

import com.example.aiexercise.data.model.KnowledgePoint
import com.example.aiexercise.data.model.ReviewTimer

/**
 * 知识点的去重、合并、计时器刷新与“方面”匹配规则。
 *
 * 去重键 = normalize(title) + "|" + normalize(aspect)；
 * 若键不同但标题高度相似（≥ 0.9）且方面相同，也视为同一条（AI 措辞略有差异）。
 */
object KnowledgePointRules {

    fun dedupeKey(title: String, aspect: String): String =
        ExerciseIdentity.normalize(title) + "|" + ExerciseIdentity.normalize(aspect)

    fun dedupeKey(point: KnowledgePoint): String = dedupeKey(point.title, point.aspect)

    fun findExisting(
        points: List<KnowledgePoint>,
        title: String,
        aspect: String,
    ): KnowledgePoint? {
        val key = dedupeKey(title, aspect)
        points.firstOrNull { dedupeKey(it) == key }?.let { return it }
        val normalizedAspect = ExerciseIdentity.normalize(aspect)
        val normalizedTitle = ExerciseIdentity.normalize(title)
        val shortTitle = normalizedTitle.length <= SHORT_TITLE_LENGTH
        return points.firstOrNull { point ->
            ExerciseIdentity.normalize(point.aspect) == normalizedAspect &&
                titleIsSamePoint(point.title, title, shortTitle)
        }
    }

    /**
     * 标题相似度兜底：中文标题很短（≤ 12 字）时，AI 措辞差异常表现为多/少一个字或一个“的”，
     * 此时 bigram Jaccard 会被明显拉低，因此短标题放宽到 0.6；长标题仍要求 ≥ 0.9。
     */
    private fun titleIsSamePoint(existingTitle: String, newTitle: String, shortTitle: Boolean): Boolean {
        val similarity = ExerciseIdentity.similarity(existingTitle, newTitle)
        if (similarity <= 0.0) return false
        if (similarity >= TITLE_SIMILARITY) return true
        if (shortTitle && similarity >= SHORT_TITLE_SIMILARITY) return true
        return ExerciseIdentity.normalize(existingTitle).contains(ExerciseIdentity.normalize(newTitle))
    }

    private const val TITLE_SIMILARITY = 0.9
    private const val SHORT_TITLE_SIMILARITY = 0.6
    private const val SHORT_TITLE_LENGTH = 12

    /**
     * 再次遇到“不会的知识点”时刷新计时器：
     * 立即把下次复习时间拉回 1 天后（说明还是不会），掌握度由调用方压回下限。
     */
    fun refreshTimer(
        timer: ReviewTimer,
        now: Long,
        config: ForgettingCurveConfig = ForgettingCurveConfig(),
    ): ReviewTimer = timer.copy(
        nextReviewAt = now + ForgettingCurve.daysToMillis(config.initialIntervalDays),
        intervalDays = config.initialIntervalDays,
    )

    /**
     * 判断知识点是否属于用户指定的“方面”。
     *
     * 规则：方面为空 → 全部命中；否则依次比较知识点 aspect / subject / title 的包含关系
     * （双向包含，容忍“二次函数” ⊂ “二次函数图象与性质” 这类粒度差异）。
     */
    fun matchesAspect(point: KnowledgePoint, aspect: String): Boolean {
        val wanted = ExerciseIdentity.normalize(aspect)
        if (wanted.isEmpty()) return true
        val aspectName = ExerciseIdentity.normalize(point.aspect)
        val subject = ExerciseIdentity.normalize(point.subject)
        val title = ExerciseIdentity.normalize(point.title)
        return aspectName == wanted ||
            subject == wanted ||
            aspectName.contains(wanted) ||
            wanted.contains(aspectName) ||
            title.contains(wanted)
    }

    fun mergeDetail(old: String, new: String): String {
        if (new.isBlank()) return old
        if (old.isBlank()) return new
        if (old.contains(new)) return old
        return "$old；$new"
    }

    fun mergeEvidence(old: String, new: String): String {
        if (new.isBlank()) return old
        if (old.isBlank()) return new
        if (old.contains(new)) return old
        return "$old；$new"
    }
}
