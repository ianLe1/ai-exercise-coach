# AI 习题教练（AI Exercise Coach）

[![Release](https://img.shields.io/github/v/release/ianLe1/ai-exercise-coach)](https://github.com/ianLe1/ai-exercise-coach/releases/latest)
[![Stars](https://img.shields.io/github/stars/ianLe1/ai-exercise-coach)](https://github.com/ianLe1/ai-exercise-coach)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Compose-Material%203-4285F4)](https://developer.android.com/jetpack/compose)

一个 Android 端「AI 习题工具」：**扫描习题 → 生成/维护“不会的知识点”与遗忘曲线计时器 → 批改 → 汇总相关知识点 → 按遗忘曲线生成结构化练习卷（JSON + Markdown 题面）**。

- 技术栈：Kotlin + Jetpack Compose（Material 3）+ kotlinx.serialization + Coroutines；AI 默认离线规则引擎，可一键切换 OpenAI 兼容接口。
- 视觉扫描：默认 Google ML Kit 中文文字识别（端侧离线）；可切换 **modlens 视觉桥**（多模态模型读题，经 `tools/modlens-bridge` 本机 HTTP 桥接入，失败自动回退 ML Kit）。
- 联网搜索：识别结果页可用 **必应 Web Search API v7** 搜索本题讲解 / 同类题（Azure Key 配置后启用，不配置则该入口隐藏）。
- **AI 分类前的自动资料检索（固定流水线）**：每次 AI 生成/更新「不会的知识点」之前，先用题目正文联网搜索本题解析 → 交给 AI 过滤出真正匹配的解析 →（可选）检索教材/教师用书知识库 → 一起作为分类上下文；任何一步失败都降级为可读提示，不阻断知识点维护。知识库当前保留接口与空实现（教材数据待接入）。
- 数据：单文件 JSON（应用私有目录 `files/data/app_data.json`），原子写；API Key 只存 SharedPreferences。
- 验收：61 个 JVM 单元测试全部通过；`assembleDebug` 产出 APK；模拟器上完整跑通一遍流程（截图见 `screenshots/`）。

## 快速开始

```bash
# 构建调试包（本仓库自带可写 Gradle home 方案，见文末“环境说明”）
GRADLE_USER_HOME=$PWD/.gradle-home ANDROID_USER_HOME=$PWD/.android-home \
  /home/lee/.gradle/wrapper/dists/gradle-9.6.0-bin/42k10rwplmzkhuboz9kdazi7s/gradle-9.6.0/bin/gradle \
  :app:assembleDebug

# 单元测试
GRADLE_USER_HOME=$PWD/.gradle-home ANDROID_USER_HOME=$PWD/.android-home <gradle-bin> :app:testDebugUnitTest
```

APK：`app/build/outputs/apk/debug/app-debug.apk`，安装后打开即为「扫描」页；点 **填入示例习题** 可一键跑通完整流程。

## 目录结构

```
app/src/main/java/com/example/aiexercise/
├── data/
│   ├── model/Models.kt          # 全部可序列化数据模型（AppData/Exercise/KnowledgePoint/ReviewTimer/...）
│   ├── model/ResearchModels.kt  # 自动资料检索模型（WebHit/SolutionDigest/KbCitation/ResearchBundle）
│   ├── store/AppDataStore.kt    # AppJson + JsonFileStore（原子写）+ InMemoryStore
│   └── AppRepository.kt         # 唯一读写磁盘的仓库层，返回 Outcome
├── domain/                      # 纯 Kotlin，无 Android 依赖，可 JVM 单测
│   ├── Errors.kt                # AppError sealed class + Outcome
│   ├── ToolConfig.kt            # 业务参数（阈值、题量上限等，可替换）
│   ├── ForgettingCurve.kt       # 简化 SM-2 + 指数遗忘模型（可替换）
│   ├── ExerciseIdentity.kt      # 同一习题判定：规范化 + SHA-256 + bigram Jaccard
│   ├── KnowledgePointRules.kt   # 去重键、相似兜底、计时器刷新、方面匹配、合并
│   ├── ExerciseWorkflow.kt      # ExerciseToolkit：scan / grade / recordReview / generatePaper
│   ├── ResearchAssistant.kt     # 固定流水线：网络搜索 → AI 过滤本题解析 → 知识库检索
│   ├── KnowledgeBase.kt         # 教材/教师用书知识库接口 + EmptyKnowledgeBase（待接入）
│   └── TimeFormat.kt            # ISO 时间格式
├── ai/
│   ├── AiService.kt             # AI 抽象 + DTO + 输出校验器（可替换）
│   ├── AiJson.kt                # JSON 容错解析（去围栏、截取花括号）
│   ├── LocalAiService.kt        # 默认离线规则引擎（无 Key 可跑通验收）
│   ├── OpenAiCompatibleAiService.kt # OpenAI 兼容 /chat/completions（可换任何厂商）
│   ├── BingSearchService.kt     # 必应 Web Search（SearchService 抽象 + 工厂）
│   ├── ModlensPayload.kt        # modlens 桥协议编解码（纯 Kotlin，可单测）
│   ├── HttpTransport.kt         # HTTP 抽象（postJson/getJson），默认 HttpURLConnection
│   └── IntegrationSettings.kt   # VisionMode/VisionSettings/SearchMode/SearchSettings
├── platform/                    # Android 相关实现
│   ├── VisionEngine.kt          # 视觉抽象 + ML Kit 实现 + 工厂（可替换点）
│   ├── ModlensVisionEngine.kt   # modlens 视觉桥（HTTP 调本机 modlens CLI 包装）
│   ├── ImageStore.kt            # 拍照文件 / 相册复制 / FileProvider
│   ├── AiSettingsStore.kt       # SharedPreferences
│   └── AppContainer.kt          # 手写依赖注入容器
├── ui/
│   ├── AppViewModel.kt          # 状态 + 全部用户动作
│   ├── AppRoot.kt               # Scaffold + 4 个底部 tab
│   ├── components/Common.kt     # 卡片、掌握度条、知识点卡等
│   └── screens/                 # Scan / Result / Knowledge / Paper / Settings
├── AiExerciseApp.kt             # Application，持有 AppContainer
└── MainActivity.kt

tools/modlens-bridge/server.mjs   # Node 桥：把 modlens CLI 包装成 HTTP /analyze 服务
```

## 六个模块的实现方式

### 1. 习题扫描

- **输入形式**：拍照（`ActivityResultContracts.TakePicture` + FileProvider 授权）、相册选图（`PickVisualMedia`）、手动输入文本三种；扫描页可随时切换。
- **视觉引擎抽象**：`platform/VisionEngine.kt` 定义 `suspend fun recognize(uri): Outcome<String>`，两个实现：
  - `MlKitVisionEngine`（默认）：Google ML Kit 中文文字识别（`com.google.mlkit:text-recognition-chinese`，模型随 APK 打包，**离线**，不上传图片）；
  - `ModlensVisionEngine`：把图片 base64 后 POST 给本机运行的 `tools/modlens-bridge`，由 modlens（多模态模型）读题并返回文字（设置页可切换，失败自动回退 ML Kit）。
- **流程**：图片 → 视觉引擎 → 文本回填可编辑输入框 → 用户补齐“参考答案/我的作答” → 点「AI 分析知识点」。识别结果过短（<4 字）视为失败，提示“可能是图片模糊或没有文字”，并保留手动输入通道。
- **可替换点**：换云端 OCR / 其他多模态服务，只需实现 `VisionEngine` 接口；UI 与业务层不用改。

### 2. 知识点生成与维护

- 扫描（首次分析）时先跑「网络搜索 → AI 过滤本题解析 → 知识库」流水线（见下一节），再把「题目 + 参考答案 + 我的作答 + 提问者画像（姓名/年级/目标）+ 检索证据」交给 `AiService.analyze`，返回 `KpDraft` 列表，经 `AiPayloadValidator` 过滤空标题、去重、补默认方面。
- **数据结构**：`KnowledgePoint(id, title, aspect, subject, detail, evidence, sourceExerciseIds, mastery, timer, createdAt, updatedAt, refreshCount, archived)`；计时器 `ReviewTimer(nextReviewAt, intervalDays, ease, reviewCount, lastReviewedAt, lastQuality, lapseCount)`。
- **新知识点**：`mastery = 0.2`，`timer = ForgettingCurve.initialTimer(now)`（1 天后到期）。
- **去重与更新规则**（`KnowledgePointRules`）：
  - 去重键 = `normalize(title) + "|" + normalize(aspect)`；`normalize` 做全角→半角、去题号、去标点空白、小写。
  - 键不同但 **方面相同且标题高度相似** 也视为同一条：长标题 Jaccard ≥ 0.9；短标题（≤12 字）放宽到 ≥ 0.6 或标题包含（中文标题常因 AI 措辞多/少一个“的”）。命中则合并 `detail`/`evidence`（去重拼接 `；`）、把 `sourceExerciseIds` 并入并刷新计时器。
- **遗忘曲线**（`ForgettingCurve`，简化 SM-2 + 指数遗忘）：初始间隔 1 天；第 2 次通过 3 天；之后 `interval * ease`，上限 180 天；ease 默认 2.5（范围 1.3–2.8）；quality<3 视为遗忘，间隔重置 1 天并 `lapseCount+1`；保持率 `R(t)=e^(-t/S)`，`S=intervalDays*1.5`；到期判定 `now ≥ nextReviewAt` 或 `R < 0.75`；紧迫度 = 保持率缺口 0.35 + 逾期 0.25 + 掌握度缺口 0.20 + 到期加成 0.20。
- **可替换点**：参数集中在 `ForgettingCurveConfig` / `ToolConfig`；算法整体替换只需重写 `ForgettingCurve` 同名函数。

### 3. 批改

- 结果页输入/修改「我的作答」，点「开始批改」→ `AiService.grade` 返回 `GradePayload(score, correct, correctAnswer, analysis, errorSteps, relatedKnowledgePoints)`。
- 默认离线引擎：先做数字比对（提取数字、容差比较），无数字则用“文本相似度 0.6 + 要点覆盖率 0.4”打分；通过线 60 分（`ToolConfig.passScore`）。远程模式由大模型直接给分与解析。
- 产出 `GradeResult` 持久化；失败批改会**加速复习**：把该题相关知识点的下次复习时间拉回 1 天内、掌握度下降（`failedGradeAccelerateMs` 可配置）。
- AI 输出不合法：远程服务先重试 1 次，仍失败返回 `AppError.AiFormatError`，UI 提示“AI 返回格式不合法，请重试或检查模型”；分数会被 clamp 到 0–100。

### 4. 知识点汇总

- 批改完成后结果页立即列出「本题相关的不会知识点」：`grade()` 返回 `relatedKnowledgePoints`（按题目 + 参考答案匹配方面，若无匹配则回退为该题来源知识点），每张卡片显示方面、掌握度条、遗忘曲线状态（“已逾期 2 天 / 3 天后复习”）、证据，并可直接自评复习（忘了/勉强/熟练）刷新计时器。
- 「知识点」tab 展示全部知识点：平均掌握度、累计复习、逾期数、关键词过滤、只看已到期，按紧迫度降序。
- **按习题查看**：知识点页顶部有「全部 / 某道习题」筛选 chip（`UiState.filterExerciseId`），点一道题即可只列“这道习题内的不会知识点”（随时可回答“这道题我有哪些不会的”）。

### 5. 连续扫描同一习题的特判

- **判定**（`ExerciseIdentity.isSameExercise`，三级从严到宽）：
  1. 规范化文本的 **SHA-256 指纹相同** → 同一题（防 OCR 全角/半角、标点、题号差异）；
  2. 长度比 ≥ 0.6 且 **bigram Jaccard 相似度 ≥ 0.85** → 同一题（容忍 OCR 错别字/漏字）；
  3. 规范化长度 < 6 的短文本只认同指纹，避免“1. 求值”“2. 求值”误判。
- **特判行为**（`ScanResult.isDuplicate = true`）：
  - **不新建习题、不新建知识点**（知识点 id 集合不变）；
  - 习题 `scanCount += 1`、`lastSeenAt` 刷新；
  - 命中的知识点 `refreshCount += 1`，计时器刷新为“再次遇到 → 1 天后复习”，掌握度压到 `duplicateMasteryCap = 0.35` 以下；
  - 结果页顶部显示特判卡片：“检测到同一习题：复用 N 个不会知识点，刷新 M 个遗忘曲线计时器”；并附“重新扫描此题（验证特判）”按钮便于演示。
  - 返回 `warning`（可空）用于提示“本次识别的文本与上次高度相似但不是同一题”等边界情况。

### 6. 练习卷生成

- 入口：结果页「按遗忘曲线为最紧迫的知识点出练习卷」或「练习卷」tab 输入/点选方面（aspect）与题量。
- 选题：`ExerciseToolkit.generatePaper` 先用 `KnowledgePointRules.matchesAspect` 过滤方面（空方面 = 全部），按 `ForgettingCurve.urgency` 降序取前 `maxKnowledgePointsPerPaper` 个；可选“只从已到期待复习知识点选题”；没有对应知识点时返回 `AppError.NoKnowledgePointForAspect(方面, 可用方面列表)`，UI 给出可读提示并展示可选方面。
- 生成：把选中的知识点 + 提问者画像交给 `AiService.generatePaper`，返回 `PaperPayload`（标题/方面/摘要/题目）。
- **输出**：`PracticePaper` 序列化为 JSON（`Paper.schema = "ai-exercise-paper/v1"`，含 `knowledgePoints`、`questions`，每题 `questionMarkdown/answerMarkdown/explanationMarkdown` 为 Markdown），练习卷页可直接「查看 JSON / 复制 JSON」。落盘保留最近 20 份。
- 生成结果为空/题面缺失会被 `AiPayloadValidator` 过滤；空卷返回 `AppError.GenerationInvalid`，不会生成非法 JSON。

## AI 分类前的自动资料检索（固定流水线）

目标没有变：**维护「不会的知识点列表」，并随时列出某道习题内的不会知识点**；检索只是 AI 分类前的证据，不单独开一条流程。

- **触发点**：`ExerciseToolkit.scan` 在调用 `ai.analyze` 之前执行（新题必跑；同一习题只在“参考答案变了或此前没有关联知识点、需要重新分析”时跑，避免重复外网请求与 token 消耗）。
- **三步流水线**（`domain/ResearchAssistant.kt`，`ResearchSource` 接口）：
  1. **网络搜索**：查询词 = 题目正文（压缩空白、截断 80 字）+ “解析 答案”，调 `SearchService`（必应；未配置则跳过并提示），取前 5 条命中。
  2. **AI 过滤**：命中交给 `AiService.distillSolution` 只保留真正讲解本题的内容 —— 远程模式走专门 prompt，要求“没有匹配就 relevant=false，不得编造”；离线引擎按「参考答案数值命中 0.45 + 题干关键词覆盖 0.35 + 答案覆盖 0.20 − 噪声词惩罚」打分，低于阈值判为不匹配。产出 `SolutionDigest(relevant, solutionMarkdown, confidence, matchedTitles)`。
  3. **知识库**：`KnowledgeBase.search(KnowledgeQuery)` 检索教材/教师用书。当前注入 `EmptyKnowledgeBase`（文档数 0 → `kbState=skipped`，提示“教材知识库暂未接入（等待教材/教师用书数据）”）；接口与 `KbCitation` 结构已就绪，接入数据只需替换容器里的一行实现。
- **喂给分类的上下文**：`AnalyzeRequest.research` 带上 `ResearchBundle`；离线引擎把“网络解析已筛选纳入参考”“教材依据：《书名》页码”写进知识点 `reason/detail`，远程 prompt 附带【自动检索到的本题解析】【教材/教师用书依据】两段，并明确“检索只作证据、不得照抄成标题”。
- **持久化与展示**：`ResearchBundle` 随习题保存（`Exercise.lastResearch`）；结果页新增「① 自动资料检索（AI 分类前）」折叠卡（状态一行 + AI 筛出的解析 + 采纳来源 + 可展开的网络命中/教材依据），知识点列表紧跟其后作为「② 本题的不会知识点」。
- **降级语义**：状态机 `webState ∈ ok / skipped / failed / no_match`，`kbState ∈ ok / empty / skipped / failed`；warning 用中文“；”拼接；`ResearchAssistant` 内部 `runCatching`，任何异常都不外抛、不影响分类。扫描按钮下方实时显示阶段（“联网搜索本题解析…”→“AI 正在过滤搜索结果…”→“AI 正在生成不会的知识点…”）。

## modlens 视觉桥与必应搜索接入

### A. 视觉：ML Kit（默认）或 modlens 视觉桥

- 设置页「视觉识别（扫描）」可选：`ML Kit 中文 OCR（离线）` / `modlens 视觉桥`；桥地址默认 `http://10.0.2.2:8765`，超时 20s，可勾选「modlens 失败时自动回退 ML Kit」。
- 桥协议（`ai/ModlensPayload.kt` 与 `tools/modlens-bridge/server.mjs` 一一对应）：
  - `POST /analyze` `{"image_base64":"...","mime":"image/jpeg","prompt":"..."}` → `{"text":"识别文字","provider":"...","raw":{...}}`；失败 → `{"error":"可读信息"}`；
  - `GET /health` → `{"ok":true}`。
- 启动桥（Node ≥ 18，零第三方依赖）：

  ```bash
  # 1) 先给 modlens 配置一个视觉 provider（任选其一）
  npx @liustack/modlens config set gemini-api.apiKey <key>
  npx @liustack/modlens doctor            # 确认 provider 已就绪
  # 2) 启动桥（默认 127.0.0.1:8765）
  node tools/modlens-bridge/server.mjs
  # 3) 模拟器推荐建立反向端口映射，桥地址填 http://127.0.0.1:8765
  adb reverse tcp:8765 tcp:8765
  ```
  - 环境变量：`PORT`、`HOST`（真机用 `0.0.0.0` + 局域网 IP）、`MODLENS_BIN`、`MODLENS_PROVIDER`、`MODLENS_TIMEOUT`。
  - 若本机没有配置 modlens provider，`/analyze` 会返回可读错误，App 自动回退 ML Kit 并在提示中说明原因。
- 已实测回退链路：停掉桥后选图，`ModlensVisionEngine` 记录 `EOFException`（桥不可达）→ 自动改用 ML Kit → OCR 文字回填（见 `screenshots/15_vision_fallback.png`）。

### B. 搜索：必应 Web Search

- 设置页「联网搜索（必应）」：开关 + Endpoint（默认 `https://api.bing.microsoft.com/v7.0/search`）+ `Ocp-Apim-Subscription-Key`（只存本机 SharedPreferences）。
- 识别结果页出现「用必应搜索相关资料 / 同类题」：关键词 = 题目正文 + 关联知识点 + “解题思路”，`count=5&mkt=zh-CN&responseFilter=Webpages`；结果按标题/摘要/URL 展示，点击用系统浏览器打开。
- 失败可读提示：未配置 Key / 401/403 → “必应搜索鉴权失败，请检查 Key”；网络错误 → `AiNetworkError`；搜索开关关闭时该入口整体隐藏。
- 可替换：`ai/BingSearchService.kt` 的 `SearchService` 接口（可换 Google CSE / 博查 / 自建搜索）。

## 错误处理（可读提示）

| 场景 | 错误类型 | UI 行为 |
| --- | --- | --- |
| 扫描文本过短 / OCR 无结果 | `AppError.UnrecognizedInput` | Snackbar 提示，保留输入框可手动输入 |
| modlens 桥不可用 / 未配置 provider | `AiNetworkError`（含原因） | 提示桥错误；开启回退时自动改用 ML Kit 并说明原因 |
| 必应 Key 未配置或鉴权失败 | `AiNetworkError` | 提示到设置页配置必应 Key |
| 未填参考答案 / 未填作答 | `MissingReferenceAnswer` / `MissingStudentAnswer` | 提示去补齐，不发起 AI 调用 |
| AI 返回非法 JSON（重试 1 次后） | `AiFormatError` | 提示“AI 返回格式不合法，请重试或检查模型配置” |
| 网络失败 / 4xx | `AiNetworkError` | 提示 HTTP 状态与错误信息 |
| 未配置 API Key | `AiNotConfigured` | 提示到设置页配置（本地端点免 Key） |
| 网络搜索失败/未配置 | `ResearchBundle.webState=failed/skipped` + warning | 检索卡显示可读原因，分类继续执行 |
| AI 过滤后无匹配解析 | `webState=no_match` | 提示“网络结果与本题不匹配”，不把噪声喂给分类 |
| 知识库未接入 / 检索失败 | `kbState=skipped/failed` | 提示“教材知识库暂未接入”，分类继续执行 |
| 指定方面没有知识点 | `NoKnowledgePointForAspect` | 提示方面名并列出可用方面 |
| 数据读写失败 | `Persistence` | 提示文件错误，界面数据不丢失 |

所有错误都实现 `userMessage`（中文可读）与 `technical`（调试用）；UI 只展示 `userMessage`。

## 可替换点（代码中均有注释）

1. **视觉识别**：`platform/VisionEngine.kt` 接口（ML Kit / modlens / 云端 OCR 均可替换）；modlens 桥实现见 `platform/ModlensVisionEngine.kt`。
2. **AI 模型与调用方式**：`ai/AiService.kt` 接口 + `AiServiceFactory`；设置页可切换“离线 / OpenAI 兼容”，填 Base URL、模型名、API Key（支持 DeepSeek、Moonshot、Ollama、vLLM 等 `/chat/completions` 兼容端点；`10.0.2.2`、localhost 免 Key）。
3. **遗忘曲线参数**：`domain/ForgettingCurve.kt` 的 `ForgettingCurveConfig`。
4. **数据持久化**：`data/store/AppDataStore.kt` 接口 → 换 Room/SQLite/云同步。
5. **交互界面形态**：Compose 单 Activity 自研导航（4 tab + 结果页），可换 Navigation-Compose / 多页面。
6. **业务参数**：`domain/ToolConfig.kt`（相似度阈值、通过分、重复扫描掌握度上限、题量上限等）。
7. **HTTP 传输**：`ai/HttpTransport.kt`（默认 HttpURLConnection，可换 OkHttp/Ktor）。
8. **联网搜索**：`ai/BingSearchService.kt` 的 `SearchService` 接口（必应 → 其他搜索 API）。
9. **教材/教师用书知识库**：`domain/KnowledgeBase.kt`（当前 `EmptyKnowledgeBase`，接数据时实现 `search()` 并替换 `AppContainer.knowledgeBase`）。
10. **检索流水线编排**：`domain/ResearchAssistant.kt` 的 `ResearchSource` 接口（可换查询构造、多路搜索、重排策略）。

## 测试

`app/src/test/java/com/example/aiexercise/` 下 61 个 JVM 单测：

- `domain/ForgettingCurveTest`：初始间隔、SM-2 增长、遗忘重置、掌握度边界、保持率/逾期/紧迫度。
- `domain/ExerciseIdentityTest`：规范化、指纹稳定性、相似度、同一题判定、短文本保护。
- `domain/KnowledgePointRulesTest`：去重键、相似兜底、计时器刷新、方面匹配、合并。
- `domain/ExerciseToolkitTest`（验收流）：扫描建知识点与计时器 → 重复扫描特判（不重复建点、刷新计时器、scanCount/refreshCount++）→ 批改 → 相关知识点 → 失败批改加速复习 → 生成合法 JSON/Markdown 练习卷 → 无对应方面的可读错误。
- `ai/LocalAiServiceTest`、`ai/OpenAiCompatibleAiServiceTest`（解析、围栏、重试、4xx 不重试、缺 Key）。
- `ai/BingSearchServiceTest`（请求构造/鉴权头、结果解析、401/403、空结果）。
- `ai/ModlensPayloadTest`（请求编码、响应解析、桥错误、无文字）。
- `domain/ResearchAssistantTest`：未配置搜索/知识库时跳过、搜索失败降级、AI 判定不匹配、知识库命中与 `citationLabel`。
- `ai/LocalAiServiceTest`（新增）：网络解析筛选保留匹配命中、剔除广告噪声、全噪声时 `relevant=false`。
- `domain/ExerciseToolkitTest`（新增）：分类前检索结果写入 `Exercise.lastResearch`，同题连续扫描不重复创建知识点且保留检索证据。
- `data/AppDataStoreTest`：JSON 文件往返、缺失文件、仓库层。

## 运行截图（Android 模拟器 / API 37）

- `screenshots/01_scan.png` 扫描页（拍照 / 相册 / 手动输入 / 填入示例）
- `screenshots/02_scan_analyzed.png` 生成知识点 + 遗忘曲线计时器
- `screenshots/03_graded.png` 批改结果（分数 / 通过 / Markdown 解析）
- `screenshots/04_duplicate_scan.png` 连续扫描同一习题 → 特判卡片（已扫描 2 次、复用知识点、刷新计时器）
- `screenshots/05_knowledge.png` 知识点汇总（掌握度 / 紧迫度 / 复习自评）
- `screenshots/06_paper.png` 按方面出卷设置
- `screenshots/07_paper_generated.png` 结构化练习卷（题面 Markdown）
- `screenshots/08_paper_json.png` 练习卷 JSON（合法、含 Markdown 题面）
- `screenshots/09_error_no_aspect.png` 方面没有对应知识点的可读错误提示
- `screenshots/10_settings.png` AI 引擎 / 提问者 / 数据设置
- `screenshots/11_persisted.png` 杀进程重启后知识点与计时器仍然存在
- `screenshots/12_integrations_settings.png` 设置页：modlens 视觉桥 + 必应搜索配置
- `screenshots/13_vision_modlens.png` modlens 视觉桥识别后回填题目（端到端）
- `screenshots/14_bing_search.png` 结果页必应搜索讲解/同类题
- `screenshots/15_vision_fallback.png` modlens 桥不可用时自动回退 ML Kit（离线 OCR 回填）
- `screenshots/16_research_pipeline.png` AI 分类前的自动资料检索（已筛出本题解析 / 知识库未接入 / 命中 3 条）
- `screenshots/17_research_details.png` 展开检索详情：网络命中标题、摘要、URL
- `screenshots/18_knowledge_by_exercise.png` 知识点页「按习题查看」（只看这道题的不会知识点）
- `screenshots/19_duplicate_research.png` 同题连续扫描：特判生效、未重复检索、保留原检索证据

## 环境说明（本机沙箱）

- `~/.gradle` 在本机沙箱内只读，因此用仓库内 `.gradle-home/`（已 `.gitignore`）复用依赖缓存；`ANDROID_USER_HOME` 指向 `.android-home/`。
- 本机 IPv6 TLS 握手失败，`gradle.properties` 已设 `systemProp.java.net.preferIPv4Stack=true`（如网络正常可删除）；App 内 `AiExerciseApp` 也设置了 `java.net.preferIPv4Stack=true`，避免部分模拟器镜像上 IPv4 映射套接字连不通宿主机。
- 本机实测：该模拟器镜像访问 `10.0.2.2` 的 Java 套接字会停在 SYN_SENT（`adb shell nc` 却正常），因此端到端验证使用 `adb reverse tcp:8765 tcp:8765` + `http://127.0.0.1:8765`；真机直接用局域网 IP 即可。
