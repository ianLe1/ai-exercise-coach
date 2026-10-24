package com.example.aiexercise.platform

import android.content.Context
import com.example.aiexercise.ai.AiMode
import com.example.aiexercise.ai.AiSettings
import com.example.aiexercise.ai.SearchMode
import com.example.aiexercise.ai.KnowledgeSettings
import com.example.aiexercise.ai.SearchSettings
import com.example.aiexercise.ai.VisionMode
import com.example.aiexercise.ai.VisionSettings

/**
 * 所有外部服务配置的持久化（SharedPreferences）。
 * API Key 只存本机，不写入 JSON 数据文件、不打印日志。
 */
class AiSettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /* ------------------------------ AI ------------------------------ */

    fun load(): AiSettings {
        val mode = runCatching {
            AiMode.valueOf(prefs.getString(KEY_MODE, AiMode.OFFLINE.name).orEmpty())
        }.getOrDefault(AiMode.OFFLINE)
        return AiSettings(
            mode = mode,
            baseUrl = prefs.getString(KEY_BASE_URL, AiSettings.DEFAULT_BASE_URL).orEmpty(),
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
            model = prefs.getString(KEY_MODEL, AiSettings.DEFAULT_MODEL).orEmpty(),
        )
    }

    fun save(settings: AiSettings) {
        prefs.edit()
            .putString(KEY_MODE, settings.mode.name)
            .putString(KEY_BASE_URL, settings.baseUrl.trim())
            .putString(KEY_API_KEY, settings.apiKey.trim())
            .putString(KEY_MODEL, settings.model.trim())
            .apply()
    }

    /* ---------------------------- 视觉识别 ---------------------------- */

    fun loadVision(): VisionSettings {
        val mode = runCatching {
            VisionMode.valueOf(prefs.getString(KEY_VISION_MODE, VisionMode.MLKIT.name).orEmpty())
        }.getOrDefault(VisionMode.MLKIT)
        return VisionSettings(
            mode = mode,
            modlensUrl = prefs.getString(KEY_MODLENS_URL, VisionSettings.DEFAULT_MODLENS_URL).orEmpty(),
            fallbackToMlKit = prefs.getBoolean(KEY_VISION_FALLBACK, true),
        )
    }

    fun saveVision(settings: VisionSettings) {
        prefs.edit()
            .putString(KEY_VISION_MODE, settings.mode.name)
            .putString(KEY_MODLENS_URL, settings.modlensUrl.trim())
            .putBoolean(KEY_VISION_FALLBACK, settings.fallbackToMlKit)
            .apply()
    }

    /* ---------------------------- 联网搜索 ---------------------------- */

    fun loadSearch(): SearchSettings {
        val mode = runCatching {
            SearchMode.valueOf(prefs.getString(KEY_SEARCH_MODE, SearchMode.OFF.name).orEmpty())
        }.getOrDefault(SearchMode.OFF)
        return SearchSettings(
            mode = mode,
            endpoint = prefs.getString(KEY_BING_ENDPOINT, SearchSettings.DEFAULT_BING_ENDPOINT).orEmpty(),
            apiKey = prefs.getString(KEY_BING_KEY, "").orEmpty(),
        )
    }

    fun loadKnowledge(): KnowledgeSettings {
        return KnowledgeSettings(
            enabled = prefs.getBoolean(KEY_KB_ENABLED, false),
            endpoint = prefs.getString(KEY_KB_ENDPOINT, KnowledgeSettings.DEFAULT_KB_ENDPOINT).orEmpty(),
        )
    }

    fun saveKnowledge(settings: KnowledgeSettings) {
        prefs.edit()
            .putBoolean(KEY_KB_ENABLED, settings.enabled)
            .putString(KEY_KB_ENDPOINT, settings.endpoint.trim())
            .apply()
    }

    fun saveSearch(settings: SearchSettings) {
        prefs.edit()
            .putString(KEY_SEARCH_MODE, settings.mode.name)
            .putString(KEY_BING_ENDPOINT, settings.endpoint.trim())
            .putString(KEY_BING_KEY, settings.apiKey.trim())
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "ai_exercise_settings"
        const val KEY_MODE = "ai_mode"
        const val KEY_BASE_URL = "ai_base_url"
        const val KEY_API_KEY = "ai_api_key"
        const val KEY_MODEL = "ai_model"
        const val KEY_VISION_MODE = "vision_mode"
        const val KEY_MODLENS_URL = "modlens_url"
        const val KEY_VISION_FALLBACK = "vision_fallback"
        const val KEY_SEARCH_MODE = "search_mode"
        const val KEY_BING_ENDPOINT = "bing_endpoint"
        const val KEY_BING_KEY = "bing_api_key"
        const val KEY_KB_ENABLED = "kb_enabled"
        const val KEY_KB_ENDPOINT = "kb_endpoint"
    }
}
