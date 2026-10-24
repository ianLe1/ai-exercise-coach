package com.example.aiexercise.ai

/**
 * 免费/常用 OpenAI 兼容服务预设，来源于
 * https://github.com/for-the-zero/Free-LLM-Collection （只收录 OpenAI 格式端点）。
 *
 * 这些服务需要各自注册并申请 API Key；这里只预填 Base URL 与推荐模型。
 */
data class LlmPreset(
    val name: String,
    val baseUrl: String,
    val model: String,
)

object LlmPresets {

    val all: List<LlmPreset> = listOf(
        LlmPreset(
            name = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            model = "Qwen/Qwen3-8B",
        ),
        LlmPreset(
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            model = "openrouter/free",
        ),
        LlmPreset(
            name = "FreeAIapikey",
            baseUrl = "https://api.freeaiapikey.com/v1",
            model = "openai/gpt-5.5",
        ),
        LlmPreset(
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o-mini",
        ),
        LlmPreset(
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
        ),
    )
}
