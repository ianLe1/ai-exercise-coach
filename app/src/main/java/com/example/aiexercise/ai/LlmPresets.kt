package com.example.aiexercise.ai

/**
 * OpenAI 兼容服务预设：当前只保留 Intern AI（discovery-api）。
 *
 * 注意：仓库不包含任何 API Key（公开仓库写入 Key 等于泄露）。
 * 点预设只会填好 Base URL 与模型名，Key 请在设置里粘贴一次。
 */
data class LlmPreset(
    val name: String,
    val baseUrl: String,
    val model: String,
)

object LlmPresets {

    val all: List<LlmPreset> = listOf(
        LlmPreset(
            name = "Intern AI",
            baseUrl = "https://discovery-api.intern-ai.org.cn/v1",
            model = "Sage-A1.5",
        ),
    )
}
