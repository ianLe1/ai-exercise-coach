package com.example.aiexercise.ai

/**
 * 视觉识别（扫描）与联网搜索的服务配置（可替换点）。
 *
 * - 视觉：默认 ML Kit 离线 OCR；也可切到 modlens 视觉桥（tools/modlens-bridge，node 服务包装 modlens CLI）。
 * - 搜索：默认关闭；开启后使用必应 Web Search API（Azure Bing Search v7）。
 */
enum class VisionMode {
    /** Google ML Kit 中文文字识别：端侧离线，无需网络与 Key。 */
    MLKIT,

    /**
     * modlens 视觉桥：图片 → 结构化文本；需要本机运行 tools/modlens-bridge。
     * 模拟器推荐先 `adb reverse tcp:8765 tcp:8765`，桥地址填 http://127.0.0.1:8765（部分镜像的 10.0.2.2 不可达）。
     */
    MODLENS,

    /** 内置多模态视觉：App 内直接调用 OpenAI 兼容的多模态接口（无需本机桥），返回排版 Markdown。 */
    CLOUD,
}

data class VisionSettings(
    val mode: VisionMode = VisionMode.CLOUD,
    /** modlens 桥地址；模拟器推荐 adb reverse 后填 127.0.0.1，或用 10.0.2.2。 */
    val modlensUrl: String = DEFAULT_MODLENS_URL,
    /** modlens 失败时是否自动回退 ML Kit（保证离线也能用）。 */
    val fallbackToMlKit: Boolean = true,
    /** 内置多模态接口（OpenAI 兼容，如 https://api.openai.com/v1）。 */
    val cloudEndpoint: String = "https://api.openai.com/v1",
    val cloudApiKey: String = "",
    val cloudModel: String = "gpt-4o-mini",
) {
    companion object {
        const val DEFAULT_MODLENS_URL = "http://10.0.2.2:8765"
    }
}

enum class SearchMode {
    OFF,
    /** 普通必应网页搜索（RSS，免 Key）。 */
    BING_WEB,
    /** Azure 必应 Web Search API v7（需 Key）。 */
    BING,
}

data class SearchSettings(
    val mode: SearchMode = SearchMode.OFF,
    /** 必应 Web Search v7 端点（可换成 Azure 区域端点或自建代理）。 */
    val endpoint: String = DEFAULT_BING_ENDPOINT,
    val apiKey: String = "",
) {
    companion object {
        const val DEFAULT_BING_ENDPOINT = "https://api.bing.microsoft.com/v7.0/search"
    }
}

/** 一条搜索结果（标题 / 链接 / 摘要）。 */
data class SearchHit(
    val title: String,
    val url: String,
    val snippet: String,
)

/**
 * 教材 / 教师用书知识库设置（可替换点）。
 *
 * 打开后，AI 分类知识点前会自动检索 ChinaTextbook-KB 服务（国家中小学智慧教育平台教材），
 * 命中片段作为「教材依据」写进 [com.example.aiexercise.data.model.ResearchBundle]，
 * 并喂给 AI 分类提示词；服务不可用时只降级提示，不阻断知识点维护。
 */
data class KnowledgeSettings(
    val enabled: Boolean = false,
    /** ChinaTextbook-KB 的 HTTP 服务地址；模拟器推荐 adb reverse 后用 127.0.0.1。 */
    val endpoint: String = DEFAULT_KB_ENDPOINT,
) {
    companion object {
        const val DEFAULT_KB_ENDPOINT = "http://127.0.0.1:8737"
    }
}
