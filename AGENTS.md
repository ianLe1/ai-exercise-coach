# AGENTS.md — 本仓库维护手册

> 给未来的 AI 代理 / 协作者：改动本仓库前先读这里，改完请推送并保持 `README.md` 与截图同步。

## 这是什么

**AI 习题教练**：一个 Android 应用（Kotlin + Jetpack Compose），主线是**维护「不会的知识点列表」及其遗忘曲线计时器**，并随时列出某道习题内的不会知识点。完整链路：

```
扫描习题（拍照/相册/手动 + 视觉引擎）
  → 自动资料检索（必应搜索 → AI 过滤出本题解析 → 教材知识库）
  → AI 生成/更新「不会的知识点」+ 遗忘曲线计时器
  → 批改
  → 本题 / 全量不会知识点
  → 按遗忘曲线生成结构化练习卷（JSON + Markdown 题面）
```

远程仓库：<https://github.com/ianLe1/ai-exercise-coach>（公开）

## 目录速览

```
app/src/main/java/com/example/aiexercise/
├── data/       # model（全部 @Serializable）、store（单文件 JSON 原子写）、repository
├── domain/     # 纯 Kotlin 核心：遗忘曲线、同一习题判定、知识点规则、工作流、检索流水线、知识库接口、题目分割、答题卡 JSON、练习批改（PracticeCoach）、守卫（ReviewGuards）
├── ai/         # AiService 抽象（离线规则 LocalAiService / OpenAI 兼容）、JSON 容错、必应、modlens 协议、教材知识库客户端
├── platform/   # Android 实现：视觉引擎、图像存储、SharedPreferences、依赖容器
└── ui/         # ViewModel + Compose 界面（扫描/结果/知识点/练习卷/设置）
tools/modlens-bridge/   # 把本机 modlens CLI 包成 HTTP /analyze 的 Node 桥
screenshots/            # 模拟器端到端验收截图（01–55）
```

## 构建与测试

```bash
# 单元测试（109 个用例）
./gradlew :app:testDebugUnitTest

# 调试包
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 直接 `./gradlew` 即可（Gradle/Android 缓存使用默认的 `~/.gradle`、`~/.android`）。早期沙箱用的项目内 `.gradle-home/`、`.android-home/` 已移到 `~/.cache/ai-exercise-build/`，避免 Android Studio 索引 1.8G 缓存导致卡顿。模拟器访问宿主机建议 `adb reverse tcp:8765 tcp:8765` + `http://127.0.0.1:8765`（部分镜像上 `10.0.2.2` 会卡在 SYN_SENT）。

## 改代码时的硬性约定

1. **业务参数集中**：阈值/题量/曲线参数放 `domain/ToolConfig.kt` 与 `domain/ForgettingCurve.kt`，不要在业务代码里散落魔法数字。
2. **核心逻辑必须纯 Kotlin**：遗忘曲线、判重、知识点规则、检索流水线放 `domain/`，不碰 Android API，保证可 JVM 单测；Android 相关放 `platform/` / `ui/`。
3. **AI 调用走 `AiService`**：新增能力先加接口 + DTO + 校验器，再分别实现离线（`LocalAiService`）和远程（`OpenAiCompatibleAiService`）。AI 返回一律容错解析（去 ```json 围栏、截取花括号），失败给 `AppError.AiFormatError` 可读提示。
4. **检索只作证据**：`ResearchAssistant` 流水线必须 `runCatching` 降级，任何一步失败都不能阻断知识点维护；知识库当前是 `EmptyKnowledgeBase`，接数据时实现 `domain/KnowledgeBase.kt` 的 `search()` 并替换 `platform/AppContainer.kt:83` 一行。
5. **密钥不进仓库**：必应 Key / OpenAI Key 只在 App 设置页由用户填写（SharedPreferences）；仓库内只允许占位与 stub。
6. **改 UI 要同步截图与 README**：更新 `screenshots/` 与 README 的「运行截图」清单。

7. **视觉结果要带排版**：`VisionEngine.recognize` 返回 `VisionDocument(plainText, markdown)`；纯文本用于 `QuestionSegmenter` 切题，Markdown 存进 `Exercise.questionMarkdown` 并随 `AnalyzeRequest.questionMarkdown` 交给 AI。新增视觉实现必须同时给出两者，排版整理统一走纯 Kotlin 的 `domain/LayoutFormatter.kt`（有单测）。

8. **扫描即批改（扫描件上框题，作业帮式）**：多页扫描分割出题目后，`SegmentsScreen` 顶部直接把扫描件原图铺出来，用 `VisionEngine` 采集的逐行归一化坐标（`ScanPage.lines` + `domain/QuestionBoxMapper.kt`）把每题映射成一条「横条框」；点框切换选中题、只批改这一题（`gradeScannedSegments(onlyExerciseId)`），已批改按对错变绿/黄。相邻框空隙在 mapper 里平分，横向统一 0.02–0.98，保证点击不落空。注意 `processDrafts` 必须用 `_ui.value.scanPages`（识别阶段已回写 lines）而不是入参副本，否则框位全空；选中态不能 `remember(segments)`，否则批改后跳回第 1 题。作答来自 AI 分割时抽取的 `studentAnswer`；扫描页不再有题目文本编辑框，单页扫描入口已全部删除。
9. **视觉默认离线 + 证据缓存（modlens 式）**：`VisionSettings.mode` 默认 `MLKIT`（快、离线、无需 Key）；云端多模态只作为可选增强（限流时单页可达 8–9 分钟）。视觉结果（plainText + markdown）写回 `ScanPage.ocrText/markdown`，`ocrAndSegment()` 遇到已有 `ocrText` 的页直接复用，绝不重复调用视觉模型；版面整理统一走 `domain/LayoutFormatter.kt`。
10. **整卷生成 + 结构化强规范 + 批改统一**：首页「生成一整张试卷」走 `AppViewModel.generatePaperFull()`（默认 20 题、`dueOnly=false`）；`domain/PaperStructure.kt` 强制三大题归类、每题分值>0、全卷合计 100、选择题≥2 选项，不合规会被拒绝（远程失败用离线模板兜底）；练习卷页不再有单独的答题卡批改入口，批改一律走「扫描习题 → 分割 → 批改这次扫描」。题面用 `ui/components/MarkdownText.kt` 渲染（轻量 Markdown，不引第三方）。整卷题池不只用错题：`ExerciseToolkit.generatePaper` 取 3 个紧迫知识点为主、2 个掌握度最高的为穿插、其余约 20% 由 AI 补新知识点（`PaperRequest.reviewPoints/newPointCount`）；大模型预设见 `ai/LlmPresets.kt`（当前只保留 Intern AI：`https://discovery-api.intern-ai.org.cn/v1` / `Sage-A1.5`（免费多模态 512K；备选 Agents-A1 / intern-s2）；只填 Base URL/模型名，Key 由用户申请，仓库绝不写 Key）。`OpenAiCompatibleAiService` 对 intern-ai 端点使用 `InternChatRequestBody`（`chat_template_kwargs.enable_thinking=false` + `max_tokens=4096`），并兼容只在 `reasoning_content` 返回正文的模型。视觉模式为 CLOUD 但没有 Key 时，`VisionEngineFactory` 直接返回 ML Kit，避免扫描时反复报「没有 API Key」；`testAi()` 在 REMOTE+空 Key 时给明确提示。本地注入：`app/build.gradle.kts` 从 `local.properties` 的 `llm.baseUrl/llm.model/llm.apiKey` 读入 `BuildConfig.DEFAULT_LLM_*`（该文件已 gitignore，仓库绝不含 Key），`AiSettingsStore.load()` 在用户从未保存设置时用它作为默认（有预置 Key 则默认 REMOTE）。扫描批改前若题目作答为空，`gradeScannedSegments()` 会先用 `readAnswers` 从 `scanPageTexts` 再抽一次。
11. **界面极简**：首页（`ui/screens/HomeScreen.kt`）只允许「按复习时间排序的知识点列表 + 扫描按钮」；练习卷/批改入口在扫描页底部；设置页只保留「大模型 + 用户信息」两块。参考答案不由用户填写，缺省时走 `AiService.solve` 自动生成。
   扫描器必须在无 Google 服务（华为）设备上可用：`DocumentScanEngine.isAvailable` 为 false 时按钮进入 `AppScreen.InAppScan`（`ui/screens/InAppScanScreen.kt`：CameraX 取景 → 连拍 → 拖四角透视矫正 → 增强 → `onInAppScanCompleted` 追加 `scanPages`），这是「模仿系统扫描界面」而非拍一张就 OCR；答题卡批改只保留多页入口（相册多选 / 相机逐页）。

12. **练习批改闭环**：`domain/PracticeCoach.kt` 负责「扫描答题卡 → 逐题批改 → 仍不会知识点 → 订正轮次」，`domain/ReviewGuards.kt` 负责订正质量与作业一致性守卫（首次必问）。改批改/提问逻辑时保持 `PracticeReview.round` 递增与 `guidedSessions` 的 resolved 并入知识点行为。

13. **教材知识库**：`ai/KnowledgeBaseClient.kt` 对接 `ChinaTextbook-KB` 的 `GET /api/search`（默认 `http://127.0.0.1:8737`，模拟器 `adb reverse tcp:8737 tcp:8737`）。检索失败必须降级（`kbState=failed` + 可读提示），不得阻断知识点分类；服务端数据/索引不入库。
14. **答题卡 JSON 规范不可破坏**：对外输出的练习卷 JSON 是「答题卡结构」（大题 → 题 → 可选小题；数字=答题行数、数组=选择题选项、字符串含 `___`=填空题）。编解码/校验集中在 `domain/AnswerSheet.kt`，题面与答案 Markdown 放在完整练习卷 JSON 里，两者都要保留。
15. **多页扫描**：文档扫描/增强在 `platform/DocumentScanEngine.kt`、`platform/ImageEnhancer.kt`；题目分割在 `domain/QuestionSegmenter.kt`（跨页合并、页眉过滤）。分割出的每道题必须独立成 `Exercise` 并带上 `sectionTitle/pageIndexes/crossPage/scanPaperId`。

## 构建性能（本机）

- `gradle.properties` 已调优（6G 堆 / parallel / build cache / configuration cache / Kotlin daemon 3G）；改构建参数时先量一下 `:app:clean :app:assembleDebug`（本机 ≈1m42s）与增量构建（≈5s）。
- **不要把 Gradle/Android 缓存目录放进项目**（早期 `.gradle-home/` 已移到 `~/.cache/ai-exercise-build/`，约 1.8G）；项目内大目录会让 Android Studio 索引与 AI 索引非常慢。IDE 侧已把堆调到 4G（`~/.config/Google/AndroidStudio2026.2.1/studio64.vmoptions`）。
- 首次 Gradle Sync 要下载 AGP/Compose/ML Kit 依赖（一次性，本机实测 27 分钟），之后为秒级。

## 提交与发布

```bash
git add -A && git commit -m "feat|fix|docs: 简述"   # 中文说明可
git push origin main
```

- 默认分支 `main`，提交信息建议 `feat: / fix: / docs: / refactor: / test:` 前缀。
- 发版：`git tag vX.Y.Z && git push origin vX.Y.Z`，然后
  `gh release create vX.Y.Z --title "..." app/build/outputs/apk/debug/app-debug.apk#app-debug.apk`。
- 改动前先跑一遍 `:app:testDebugUnitTest`，绿色再提交。

## 已知待办

- 知识库增强：向量检索需要 HuggingFace 嵌入模型（本机离线时走纯 BM25）；可补 PDF 原页图查看、OCR 更多扫描页。
- 可选：GitHub Actions 跑单测 + 构建（目前本地验证为主）。
