package com.example.aiexercise.platform

import android.content.Context
import com.example.aiexercise.ai.AiService
import com.example.aiexercise.ai.AiServiceFactory
import com.example.aiexercise.ai.AiSettings
import com.example.aiexercise.ai.KnowledgeBaseFactory
import com.example.aiexercise.ai.KnowledgeSettings
import com.example.aiexercise.ai.SearchService
import com.example.aiexercise.ai.SearchServiceFactory
import com.example.aiexercise.ai.SearchSettings
import com.example.aiexercise.ai.VisionSettings
import com.example.aiexercise.data.AppRepository
import com.example.aiexercise.data.store.JsonFileStore
import com.example.aiexercise.domain.KnowledgeBase
import java.io.File

/**
 * 手动依赖容器（本项目规模不需要 Hilt）：
 * 所有可替换实现都在这里装配 —— 存储、AI 服务、视觉引擎、搜索服务、图片缓存、设置。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val repository: AppRepository = AppRepository(
        JsonFileStore(File(appContext.filesDir, "data/app_data.json")),
    )

    val settingsStore: AiSettingsStore = AiSettingsStore(appContext)
    val imageStore: ImageStore = ImageStore(appContext)

    /* ---------------------------- 多页扫描 ---------------------------- */

    /** ML Kit 文档扫描（自动透视矫正 + 增强 + 多页），无 Play 服务时 UI 回退相册多选。 */
    val documentScanEngine: DocumentScanEngine = DocumentScanEngine(appContext)

    /** 本地矫正/增强：相册多选与文档扫描器页图转存都走这里。 */
    val imageEnhancer: ImageEnhancer = ImageEnhancer(appContext)

    /* ------------------------------ AI ------------------------------ */

    @Volatile
    var aiSettings: AiSettings = settingsStore.load()
        private set

    @Volatile
    var aiService: AiService = AiServiceFactory.create(aiSettings)
        private set

    fun saveSettings(settings: AiSettings): AiService {
        settingsStore.save(settings)
        aiSettings = settings
        aiService = AiServiceFactory.create(settings)
        visionEngine = VisionEngineFactory.create(appContext, visionSettings, settings)
        return aiService
    }

    /** 配置变化后重新读取设置（例如从设置页返回时）。 */
    fun refreshAiService(): AiService {
        val settings = settingsStore.load()
        aiSettings = settings
        aiService = AiServiceFactory.create(settings)
        visionEngine = VisionEngineFactory.create(appContext, visionSettings, settings)
        return aiService
    }

    /* ---------------------------- 视觉识别 ---------------------------- */

    /** 固定保留一个离线 ML Kit 引擎，作为 modlens 失败时的回退选项。 */
    val mlKitVisionEngine: VisionEngine = MlKitVisionEngine(appContext)

    @Volatile
    var visionSettings: VisionSettings = settingsStore.loadVision()
        private set

    @Volatile
    var visionEngine: VisionEngine = VisionEngineFactory.create(appContext, visionSettings, aiSettings)
        private set

    fun saveVision(settings: VisionSettings): VisionEngine {
        settingsStore.saveVision(settings)
        visionSettings = settings
        visionEngine = VisionEngineFactory.create(appContext, settings, aiSettings)
        return visionEngine
    }

    /* ---------------------------- 知识库 ---------------------------- */

    /**
     * 教材 / 教师用书知识库（可替换点）。
     *
     * 默认空实现（不联网）；在设置页打开后会装配 [com.example.aiexercise.ai.HttpKnowledgeBase]，
     * 通过本机 ChinaTextbook-KB HTTP 服务检索国家中小学智慧教育平台教材。
     */
    @Volatile
    var knowledgeSettings: KnowledgeSettings = settingsStore.loadKnowledge()
        private set

    @Volatile
    var knowledgeBase: KnowledgeBase = KnowledgeBaseFactory.create(knowledgeSettings)
        private set

    fun saveKnowledge(settings: KnowledgeSettings): KnowledgeBase {
        settingsStore.saveKnowledge(settings)
        knowledgeSettings = settings
        knowledgeBase = KnowledgeBaseFactory.create(settings)
        return knowledgeBase
    }

    /* ---------------------------- 联网搜索 ---------------------------- */

    @Volatile
    var searchSettings: SearchSettings = settingsStore.loadSearch()
        private set

    @Volatile
    var searchService: SearchService? = SearchServiceFactory.create(searchSettings)
        private set

    fun saveSearch(settings: SearchSettings): SearchService? {
        settingsStore.saveSearch(settings)
        searchSettings = settings
        searchService = SearchServiceFactory.create(settings)
        return searchService
    }
}
