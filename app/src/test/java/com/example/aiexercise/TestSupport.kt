package com.example.aiexercise

import com.example.aiexercise.data.model.Exercise
import com.example.aiexercise.data.model.KnowledgePoint
import com.example.aiexercise.data.model.ReviewTimer
import com.example.aiexercise.domain.AppError
import com.example.aiexercise.domain.DAY_MS
import com.example.aiexercise.domain.Outcome

/* 单元测试共用的小工具（纯 JVM，不依赖 Android）。 */

fun <T> Outcome<T>.successValue(): T = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> throw AssertionError("期望成功，实际失败：${error.userMessage}")
}

fun <T> Outcome<T>.failureError(): AppError = when (this) {
    is Outcome.Success -> throw AssertionError("期望失败，实际成功：$value")
    is Outcome.Failure -> error
}

fun sampleExercise(id: String = "ex-1", questionText: String = "求函数 f(x)=x²-4x+3 的最小值。"): Exercise =
    Exercise(
        id = id,
        questionText = questionText,
        fingerprint = "fingerprint-$id",
        subject = "数学",
        referenceAnswer = "最小值为 -1",
        createdAt = 1_700_000_000_000L,
        lastSeenAt = 1_700_000_000_000L,
    )

fun sampleKnowledgePoint(
    id: String = "kp-1",
    title: String = "闭区间上的最值求法",
    aspect: String = "二次函数",
    now: Long = 1_700_000_000_000L,
): KnowledgePoint = KnowledgePoint(
    id = id,
    title = title,
    aspect = aspect,
    subject = "数学",
    detail = "闭区间求最值必须同时比较顶点与两个端点。",
    evidence = "作答错误，判定为未掌握",
    sourceExerciseIds = listOf("ex-1"),
    mastery = 0.2,
    timer = ReviewTimer(nextReviewAt = now + DAY_MS),
    createdAt = now,
    updatedAt = now,
)
