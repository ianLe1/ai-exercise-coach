package com.example.aiexercise.domain

import com.example.aiexercise.domain.ExerciseIdentity.similarity

/**
 * 订正质量评估（模块三：「若给出知识点后的订正质量极低，则拒绝回答问题」）。
 *
 * 只做“订正是否接近正确答”的客观评估，不评判用户态度；
 * 阈值参数集中在这里，可按需替换。
 */
enum class CorrectionQuality { NONE, LOW, MEDIUM, HIGH }

data class CorrectionCheck(
    val quality: CorrectionQuality,
    val similarity: Double,
    val message: String,
    /** 是否允许发起引导式提问。 */
    val mayAskGuided: Boolean,
)

object CorrectionGuard {

    /** 明显没改对：拒绝引导式提问，先重做。 */
    const val LOW_THRESHOLD = 0.35

    /** 基本改对：允许提问但继续订正。 */
    const val HIGH_THRESHOLD = 0.75

    fun check(correctedAnswer: String, correctAnswer: String): CorrectionCheck {
        val corrected = correctedAnswer.trim()
        if (corrected.isEmpty()) {
            return CorrectionCheck(
                quality = CorrectionQuality.NONE,
                similarity = 0.0,
                message = "这一轮没有识别到订正后的作答，请重新扫描或重做后再来。",
                mayAskGuided = false,
            )
        }
        val value = answerSimilarity(corrected, correctAnswer)
        return when {
            value < LOW_THRESHOLD -> CorrectionCheck(
                quality = CorrectionQuality.LOW,
                similarity = value,
                message = "订正与正确答案差距仍然很大，先把这道题重新做一遍再提问。",
                mayAskGuided = false,
            )

            value < HIGH_THRESHOLD -> CorrectionCheck(
                quality = CorrectionQuality.MEDIUM,
                similarity = value,
                message = "订正了一半左右，可以就卡住的那一步提问。",
                mayAskGuided = true,
            )

            else -> CorrectionCheck(
                quality = CorrectionQuality.HIGH,
                similarity = value,
                message = "订正已接近正确答案，回顾一下思路就可以过关。",
                mayAskGuided = true,
            )
        }
    }

    private fun answerSimilarity(a: String, b: String): Double {
        val textScore = similarity(a, b)
        val aNumbers = NUMBERS.findAll(a).map { it.value }.toList()
        val bNumbers = NUMBERS.findAll(b).map { it.value }.toList()
        val numberScore = when {
            aNumbers.isEmpty() || bNumbers.isEmpty() -> textScore
            else -> aNumbers.count { bNumbers.contains(it) }.toDouble() / maxOf(aNumbers.size, bNumbers.size)
        }
        return maxOf(textScore, numberScore)
    }

    private val NUMBERS = Regex("-?\\d+(?:\\.\\d+)?")
}

/**
 * 「作业与不会的知识点列表大相径庭」守卫：
 * - 首次使用必问（用户说“首次使用必会询问”）；
 * - 之后若本次扫描出的题目与已有习题几乎对不上（匹配率 < [MISMATCH_RATIO]），也要问一句。
 */
enum class HomeworkGuardAction { ASK, PROCEED }

data class HomeworkGuardResult(
    val action: HomeworkGuardAction,
    val firstTime: Boolean,
    val matchedRatio: Double,
    val message: String,
)

object HomeworkGuard {

    const val MATCH_SIMILARITY = 0.5
    const val MISMATCH_RATIO = 0.2

    fun decide(
        scannedQuestions: List<String>,
        knownQuestions: List<String>,
        askedOnce: Boolean,
    ): HomeworkGuardResult {
        if (!askedOnce) {
            return HomeworkGuardResult(
                action = HomeworkGuardAction.ASK,
                firstTime = true,
                matchedRatio = 0.0,
                message = "首次一起批改作业：这次作业是你自己认真做的吗？如果不是，我会忽略这次扫描，不写进不会的知识点列表。",
            )
        }
        if (scannedQuestions.isEmpty() || knownQuestions.isEmpty()) {
            return HomeworkGuardResult(HomeworkGuardAction.PROCEED, false, 1.0, "")
        }
        val matched = scannedQuestions.count { scanned ->
            knownQuestions.any { known -> similarity(scanned, known) >= MATCH_SIMILARITY }
        }
        val ratio = matched.toDouble() / scannedQuestions.size
        return if (ratio < MISMATCH_RATIO) {
            HomeworkGuardResult(
                action = HomeworkGuardAction.ASK,
                firstTime = false,
                matchedRatio = ratio,
                message = "这次作业和现有的不会知识点列表几乎对不上（匹配率约 ${"%.0f".format(ratio * 100)}%）。你确定是认真做的吗？如果没用心，我会忽略这次扫描。",
            )
        } else {
            HomeworkGuardResult(HomeworkGuardAction.PROCEED, false, ratio, "")
        }
    }
}

/** 引导式回答的一轮结果。 */
data class GuidedTurn(
    val question: String,
    val feedback: String = "",
    val resolved: Boolean = false,
    val stage: Int = 0,
)

/**
 * 离线引导式导师（远程模式由 AI prompt 实现，见 OpenAiCompatibleAiService）。
 * 只给“怎么想”的引导，不直接给答案，共三步。
 */
object LocalGuidedTutor {

    fun opening(questionText: String, kpTitle: String, wrongAnswer: String): String = buildString {
        append("我们一步步来，不急着看答案。")
        if (wrongAnswer.isNotBlank()) {
            append("你上次写的是「${wrongAnswer.take(40)}」，这次先不动它。")
        }
        append("先用自己的话把题目缩成一句：它给了什么条件、要求什么？")
        if (kpTitle.isNotBlank()) append("（本题关联知识点：$kpTitle）")
    }

    fun next(stage: Int, answer: String): GuidedTurn {
        val trimmed = answer.trim()
        val tooShort = trimmed.length < 4
        return when (stage) {
            1 -> GuidedTurn(
                feedback = if (tooShort) "再展开一点：把条件抄成短句就行。" else "有方向了。",
                question = "第二步：这道题的关键条件或公式是哪一个？把题干里的数字、限制条件圈出来。",
                stage = 2,
            )

            2 -> GuidedTurn(
                feedback = if (tooShort) "别怕写错，先把能想到的写下来。" else "不错，已经碰到关键了。",
                question = "第三步：如果只写第一行推导，你会写什么？先写这一步，不用算完。",
                stage = 3,
            )

            else -> GuidedTurn(
                feedback = "把这三步串起来，就是这道题的骨架。现在回到订正，把刚才缺的那一步补上。",
                question = "如果还想再来一轮，重新扫描订正后的作答；也可以就刚才卡住的点继续问我。",
                resolved = true,
                stage = 4,
            )
        }
    }
}
