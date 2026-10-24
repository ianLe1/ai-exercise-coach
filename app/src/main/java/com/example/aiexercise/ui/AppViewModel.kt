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
import com.example.aiexercise.domain.ExerciseToolkit
import com.example.aiexercise.domain.ResearchAssistant
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
enum class AppScreen { Scan, Result, Segments, Knowledge, Paper, Settings }

data class UiMessage(val text: String, val isError: Boolean = false, val id: Long = System.nanoTime())

data class UiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val ocrBusy: Boolean = false,
    val screen: AppScreen = AppScreen.Scan,
    val data: AppData = AppData(),
    val message: UiMessage? = null,

    // 扫描表单
    val scanText: String = "",
    val referenceAnswer: String = "",
    val studentAnswer: String = "",
    val imagePath: String? = null,
    val imageSource: ExerciseSource? = null,

    // 多页扫描（矫正增强 → 分割题目 → 每题独立成习题）
    val scanPages: List<ScanPage> = emptyList(),
    val scanPaperId: String = "",
    val segments: List<ScanResult> = emptyList(),
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
    val settingsModlensUrl: String = "",
    val settingsVisionFallback: Boolean = true,

    // 联网搜索设置与结果
    val searchMode: SearchMode = SearchMode.OFF,
    val settingsBingEndpoint: String = "",
    val settingsBingKey: String = "",
    val searchBusy: Boolean = false,
    val searchQuery: String = "",
    val searchHits: List<SearchHit> = emptyList(),

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
            settingsModlensUrl = container.visionSettings.modlensUrl,
            settingsVisionFallback = container.visionSettings.fallbackToMlKit,
            searchMode = container.searchSettings.mode,
            settingsBingEndpoint = container.searchSettings.endpoint,
            settingsBingKey = container.searchSettings.apiKey,
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
                screen = AppScreen.Scan,
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
                    is Outcome.Success -> pageTexts += recognized.value
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

            val toolkit = newToolkit()
            val paperId = current.scanPaperId.ifBlank { UUID.randomUUID().toString() }
            var data = current.data
            val results = mutableListOf<ScanResult>()
            for ((index, draft) in drafts.withIndex()) {
                _ui.update { it.copy(segmentStage = "正在分析第 ${index + 1}/${drafts.size} 题的知识点…") }
                val pageFrom = draft.pageFrom.coerceIn(1, pages.size)
                val pageTo = draft.pageTo.coerceIn(pageFrom, pages.size)
                val input = ScanInput(
                    rawText = draft.questionText,
                    referenceAnswer = draft.referenceAnswer,
                    studentAnswer = draft.studentAnswer,
                    source = ExerciseSource.OCR_GALLERY,
                    imagePath = pages[pageFrom - 1].imagePath,
                    subjectHint = request.subjectHint,
                    sectionTitle = draft.sectionTitle,
                    pageIndexes = (pageFrom..pageTo).toList(),
                    crossPage = pageTo > pageFrom,
                    scanPaperId = paperId,
                )
                when (val scanned = toolkit.scan(data, input)) {
                    is Outcome.Success -> {
                        results += scanned.value
                        data = scanned.value.data
                        scanned.value.warning?.let { warnings += "第 ${index + 1} 题：${it.userMessage}" }
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
                return@launch
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

    /** 从结果页返回：多页分割流程回到题目列表，単题流程回扫描页。 */
    fun backFromResult() = _ui.update {
        if (it.segments.isNotEmpty()) it.copy(screen = AppScreen.Segments) else it.copy(screen = AppScreen.Scan)
    }

    private fun newToolkit(): ExerciseToolkit = ExerciseToolkit(
        ai = container.aiService,
        research = ResearchAssistant(
            search = container.searchService,
            knowledgeBase = container.knowledgeBase,
            ai = container.aiService,
        ),
    )

    private suspend fun recognizeText(file: File): Outcome<String> {
        val uri = Uri.fromFile(file)
        val primary = container.visionEngine.recognize(uri)
        if (primary is Outcome.Failure &&
            container.visionSettings.mode == VisionMode.MODLENS &&
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
        var result: Outcome<String> = primary
        var fallbackNote: String? = null
        // modlens 不可用时自动回退离线 ML Kit（用户可在设置里关闭该行为）
        if (primary is Outcome.Failure &&
            container.visionSettings.mode == VisionMode.MODLENS &&
            container.visionSettings.fallbackToMlKit
        ) {
            val fallback = container.mlKitVisionEngine.recognize(uri)
            if (fallback is Outcome.Success) {
                result = fallback
                fallbackNote = "modlens 不可用，已自动回退 ML Kit（${primary.error.userMessage}）"
            }
        }
        val finalResult = result
        when (finalResult) {
            is Outcome.Success -> _ui.update {
                it.copy(
                    ocrBusy = false,
                    imagePath = file.absolutePath,
                    imageSource = source,
                    scanText = finalResult.value,
                    message = UiMessage(
                        fallbackNote ?: "已用 ${container.visionEngine.displayName} 识别 ${finalResult.value.length} 个字符，" +
                            "请核对/补全参考答案后分析",
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
        performScan(
            ScanInput(
                rawText = text,
                referenceAnswer = current.referenceAnswer,
                studentAnswer = current.studentAnswer,
                source = current.imageSource ?: ExerciseSource.MANUAL,
                imagePath = current.imagePath,
            ),
        )
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

    fun saveVisionSettings(mode: VisionMode, modlensUrl: String, fallbackToMlKit: Boolean) {
        val settings = VisionSettings(
            mode = mode,
            modlensUrl = modlensUrl.trim().ifBlank { VisionSettings.DEFAULT_MODLENS_URL },
            fallbackToMlKit = fallbackToMlKit,
        )
        val engine = container.saveVision(settings)
        _ui.update {
            it.copy(
                visionMode = settings.mode,
                visionLabel = engine.displayName,
                settingsModlensUrl = settings.modlensUrl,
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
