package com.example.aiexercise.ai

/** AI 接入模式（可替换点：新增厂商时在 [AiMode] 里加枚举）。 */
enum class AiMode {
    /** 离线规则引擎：无需网络与 Key，默认值，保证开箱能跑通全流程。 */
    OFFLINE,

    /** OpenAI 兼容接口：OpenAI / DeepSeek / Moonshot / 通义 / Ollama / vLLM 等。 */
    REMOTE,
}

data class AiSettings(
    val mode: AiMode = AiMode.OFFLINE,
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
    }
}

/** 工厂：根据设置构建 AI 服务实例。 */
object AiServiceFactory {
    fun create(settings: AiSettings): AiService = when (settings.mode) {
        AiMode.OFFLINE -> LocalAiService()
        AiMode.REMOTE -> OpenAiCompatibleAiService(
            AiRemoteConfig(
                baseUrl = settings.baseUrl.trim().ifBlank { AiSettings.DEFAULT_BASE_URL },
                apiKey = settings.apiKey.trim(),
                model = settings.model.trim().ifBlank { AiSettings.DEFAULT_MODEL },
            ),
        )
    }
}
