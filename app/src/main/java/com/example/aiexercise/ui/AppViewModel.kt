package com.example.aiexercise.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiexercise.AiExerciseApp
import com.example.aiexercise.ai.AiJson
import com.example.aiexercise.ai.AiMode
import com.example.aiexercise.ai.AiSettings
import com.example.aiexercise.ai.AnalyzeRequest
import com.example.aiexercise.ai.KnowledgeSettings
import com.example.aiexercise.ai.LocalAiService
import com.example.aiexercise.ai.SearchHit
import com.example.aiexercise.ai.SegmentDraft
import com.example.aiexercise.ai.SegmentRequest
import com.example.aiexercise.ai.SearchMode
import com.example.aiexercise.ai.SearchSettings
import com.example.aiexercise.ai.VisionMode
import com.example.aiexercise.ai.VisionSettings
import com.example.aiexercise.data.model.AppData
import com.example.aiexercise.data.model.AskerProfile
import com.example.aiexercise.data.model.ExerciseSource
import com.example.aiexercise.data.model.GradeResult
import com.example.aiexercise.data.model.PracticePaper
import com.example.aiexercise.data.model.ResearchBundle
import com.example.aiexercise.data.model.ScanPage
import com.example.aiexercise.data.model.ScanPaper
import com.example.aiexercise.domain.AppError
import com.example.aiexercise.domain.PracticeCoach
import com.example.aiexercise.domain.HomeworkGuardAction
import com.example.aiexercise.domain.HomeworkGuardResult
import com.example.aiexercise.data.model.PracticeReview
import com.example.aiexercise.data.model.GuidedSession
import com.example.aiexercise.data.model.KnowledgePoint
import com.example.aiexercise.domain.ExerciseToolkit
import com.example.aiexercise.domain.ResearchAssistant
import com.example.aiexercise.platform.VisionDocument
import com.example.aiexercise.domain.ForgettingCurve
import com.example.aiexercise.domain.Outcome
import com.example.aiexercise.domain.ScanInput
import com.example.aiexercise.domain.ScanResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** 页面路由（单 Activity + 自研简单导航，避免额外依赖）。 */
enum class AppScreen { Home, Scan, Result, Segments, Paper, PracticeReview, Settings }

data class UiMessage(val text: String, val isError: Boolean = false, val id: Long = System.nanoTime())

data class UiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val ocrBusy: Boolean = false,
    val screen: AppScreen = AppScreen.Home,
    val data: AppData = AppData(),
    val message: UiMessage? = null,

    // 扫描表单
    val scanText: String = "",
    /** 视觉识别的排版 Markdown（不含纯文本兜底）。 */
    val scanMarkdown: String = "",
    val referenceAnswer: String = "",
    val studentAnswer: String = "",
    val imagePath: String? = null,
    val imageSource: ExerciseSource? = null,

    // 多页扫描（矫正增强 → 分割题目 → 每题独立成习题）
    val scanPages: List<ScanPage> = emptyList(),
    val scanPaperId: String = "",
    val segments: List<ScanResult> = emptyList(),
    /** 本次扫描按纸面作答批改的结果：exerciseId → 批改结果。 */
    val scanGrades: MutableMap<String, GradeResult> = mutableMapOf(),
    val segmentsBusy: Boolean = false,
    val segmentStage: String = "",

    // 最近一次识别 / 批改结果
    val lastScan: ScanResult? = null,
    val lastGrade: GradeResult? = null,
    /** 扫描过程中的阶段提示（检索解析 → 知识库 → AI 分类）。 */
    val scanStage: String = "",
    /** 知识点页的“按习题查看”过滤；null=全部。 */
    val filterExerciseId: String? = null,

    // 练习卷
    val paperAspect: String = "",
    val paperCount: Int = 5,
    val dueOnly: Boolean = false,
    val lastPaper: PracticePaper? = null,
    val lastPaperJson: String? = null,
    val showPaperJson: Boolean = false,
    /** 练习卷 JSON 是否展示“完整版（含题面/答案）”，默认展示答题卡结构规范。 */
    val showFullPaperJson: Boolean = false,

    // AI 设置
    val aiMode: AiMode = AiMode.OFFLINE,
    val aiLabel: String = "",
    val settingsBaseUrl: String = "",
    val settingsApiKey: String = "",
    val settingsModel: String = "",
    val toast: String? = null,

    // 视觉识别（扫描）设置
    val visionMode: VisionMode = VisionMode.MLKIT,
    val visionLabel: String = "",
    /** 当前设备是否支持 ML Kit 文档扫描（无 Google 服务的华为等设备为 false）。 */
    val documentScanAvailable: Boolean = true,
    val settingsModlensUrl: String = "",
    val settingsCloudVisionEndpoint: String = "",
    val settingsCloudVisionKey: String = "",
    val settingsCloudVisionModel: String = "",
    val settingsVisionFallback: Boolean = true,

    // 联网搜索设置与结果
    val searchMode: SearchMode = SearchMode.OFF,
    val settingsBingEndpoint: String = "",
    val settingsBingKey: String = "",
    val searchBusy: Boolean = false,
    val searchQuery: String = "",
    val searchHits: List<SearchHit> = emptyList(),

    // 教材 / 教师用书知识库设置
    val kbEnabled: Boolean = false,
    val settingsKbEndpoint: String = "",
    val kbLabel: String = "教材知识库（未接入）",

    // 练习卷答题卡扫描批改（模块三）
    val practicePages: List<ScanPage> = emptyList(),
    val practiceBusy: Boolean = false,
    val practiceStage: String = "",
    val lastReview: PracticeReview? = null,
    val reviewPoints: List<KnowledgePoint> = emptyList(),
    val guidedSession: GuidedSession? = null,
    val guidedInput: String = "",
    val guidedBusy: Boolean = false,
    /** 作业与知识点列表大相径庭时，等待用户确认的守卫。 */
    val pendingGuard: HomeworkGuardResult? = null,
    /** 守卫确认前暂存的单题扫描输入（用于「首次使用必问」后继续）。 */
    val pendingScanInput: ScanInput? = null,
    val pendingDrafts: List<SegmentDraft> = emptyList(),
    val pendingPages: List<ScanPage> = emptyList(),
    val pendingNote: String = "",
    val pendingWarnings: List<String> = emptyList(),

    // 提问者
    val askerName: String = "",
    val askerGrade: String = "",
    val askerGoal: String = "",
    val showClearDialog: Boolean = false,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as AiExerciseApp).container

    private val _ui = MutableStateFlow(
        UiState(
            aiLabel = container.aiService.displayName,
            aiMode = container.aiSettings.mode,
            settingsBaseUrl = container.aiSettings.baseUrl,
            settingsApiKey = container.aiSettings.apiKey,
            settingsModel = container.aiSettings.model,
            visionMode = container.visionSettings.mode,
            visionLabel = container.visionEngine.displayName,
            documentScanAvailable = container.documentScanEngine.isAvailable,
            settingsModlensUrl = container.visionSettings.modlensUrl,
            settingsCloudVisionEndpoint = container.visionSettings.cloudEndpoint,
            settingsCloudVisionKey = container.visionSettings.cloudApiKey,
            settingsCloudVisionModel = container.visionSettings.cloudModel,
            settingsVisionFallback = container.visionSettings.fallbackToMlKit,
            searchMode = container.searchSettings.mode,
            settingsBingEndpoint = container.searchSettings.endpoint,
            settingsBingKey = container.searchSettings.apiKey,
            kbEnabled = container.knowledgeSettings.enabled,
            settingsKbEndpoint = container.knowledgeSettings.endpoint,
            kbLabel = container.knowledgeBase.name,
        ),
    )
    val uiState: StateFlow<UiState> = _ui.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            when (val result = container.repository.load()) {
                is Outcome.Success -> {
                    val data = result.value
                    _ui.update {
                        it.copy(
                            loading = false,
                            data = data,
                            askerName = data.asker.name,
                            askerGrade = data.asker.gradeLevel,
                            askerGoal = data.asker.goal,
                        )
                    }
                }

                is Outcome.Failure -> _ui.update {
                    it.copy(loading = false, message = UiMessage(result.error.userMessage, true))
                }
            }
        }
    }

    /* ---------------------------- 通用 ---------------------------- */

    private fun setMessage(text: String, isError: Boolean = false) {
        _ui.update { it.copy(message = UiMessage(text, isError)) }
    }

    fun consumeMessage() = _ui.update { it.copy(message = null) }

    fun consumeToast() = _ui.update { it.copy(toast = null) }

    /** 供 UI 在复制/提示等场景主动弹一条 Snackbar。 */
    fun notify(text: String, isError: Boolean = false) = setMessage(text, isError)

    fun navigate(screen: AppScreen) = _ui.update { it.copy(screen = screen) }

    /** 保存到磁盘；失败时把可读错误塞进消息（不阻断内存状态更新）。 */
    private suspend fun persist(data: AppData): AppError? =
        (container.repository.save(data) as? Outcome.Failure)?.error

    /* ---------------------------- 扫描表单 ---------------------------- */

    fun updateScanText(value: String) = _ui.update { it.copy(scanText = value) }

    fun updateReferenceAnswer(value: String) = _ui.update { it.copy(referenceAnswer = value) }

    fun updateStudentAnswer(value: String) = _ui.update { it.copy(studentAnswer = value) }

    fun fillDemo() = _ui.update {
        it.copy(
            scanText = DEMO_QUESTION,
            scanMarkdown = "",
            referenceAnswer = DEMO_REFERENCE,
            studentAnswer = DEMO_STUDENT,
            imagePath = null,
            imageSource = null,
            lastScan = null,
            lastGrade = null,
        )
    }

    /** 返回扫描页；clearForm=true 时清空本次表单（“再扫一题”）。 */
    fun backToScan(clearForm: Boolean) = _ui.update {
        if (clearForm) {
            it.copy(
                screen = AppScreen.Home,
                scanText = "",
                referenceAnswer = "",
                studentAnswer = "",
                imagePath = null,
                imageSource = null,
                lastScan = null,
                lastGrade = null,
            )
        } else {
            it.copy(screen = AppScreen.Scan)
        }
    }

    /* ---------------------------- 扫描 / OCR ---------------------------- */

    /** 生成相机输出文件与给相机应用的授权 URI。 */
    fun createCameraOutput(): Pair<File, Uri> {
        val file = container.imageStore.newCameraFile()
        return file to container.imageStore.fileProviderUri(file)
    }

    fun onCameraCaptured(file: File) {
        viewModelScope.launch { recognize(file, ExerciseSource.OCR_CAMERA) }
    }

    fun onGalleryPicked(uri: Uri) {
        viewModelScope.launch {
            _ui.update { it.copy(ocrBusy = true) }
            val file = runCatching { container.imageStore.copyFromUri(uri) }.getOrElse { error ->
                _ui.update { it.copy(ocrBusy = false) }
                setMessage("读取所选图片失败：${error.message ?: "未知错误"}", true)
                return@launch
            }
            recognize(file, ExerciseSource.OCR_GALLERY)
        }
    }

    /* ------------------- 多页扫描（矫正增强 + 题目分割） ------------------- */

    /** 「文档扫描」入口：拿到系统扫描器的 IntentSender 后由 UI 启动（自动透视矫正+增强+多页）。 */
    fun startDocumentScan(activity: Activity, onIntent: (IntentSender) -> Unit) {
        viewModelScope.launch {
            when (val result = container.documentScanEngine.startIntent(activity)) {
                is Outcome.Success -> onIntent(result.value)
                is Outcome.Failure -> setMessage(result.error.userMessage, true)
            }
        }
    }

    /** 文档扫描器返回：页图已被矫正增强，直接转存进 App 目录。 */
    fun onDocumentScanResult(data: Intent?) {
        when (val parsed = container.documentScanEngine.parseResult(data)) {
            is Outcome.Failure -> setMessage(parsed.error.userMessage, true)
            is Outcome.Success -> viewModelScope.launch {
                val paths = mutableListOf<String>()
                for (uri in parsed.value) {
                    when (val saved = container.imageEnhancer.saveScannedPage(uri)) {
                        is Outcome.Success -> paths += saved.value
                        is Outcome.Failure -> setMessage(saved.error.userMessage, true)
                    }
                }
                appendPages(paths, ExerciseSource.OCR_CAMERA)
            }
        }
    }

    /** 相册多选：本地按 EXIF 摆正 + 增强后加入扫描页。 */
    fun onGalleryPickedMultiple(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _ui.update { it.copy(ocrBusy = true) }
            val paths = mutableListOf<String>()
            for (uri in uris) {
                when (val enhanced = container.imageEnhancer.enhance(uri)) {
                    is Outcome.Success -> paths += enhanced.value
                    is Outcome.Failure -> setMessage(enhanced.error.userMessage, true)
                }
            }
            _ui.update { it.copy(ocrBusy = false) }
            appendPages(paths, ExerciseSource.OCR_GALLERY)
        }
    }

    private fun appendPages(paths: List<String>, source: ExerciseSource) {
        if (paths.isEmpty()) return
        _ui.update { state ->
            val startIndex = state.scanPages.size
            val newPages = paths.mapIndexed { offset, path ->
                ScanPage(
                    id = UUID.randomUUID().toString(),
                    index = startIndex + offset + 1,
                    imagePath = path,
                    corrected = true,
                )
            }
            state.copy(
                scanPages = state.scanPages + newPages,
                scanPaperId = state.scanPaperId.ifBlank { UUID.randomUUID().toString() },
                imagePath = newPages.last().imagePath,
                imageSource = source,
                message = UiMessage("已添加 ${newPages.size} 页，共 ${state.scanPages.size + newPages.size} 页，可点「识别并分割题目」"),
            )
        }
    }

    fun removeScanPage(pageId: String) {
        _ui.update { state ->
            val remaining = state.scanPages
                .filterNot { it.id == pageId }
                .mapIndexed { i, page -> page.copy(index = i + 1) }
            state.copy(
                scanPages = remaining,
                message = if (remaining.isEmpty()) UiMessage("已清空扫描页") else null,
            )
        }
    }

    fun clearScanPages() = _ui.update {
        it.copy(scanPages = emptyList(), scanPaperId = "", segments = emptyList())
    }

    /**
     * 多页 → 逐页 OCR → AI 分割题目（跨页题合并为一条）→ 每道题独立走完整流水线，
     * 即每道题都会单独生成/维护自己的「不会的知识点」与遗忘曲线计时器。
     */
    fun ocrAndSegment() {
        val current = _ui.value
        if (current.segmentsBusy || current.busy) return
        if (current.scanPages.isEmpty()) {
            setMessage("请先添加扫描页：文档扫描或相册多选。", true)
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(segmentsBusy = true, segmentStage = "开始识别…") }
            val pages = current.scanPages
            val pageTexts = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            for ((index, page) in pages.withIndex()) {
                _ui.update { it.copy(segmentStage = "正在识别第 ${index + 1}/${pages.size} 页…") }
                when (val recognized = recognizeText(File(page.imagePath))) {
                    is Outcome.Success -> pageTexts += recognized.value.plainText
                    is Outcome.Failure -> {
                        pageTexts += ""
                        warnings += "第 ${index + 1} 页识别失败：${recognized.error.userMessage}"
                    }
                }
            }
            pageTexts.forEachIndexed { i, text -> android.util.Log.d("ScanFlow", "page ${i + 1} OCR:\n$text") }
            if (pageTexts.all { it.isBlank() }) {
                _ui.update {
                    it.copy(
                        segmentsBusy = false,
                        segmentStage = "",
                        message = UiMessage(
                            warnings.firstOrNull() ?: "没有识别到文字，请重拍或改用相册/手动输入。",
                            true,
                        ),
                    )
                }
                return@launch
            }

            _ui.update { it.copy(segmentStage = "正在分割题目（自动合并跨页题）…") }
            val request = SegmentRequest(
                pagesText = pageTexts.mapIndexed { i, text -> "=== 第 ${i + 1} 页 ===\n$text" }
                    .joinToString("\n\n"),
                pages = pageTexts,
                pageCount = pageTexts.size,
                subjectHint = current.data.asker.gradeLevel,
                asker = current.data.asker,
            )
            var note = ""
            val drafts = when (val segmented = container.aiService.segmentQuestions(request)) {
                is Outcome.Success -> segmented.value.questions
                is Outcome.Failure -> {
                    note = "AI 分割不可用，已用离线规则分割（${segmented.error.userMessage}）"
                    when (val offline = LocalAiService().segmentQuestions(request)) {
                        is Outcome.Success -> offline.value.questions
                        is Outcome.Failure -> emptyList()
                    }
                }
            }
            if (drafts.isEmpty()) {
                _ui.update {
                    it.copy(
                        segmentsBusy = false,
                        segmentStage = "",
                        message = UiMessage(
                            "没有从扫描页里分割出题目，请确认图片清晰、题目带题号（如“1.”“第1题”）。",
                            true,
                        ),
                    )
                }
                return@launch
            }

            val guard = PracticeCoach(container.aiService)
                .guardHomework(current.data, drafts.map { it.questionText })
            if (guard.action == HomeworkGuardAction.ASK) {
                _ui.update {
                    it.copy(
                        segmentsBusy = false,
                        segmentStage = "",
                        pendingGuard = guard,
                        pendingDrafts = drafts,
                        pendingPages = pages,
                        pendingNote = note,
                        pendingWarnings = warnings,
                        message = UiMessage(guard.message, false),
                    )
                }
                return@launch
            }

            processDrafts(
                baseData = current.data,
                drafts = drafts,
                pages = pages,
                note = note,
                warningsSeed = warnings,
                pageCount = pages.size,
                subjectHint = request.subjectHint,
            )
        }
    }

    /** 确认守卫后（用心做 / 忽略）继续处理已分割好的题目。 */
    fun resolveGuard(useThisScan: Boolean) {
        val current = _ui.value
        val guard = current.pendingGuard ?: return
        val drafts = current.pendingDrafts
        val pages = current.pendingPages
        val note = current.pendingNote
        val warnings = current.pendingWarnings
        val pendingScan = current.pendingScanInput
        _ui.update {
            it.copy(
                pendingGuard = null,
                pendingDrafts = emptyList(),
                pendingPages = emptyList(),
                pendingScanInput = null,
            )
        }
        viewModelScope.launch {
            val coach = PracticeCoach(container.aiService)
            if (!useThisScan) {
                val data = coach.markHomeworkAsked(current.data, ignored = true)
                val saveError = persist(data)
                _ui.update {
                    it.copy(
                        data = data,
                        message = UiMessage(
                            "已按「没用心做」处理：本次扫描被忽略，不会写进不会的知识点列表。" +
                                (saveError?.let { error -> "（保存失败：${error.userMessage}）" } ?: ""),
                            saveError != null,
                        ),
                    )
                }
                return@launch
            }
            val data = coach.markHomeworkAsked(current.data, ignored = false)
            persist(data)
            _ui.update { it.copy(data = data) }
            if (pendingScan != null) {
                performScan(pendingScan)
                return@launch
            }
            processDrafts(
                baseData = data,
                drafts = drafts,
                pages = pages,
                note = note,
                warningsSeed = warnings,
                pageCount = pages.size,
                subjectHint = current.data.asker.gradeLevel,
            )
        }
    }

    private suspend fun processDrafts(
        baseData: com.example.aiexercise.data.model.AppData,
        drafts: List<SegmentDraft>,
        pages: List<ScanPage>,
        note: String,
        warningsSeed: List<String>,
        pageCount: Int,
        subjectHint: String,
    ) {
        val warnings = warningsSeed.toMutableList()
        val toolkit = newToolkit()
        val paperId = _ui.value.scanPaperId.ifBlank { UUID.randomUUID().toString() }
        var data = baseData
        val results = mutableListOf<ScanResult>()
        for ((index, draft) in drafts.withIndex()) {
            _ui.update { it.copy(segmentStage = "正在分析第 ${index + 1}/${drafts.size} 题的不会知识点…") }
            val pageFrom = draft.pageFrom.coerceIn(1, pageCount)
            val pageTo = draft.pageTo.coerceIn(pageFrom, pageCount)
            val input = ScanInput(
                rawText = draft.questionText,
                referenceAnswer = draft.referenceAnswer,
                studentAnswer = draft.studentAnswer,
                source = ExerciseSource.OCR_GALLERY,
                imagePath = pages[pageFrom - 1].imagePath,
                subjectHint = subjectHint,
                sectionTitle = draft.sectionTitle,
                pageIndexes = (pageFrom..pageTo).toList(),
                crossPage = pageTo > pageFrom,
                scanPaperId = paperId,
            )
            when (val scanned = toolkit.scan(data, input)) {
                is Outcome.Success -> {
                    results += scanned.value
                    data = scanned.value.data
                    scanned.value.warning?.let { warning -> warnings += "第 ${index + 1} 题：${warning.userMessage}" }
                }

                is Outcome.Failure -> warnings += "第 ${index + 1} 题分析失败：${scanned.error.userMessage}"
            }
        }
        if (results.isEmpty()) {
            _ui.update {
                it.copy(
                    segmentsBusy = false,
                    segmentStage = "",
                    message = UiMessage(warnings.firstOrNull() ?: "题目分割成功，但逐题分析都失败了。", true),
                )
            }
            return
        }
        val crossPageCount = drafts.count { it.pageTo > it.pageFrom }
        val batch = ScanPaper(
            id = paperId,
            createdAt = System.currentTimeMillis(),
            pages = pages,
            exerciseIds = results.map { it.exercise.id },
            warnings = warnings,
        )
        data = data.copy(scanPapers = (data.scanPapers + batch).takeLast(MAX_STORED_SCAN_PAPERS))
        val saveError = persist(data)
        val message = buildString {
            append("已把 ${results.size} 道题分别建为习题")
            if (crossPageCount > 0) append("（其中 $crossPageCount 道跨页题已合并）")
            if (note.isNotBlank()) append("；$note")
            if (warnings.isNotEmpty()) append("；${warnings.size} 条提示")
            saveError?.let { append("；但保存失败：${it.userMessage}") }
        }
        _ui.update {
            it.copy(
                segmentsBusy = false,
                segmentStage = "",
                data = data,
                segments = results,
                scanPaperId = paperId,
                screen = AppScreen.Segments,
                message = UiMessage(message, saveError != null),
            )
        }
    }

    /** 平时的扫描也要批改：用分割时抽取的纸面作答，逐题批改。 */
    fun gradeScannedSegments() {
        val current = _ui.value
        if (current.segments.isEmpty() || current.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, scanStage = "准备批改…") }
            val toolkit = newToolkit()
            var data = current.data
            val graded = current.scanGrades.toMutableMap()
            val failures = mutableListOf<String>()
            current.segments.forEachIndexed { index, segment ->
                _ui.update { it.copy(scanStage = "正在批改第 ${index + 1}/${current.segments.size} 题…") }
                when (val result = toolkit.grade(data, segment.exercise.id)) {
                    is Outcome.Success -> {
                        data = result.value.data
                        graded[segment.exercise.id] = result.value.grade
                    }
                    is Outcome.Failure -> failures += "第 ${index + 1} 题：${result.error.userMessage}"
                }
            }
            val saveError = persist(data)
            val passed = graded.values.count { it.passed }
            _ui.update {
                it.copy(
                    busy = false,
                    scanStage = "",
                    data = data,
                    scanGrades = graded,
                    message = UiMessage(
                        "批改完成：${graded.size}/${current.segments.size} 题有作答并被批改，对 $passed 题。" +
                            (failures.firstOrNull()?.let { "（${it}）" } ?: "") +
                            (saveError?.let { "；保存失败：${it.userMessage}" } ?: ""),
                        failures.isNotEmpty(),
                    ),
                )
            }
        }
    }

    fun openSegment(segment: ScanResult) {
        val current = _ui.value
        _ui.update {
            it.copy(
                screen = AppScreen.Result,
                lastScan = segment,
                lastGrade = current.data.grades.lastOrNull { grade -> grade.exerciseId == segment.exercise.id },
            )
        }
    }

    fun openSegments() = _ui.update { it.copy(screen = AppScreen.Segments) }

    /** 回到最近一次练习批改结果页。 */
    fun openLastReview() = _ui.update { it.copy(screen = AppScreen.PracticeReview) }

    /** 从结果页返回：多页分割流程回到题目列表，単题流程回扫描页。 */
    fun backFromResult() = _ui.update {
        if (it.segments.isNotEmpty()) it.copy(screen = AppScreen.Segments) else it.copy(screen = AppScreen.Home)
    }

    /* -------------------- 模块三：练习卷扫描批改 / 订正 / 引导提问 -------------------- */

    /** 选择答题卡照片（可多页）：本地矫正增强后暂存，等待「开始批改」。 */
    fun onPracticePagesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _ui.update { it.copy(practiceBusy = true, practiceStage = "正在保存答题卡照片…") }
            val added = mutableListOf<ScanPage>()
            val startIndex = _ui.value.practicePages.size
            uris.forEachIndexed { i, uri ->
                _ui.update { it.copy(practiceStage = "正在处理第 ${i + 1}/${uris.size} 页…") }
                when (val enhanced = container.imageEnhancer.enhance(uri, "answer")) {
                    is Outcome.Success -> added += ScanPage(
                        id = UUID.randomUUID().toString(),
                        index = startIndex + i + 1,
                        imagePath = enhanced.value,
                        corrected = true,
                    )

                    is Outcome.Failure -> Unit
                }
            }
            _ui.update {
                it.copy(
                    practiceBusy = false,
                    practiceStage = "",
                    practicePages = it.practicePages + added,
                    message = UiMessage(
                        if (added.isEmpty()) "没有添加成功，请重试或检查相册权限。" else "已添加 ${added.size} 页答题卡，点「开始批改」即可。",
                        added.isEmpty(),
                    ),
                )
            }
        }
    }

    fun clearPracticePages() = _ui.update { it.copy(practicePages = emptyList(), practiceStage = "") }

    /** 扫描练习卷答题卡 → 逐题批改 → 列出仍不会的知识点。 */
    fun reviewPractice(paperId: String) {
        val current = _ui.value
        if (current.practiceBusy) return
        if (current.practicePages.isEmpty()) {
            setMessage("请先添加答题卡照片（可多页），再开始批改。", true)
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(practiceBusy = true, practiceStage = "正在识别答题卡…") }
            val texts = mutableListOf<String>()
            current.practicePages.forEachIndexed { i, page ->
                _ui.update { it.copy(practiceStage = "正在识别第 ${i + 1}/${current.practicePages.size} 页答题卡…") }
                when (val recognized = recognizeText(File(page.imagePath))) {
                    is Outcome.Success -> texts += recognized.value.plainText
                    is Outcome.Failure -> {
                        texts += ""
                        notify("第 ${i + 1} 页识别失败：${recognized.error.userMessage}", true)
                    }
                }
            }
            _ui.update { it.copy(practiceStage = "正在逐题批改并整理不会的知识点…") }
            val outcome = PracticeCoach(container.aiService).review(current.data, paperId, texts)
            when (outcome) {
                is Outcome.Success -> {
                    val value = outcome.value
                    val saveError = persist(value.data)
                    _ui.update {
                        it.copy(
                            practiceBusy = false,
                            practiceStage = "",
                            data = value.data,
                            lastReview = value.review,
                            reviewPoints = value.knowledgePoints,
                            guidedSession = null,
                            guidedInput = "",
                            practicePages = emptyList(),
                            screen = AppScreen.PracticeReview,
                            message = UiMessage(
                                buildString {
                                    append("批改完成：${value.review.score} 分")
                                    append(if (value.review.wrong.isEmpty()) "，全部做对" else "，${value.review.wrong.size} 题仍需订正")
                                    saveError?.let { append("；保存失败：${it.userMessage}") }
                                },
                                saveError != null,
                            ),
                        )
                    }
                }

                is Outcome.Failure -> _ui.update {
                    it.copy(practiceBusy = false, practiceStage = "", message = UiMessage(outcome.error.userMessage, true))
                }
            }
        }
    }

    /** 对某道错题发起引导式提问（订正质量太低会被拒绝）。 */
    fun openGuided(questionId: String) {
        val current = _ui.value
        val review = current.lastReview ?: return
        when (val result = PracticeCoach(container.aiService).openGuided(current.data, review.id, questionId)) {
            is Outcome.Success -> {
                viewModelScope.launch {
                    persist(result.value.data)
                    _ui.update {
                        it.copy(data = result.value.data, guidedSession = result.value.session, guidedInput = "")
                    }
                }
            }

            is Outcome.Failure -> setMessage(result.error.userMessage, true)
        }
    }

    fun closeGuided() = _ui.update { it.copy(guidedSession = null, guidedInput = "") }

    fun updateGuidedInput(value: String) = _ui.update { it.copy(guidedInput = value) }

    /** 回答引导式提问：AI 给下一步引导；收尾后过程并入不会的知识点列表。 */
    fun replyGuided() {
        val current = _ui.value
        val session = current.guidedSession ?: return
        val text = current.guidedInput.trim()
        if (text.isEmpty()) {
            setMessage("先写一句你自己的想法，再发送。", true)
            return
        }
        if (current.guidedBusy) return
        viewModelScope.launch {
            _ui.update { it.copy(guidedBusy = true, guidedInput = "") }
            when (val result = PracticeCoach(container.aiService).replyGuided(current.data, session.id, text)) {
                is Outcome.Success -> {
                    val saveError = persist(result.value.data)
                    _ui.update {
                        it.copy(
                            guidedBusy = false,
                            data = result.value.data,
                            guidedSession = result.value.session,
                            message = if (result.value.session.resolved) {
                                UiMessage("这道题已收尾：提问中梳理出的薄弱点已并入不会的知识点列表。" + (saveError?.let { e -> "（保存失败：${e.userMessage}）" } ?: ""), saveError != null)
                            } else {
                                null
                            },
                        )
                    }
                }

                is Outcome.Failure -> _ui.update {
                    it.copy(guidedBusy = false, message = UiMessage(result.error.userMessage, true))
                }
            }
        }
    }

    private fun newToolkit(): ExerciseToolkit = ExerciseToolkit(
        ai = container.aiService,
        research = ResearchAssistant(
            search = container.searchService,
            knowledgeBase = container.knowledgeBase,
            ai = container.aiService,
        ),
    )

    private suspend fun recognizeText(file: File): Outcome<VisionDocument> {
        val uri = Uri.fromFile(file)
        val primary = container.visionEngine.recognize(uri)
        if (primary is Outcome.Failure &&
            container.visionSettings.mode != VisionMode.MLKIT &&
            container.visionSettings.fallbackToMlKit
        ) {
            val fallback = container.mlKitVisionEngine.recognize(uri)
            if (fallback is Outcome.Success) return fallback
        }
        return primary
    }

    private suspend fun recognize(file: File, source: ExerciseSource) {
        val uri = Uri.fromFile(file)
        val primary = container.visionEngine.recognize(uri)
        var result: Outcome<VisionDocument> = primary
        var fallbackNote: String? = null
        // modlens 不可用时自动回退离线 ML Kit（用户可在设置里关闭该行为）
        if (primary is Outcome.Failure &&
            container.visionSettings.mode != VisionMode.MLKIT &&
            container.visionSettings.fallbackToMlKit
        ) {
            val fallback = container.mlKitVisionEngine.recognize(uri)
            if (fallback is Outcome.Success) {
                result = fallback
                fallbackNote = "视觉接口不可用，已自动回退 ML Kit（${primary.error.userMessage}）"
            }
        }
        val finalResult = result
        when (finalResult) {
            is Outcome.Success -> _ui.update {
                it.copy(
                    ocrBusy = false,
                    imagePath = file.absolutePath,
                    imageSource = source,
                    scanText = finalResult.value.plainText,
                    scanMarkdown = finalResult.value.markdown,
                    message = UiMessage(
                        fallbackNote ?: "已用 ${container.visionEngine.displayName} 识别 ${finalResult.value.plainText.length} 个字符" +
                            "（参考答案由 AI 自动生成）",
                        false,
                    ),
                )
            }

            is Outcome.Failure -> _ui.update {
                it.copy(
                    ocrBusy = false,
                    imagePath = file.absolutePath,
                    imageSource = source,
                    message = UiMessage("${finalResult.error.userMessage}（可改用手动输入）", true),
                )
            }
        }
    }

    /* ---------------------------- 知识点分析 ---------------------------- */

    fun scan() {
        val current = _ui.value
        if (current.busy) return
        val text = current.scanText.trim()
        if (text.length < MIN_QUESTION_LENGTH) {
            setMessage("题目内容太短（至少 $MIN_QUESTION_LENGTH 个字符），请先扫描或手动输入题目。", true)
            return
        }
        val input = ScanInput(
            rawText = text,
            questionMarkdown = current.scanMarkdown,
            referenceAnswer = current.referenceAnswer,
            studentAnswer = current.studentAnswer,
            source = current.imageSource ?: ExerciseSource.MANUAL,
            imagePath = current.imagePath,
        )
        // 作业与「不会的知识点列表」是否对得上：首次使用必问，之后大相径庭时再问一次
        val guard = PracticeCoach(container.aiService).guardHomework(current.data, listOf(text))
        if (guard.action == HomeworkGuardAction.ASK) {
            _ui.update { it.copy(pendingGuard = guard, pendingScanInput = input) }
            return
        }
        performScan(input)
    }

    /** 结果页「重新扫描此题」：用该题已有的文本/页码/批次再走一遍（用于验证同一习题特判）。 */
    fun rescanLastExercise() {
        val current = _ui.value
        if (current.busy) return
        val exercise = current.lastScan?.exercise
        if (exercise == null) {
            setMessage("还没有识别结果，请先扫描习题。", true)
            return
        }
        performScan(
            ScanInput(
                rawText = exercise.questionText,
                questionMarkdown = exercise.questionMarkdown,
                referenceAnswer = exercise.referenceAnswer,
                studentAnswer = exercise.studentAnswer,
                source = exercise.source,
                imagePath = exercise.imagePath,
                sectionTitle = exercise.sectionTitle,
                pageIndexes = exercise.pageIndexes,
                crossPage = exercise.crossPage,
                scanPaperId = exercise.scanPaperId,
            ),
        )
    }

    private fun performScan(input: ScanInput) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, scanStage = "准备分析…") }
            val data = _ui.value.data
            val toolkit = newToolkit()
            val onStage: (String) -> Unit = { stage -> _ui.update { it.copy(scanStage = stage) } }
            when (val result = toolkit.scan(data, input, onStage)) {
                is Outcome.Success -> {
                    val value = result.value
                    val saveError = persist(value.data)
                    val researchNote = when {
                        value.research == null -> ""
                        value.research.hasAnyEvidence -> "，已自动检索并筛出本题解析"
                        value.research.webState == ResearchBundle.WEB_NO_MATCH -> "，网络结果未匹配到本题解析"
                        else -> "，资料检索已降级（不影响知识点分类）"
                    }
                    val base = if (value.isDuplicate) {
                        "同一习题特判：复用 ${value.knowledgePoints.size} 个知识点，刷新 ${value.refreshedKnowledgePointCount} 个遗忘曲线计时器（未重复创建）"
                    } else {
                        "识别成功：新增 ${value.knowledgePoints.size} 个不会的知识点，已开始遗忘曲线计时$researchNote"
                    }
                    val warning = value.warning?.let { "（合并警告：${it.userMessage}）" }.orEmpty()
                    val saveSuffix = saveError?.let { "；但保存失败：${it.userMessage}" }.orEmpty()
                    _ui.update {
                        it.copy(
                            busy = false,
                            scanStage = "",
                            data = value.data,
                            lastScan = value,
                            lastGrade = null,
                            screen = AppScreen.Result,
                            message = UiMessage(base + warning + saveSuffix, saveError != null),
                        )
                    }
                }

                is Outcome.Failure -> _ui.update {
                    it.copy(busy = false, scanStage = "", message = UiMessage(result.error.userMessage, true))
                }
            }
        }
    }

    /** 知识点页：只看某道习题相关的不会知识点（传 null 恢复全部）。 */
    fun filterKnowledgeByExercise(exerciseId: String?) {
        _ui.update { it.copy(filterExerciseId = exerciseId) }
    }

    fun generatePaperFromResult() {
        val current = _ui.value
        val top = current.lastScan?.knowledgePoints?.firstOrNull()
        if (top == null) {
            setMessage("请先扫描习题，识别出知识点后才能出卷。", true)
            return
        }
        _ui.update { it.copy(paperAspect = top.aspect) }
        generatePaper(top.aspect)
    }

    /* ---------------------------- 批改 ---------------------------- */

    fun grade() {
        val current = _ui.value
        val scan = current.lastScan
        if (scan == null) {
            setMessage("请先扫描习题。", true)
            return
        }
        if (current.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val toolkit = ExerciseToolkit(container.aiService)
            when (val result = toolkit.grade(current.data, scan.exercise.id, current.studentAnswer)) {
                is Outcome.Success -> {
                    val value = result.value
                    val saveError = persist(value.data)
                    val tail = saveError?.let { "；但保存失败：${it.userMessage}" }.orEmpty()
                    _ui.update {
                        it.copy(
                            busy = false,
                            data = value.data,
                            lastGrade = value.grade,
                            message = UiMessage(
                                "批改完成：${value.grade.score} 分，相关不会知识点 ${value.relatedKnowledgePoints.size} 个$tail",
                                !value.grade.passed || saveError != null,
                            ),
                        )
                    }
                }

                is Outcome.Failure -> _ui.update {
                    it.copy(busy = false, message = UiMessage(result.error.userMessage, true))
                }
            }
        }
    }

    /* ---------------------------- 复习 / 遗忘曲线 ---------------------------- */

    fun review(knowledgePointId: String, quality: Int) {
        val current = _ui.value
        viewModelScope.launch {
            val toolkit = ExerciseToolkit(container.aiService)
            when (val result = toolkit.recordReview(current.data, knowledgePointId, quality)) {
                is Outcome.Success -> {
                    val value = result.value
                    val saveError = persist(value.data)
                    val next = ForgettingCurve.nextReviewLabel(value.knowledgePoint.timer, System.currentTimeMillis())
                    _ui.update {
                        it.copy(
                            data = value.data,
                            message = UiMessage(
                                "已记录复习评分 $quality：「${value.knowledgePoint.title}」$next" +
                                    (saveError?.let { "（保存失败：${it.userMessage}）" }.orEmpty()),
                                saveError != null,
                            ),
                        )
                    }
                }

                is Outcome.Failure -> setMessage(result.error.userMessage, true)
            }
        }
    }

    /* ---------------------------- 练习卷 ---------------------------- */

    fun updatePaperAspect(value: String) = _ui.update { it.copy(paperAspect = value) }

    fun updatePaperCount(value: Int) = _ui.update { it.copy(paperCount = value.coerceIn(1, 20)) }

    fun toggleDueOnly() = _ui.update { it.copy(dueOnly = !it.dueOnly) }

    fun toggleShowPaperJson() = _ui.update { it.copy(showPaperJson = !it.showPaperJson) }

    /** 切换“完整练习卷 JSON（含 Markdown 题面/答案）”的展示。 */
    fun toggleShowFullPaperJson() = _ui.update { it.copy(showFullPaperJson = !it.showFullPaperJson) }

    fun generatePaper(aspect: String = _ui.value.paperAspect) {
        val current = _ui.value
        if (current.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val toolkit = ExerciseToolkit(container.aiService)
            when (val result = toolkit.generatePaper(current.data, aspect, current.paperCount, current.dueOnly)) {
                is Outcome.Success -> {
                    val value = result.value
                    val saveError = persist(value.data)
                    _ui.update {
                        it.copy(
                            busy = false,
                            data = value.data,
                            lastPaper = value.paper,
                            lastPaperJson = value.json,
                            paperAspect = aspect,
                            screen = AppScreen.Paper,
                            showPaperJson = false,
                            message = UiMessage(
                                "已生成「${value.paper.aspect}」练习卷：${value.paper.questions.size} 题，" +
                                    "覆盖 ${value.selectedKnowledgePoints.size} 个按遗忘曲线挑选的知识点" +
                                    (saveError?.let { "（保存失败：${it.userMessage}）" }.orEmpty()),
                                saveError != null,
                            ),
                        )
                    }
                }

                is Outcome.Failure -> _ui.update {
                    it.copy(busy = false, message = UiMessage(result.error.userMessage, true))
                }
            }
        }
    }

    fun openPaper(paperId: String) {
        val paper = _ui.value.data.papers.firstOrNull { it.id == paperId } ?: return
        _ui.update {
            it.copy(
                lastPaper = paper,
                lastPaperJson = AiJson.encodePaper(paper),
                paperAspect = paper.aspect,
                showPaperJson = false,
            )
        }
    }

    /* ---------------------------- 设置 ---------------------------- */

    fun saveSettings(mode: AiMode, baseUrl: String, apiKey: String, model: String) {
        val settings = AiSettings(mode = mode, baseUrl = baseUrl, apiKey = apiKey, model = model)
        val service = container.saveSettings(settings)
        _ui.update {
            it.copy(
                aiMode = settings.mode,
                aiLabel = service.displayName,
                settingsBaseUrl = settings.baseUrl,
                settingsApiKey = settings.apiKey,
                settingsModel = settings.model,
                message = UiMessage("AI 设置已保存：${service.displayName}", false),
            )
        }
    }

    fun testAi() {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val request = AnalyzeRequest(
                questionText = "测试题：已知 1+1=2，求 2+2 的值。",
                referenceAnswer = "4",
                studentAnswer = "4",
                asker = _ui.value.data.asker,
            )
            when (val result = container.aiService.analyze(request)) {
                is Outcome.Success -> _ui.update {
                    it.copy(
                        busy = false,
                        message = UiMessage(
                            "AI 连通性正常：返回 ${result.value.knowledgePoints.size} 个知识点草稿（${container.aiService.displayName}）",
                            false,
                        ),
                    )
                }

                is Outcome.Failure -> _ui.update {
                    it.copy(busy = false, message = UiMessage("AI 测试失败：${result.error.userMessage}", true))
                }
            }
        }
    }

    fun saveVisionSettings(
        mode: VisionMode,
        modlensUrl: String,
        fallbackToMlKit: Boolean,
        cloudEndpoint: String,
        cloudApiKey: String,
        cloudModel: String,
    ) {
        val settings = VisionSettings(
            mode = mode,
            modlensUrl = modlensUrl.trim().ifBlank { VisionSettings.DEFAULT_MODLENS_URL },
            fallbackToMlKit = fallbackToMlKit,
            cloudEndpoint = cloudEndpoint.trim().ifBlank { "https://api.openai.com/v1" },
            cloudApiKey = cloudApiKey.trim(),
            cloudModel = cloudModel.trim().ifBlank { "gpt-4o-mini" },
        )
        val engine = container.saveVision(settings)
        _ui.update {
            it.copy(
                visionMode = settings.mode,
                visionLabel = engine.displayName,
                settingsModlensUrl = settings.modlensUrl,
                settingsCloudVisionEndpoint = settings.cloudEndpoint,
                settingsCloudVisionKey = settings.cloudApiKey,
                settingsCloudVisionModel = settings.cloudModel,
                settingsVisionFallback = settings.fallbackToMlKit,
                message = UiMessage("视觉引擎已保存：${engine.displayName}", false),
            )
        }
    }

    fun updateSettingsModlensUrl(value: String) = _ui.update { it.copy(settingsModlensUrl = value) }

    fun updateVisionFallback(value: Boolean) = _ui.update { it.copy(settingsVisionFallback = value) }

    fun saveSearchSettings(mode: SearchMode, endpoint: String, apiKey: String) {
        val settings = SearchSettings(
            mode = mode,
            endpoint = endpoint.trim().ifBlank { SearchSettings.DEFAULT_BING_ENDPOINT },
            apiKey = apiKey.trim(),
        )
        val service = container.saveSearch(settings)
        _ui.update {
            it.copy(
                searchMode = settings.mode,
                settingsBingEndpoint = settings.endpoint,
                settingsBingKey = settings.apiKey,
                searchHits = emptyList(),
                searchQuery = "",
                message = UiMessage(
                    if (service == null) "联网搜索已关闭" else "联网搜索已启用：${service.displayName}",
                    false,
                ),
            )
        }
    }

    fun updateSettingsBingEndpoint(value: String) = _ui.update { it.copy(settingsBingEndpoint = value) }

    fun updateSettingsKbEndpoint(value: String) = _ui.update { it.copy(settingsKbEndpoint = value) }

    fun saveKnowledgeSettings(enabled: Boolean, endpoint: String) {
        val settings = KnowledgeSettings(
            enabled = enabled,
            endpoint = endpoint.trim().ifBlank { KnowledgeSettings.DEFAULT_KB_ENDPOINT },
        )
        val base = container.saveKnowledge(settings)
        _ui.update {
            it.copy(
                kbEnabled = settings.enabled,
                settingsKbEndpoint = settings.endpoint,
                kbLabel = base.name,
                message = UiMessage(
                    if (settings.enabled) {
                        "教材知识库已启用：${base.name}（端点 ${settings.endpoint}）"
                    } else {
                        "教材知识库已关闭（分类前只做网络检索）"
                    },
                    false,
                ),
            )
        }
    }

    fun updateSettingsBingKey(value: String) = _ui.update { it.copy(settingsBingKey = value) }

    /**
     * 用必应搜索与本题相关的资料 / 同类题：
     * 关键词 = 题目正文（截断）+ 关联知识点标题。
     */
    fun searchRelated() {
        val current = _ui.value
        if (current.searchBusy) return
        val service = container.searchService
        if (service == null) {
            setMessage("联网搜索未启用：请到「设置 → 联网搜索」选择「必应」并填写 API Key。", true)
            return
        }
        val exercise = current.lastScan?.exercise
        if (exercise == null) {
            setMessage("请先扫描并分析一道习题，再搜索相关资料。", true)
            return
        }
        val titles = (
            current.lastGrade?.relatedKnowledgePointTitles.orEmpty() +
                current.data.knowledgePoints
                    .filter { it.sourceExerciseIds.contains(exercise.id) }
                    .map { it.title }
            ).distinct()
        val query = buildSearchQuery(exercise.questionText, titles)
        viewModelScope.launch {
            _ui.update { it.copy(searchBusy = true) }
            when (val result = service.search(query, count = SEARCH_RESULT_COUNT)) {
                is Outcome.Success -> _ui.update {
                    it.copy(
                        searchBusy = false,
                        searchQuery = query,
                        searchHits = result.value,
                        message = UiMessage(
                            if (result.value.isEmpty()) "必应没有返回相关资料，可换个知识点后再试" else "已找到 ${result.value.size} 条相关资料",
                            result.value.isEmpty(),
                        ),
                    )
                }

                is Outcome.Failure -> _ui.update {
                    it.copy(searchBusy = false, message = UiMessage(result.error.userMessage, true))
                }
            }
        }
    }

    private fun buildSearchQuery(questionText: String, titles: List<String>): String {
        val head = questionText.replace(Regex("\\s+"), " ").trim().take(60)
        val keywords = titles.take(2).joinToString(" ")
        return listOf(head, keywords, "解题思路").filter { it.isNotBlank() }.joinToString(" ")
    }

    fun saveAsker(name: String, gradeLevel: String, goal: String) {
        viewModelScope.launch {
            val data = _ui.value.data.copy(asker = AskerProfile(name = name.trim(), gradeLevel = gradeLevel.trim(), goal = goal.trim()))
            val saveError = persist(data)
            _ui.update {
                it.copy(
                    data = data,
                    message = UiMessage(
                        saveError?.let { "提问者信息保存失败：${it.userMessage}" } ?: "提问者信息已保存（AI 将据此调整知识点与题目的难度）",
                        saveError != null,
                    ),
                )
            }
        }
    }

    fun updateAskerName(value: String) = _ui.update { it.copy(askerName = value) }

    fun updateAskerGrade(value: String) = _ui.update { it.copy(askerGrade = value) }

    fun updateAskerGoal(value: String) = _ui.update { it.copy(askerGoal = value) }

    fun requestClearData() = _ui.update { it.copy(showClearDialog = true) }

    fun dismissClearDialog() = _ui.update { it.copy(showClearDialog = false) }

    fun clearAllData() {
        viewModelScope.launch {
            container.repository.save(AppData())
            _ui.update {
                it.copy(
                    data = AppData(),
                    lastScan = null,
                    lastGrade = null,
                    lastPaper = null,
                    lastPaperJson = null,
                    showClearDialog = false,
                    message = UiMessage("本地数据已清空", false),
                )
            }
        }
    }

    private companion object {
        const val MIN_QUESTION_LENGTH = 4
        const val SEARCH_RESULT_COUNT = 5

        const val DEMO_QUESTION =
            "已知函数 f(x)=x²-4x+3，求 f(x) 在闭区间 [0,3] 上的最大值与最小值。"

        const val DEMO_REFERENCE =
            "f(x)=(x-2)²-1，对称轴 x=2∈[0,3]。最小值 f(2)=-1；最大值在端点取得，" +
                "f(0)=3，f(3)=0，故最大值为 3。"

        const val DEMO_STUDENT =
            "配方得 f(x)=(x-2)²-1，所以最小值是 -1，最大值是 f(3)=0。"
    }
}

/** 最多保留多少批多页扫描记录。 */
private const val MAX_STORED_SCAN_PAPERS = 10
