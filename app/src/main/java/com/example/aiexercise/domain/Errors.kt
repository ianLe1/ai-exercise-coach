package com.example.aiexercise.domain

/**
 * 全应用统一的可读错误类型。
 *
 * UI 只展示 [userMessage]，[technical] 仅用于日志/排查。
 * 每个错误都对应一条明确的“处理方式”，见 README「错误处理」。
 */
sealed class AppError(val userMessage: String, val technical: String = "") {

    class UnrecognizedInput(technical: String = "") : AppError(
        "未能识别到有效的题目内容。请重新拍摄/选择清晰图片，或切换到手动输入。",
        technical,
    )

    object EmptyQuestion : AppError("题目内容为空，请先扫描或输入题目。")

    object MissingReferenceAnswer : AppError(
        "缺少参考答案，无法批改。请在扫描页补录这道题的答案后重试。",
    )

    object MissingStudentAnswer : AppError("请先填写你的作答，再开始批改。")

    class AiFormatError(technical: String = "") : AppError(
        "AI 返回结果格式不合法（已自动重试 1 次）。请检查模型配置，或切换到离线规则引擎模式。",
        technical,
    )

    class AiNetworkError(technical: String) : AppError(
        "AI 服务调用失败：$technical。请检查网络与接口地址，或切换到离线模式。",
        technical,
    )

    class AiNotConfigured(technical: String = "") : AppError(
        "已选择云端 AI 模式，但接口地址 / 模型 / API Key 未配置完整。请到「设置」补全，或切换到离线模式。",
        technical,
    )

    object AiEmptyKnowledgePoints : AppError(
        "AI 没有从这道题里提取到“不会的知识点”。请补充参考答案，或换一道题再试。",
    )

    class NoKnowledgePointForAspect(val aspect: String, val available: List<String>) : AppError(
        if (available.isEmpty()) {
            "当前还没有任何「不会的知识点」，暂时无法生成练习卷。请先扫描并批改几道题。"
        } else {
            "「$aspect」方面没有对应的不会知识点。可用方面：${available.joinToString("、")}"
        },
    )

    class GenerationInvalid(technical: String) : AppError(
        "练习卷生成结果不合法：$technical。请重试，或检查 AI 模型是否支持 JSON 输出。",
        technical,
    )

    class Persistence(technical: String) : AppError("数据保存失败：$technical", technical)

    class Unexpected(technical: String) : AppError("发生未知错误：$technical", technical)
}

/** 业务层结果：成功 / 可读失败。 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>
}

fun <T> Outcome<T>.valueOrNull(): T? = (this as? Outcome.Success)?.value

fun <T> Outcome<T>.errorOrNull(): AppError? = (this as? Outcome.Failure)?.error

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}
