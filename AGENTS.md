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
├── domain/     # 纯 Kotlin 核心：遗忘曲线、同一习题判定、知识点规则、工作流、检索流水线、知识库接口、题目分割、答题卡 JSON
├── ai/         # AiService 抽象（离线规则 LocalAiService / OpenAI 兼容）、JSON 容错、必应、modlens 协议
├── platform/   # Android 实现：视觉引擎、图像存储、SharedPreferences、依赖容器
└── ui/         # ViewModel + Compose 界面（扫描/结果/知识点/练习卷/设置）
tools/modlens-bridge/   # 把本机 modlens CLI 包成 HTTP /analyze 的 Node 桥
screenshots/            # 模拟器端到端验收截图（01–23）
```

## 构建与测试

```bash
# 单元测试（81 个用例）
./gradlew :app:testDebugUnitTest

# 调试包
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 本机沙箱若 `~/.gradle` 不可写，可把 `GRADLE_USER_HOME` / `ANDROID_USER_HOME` 指到仓库内目录（`.gitignore` 已忽略 `.gradle-home/`、`.android-home/`），用解压好的 gradle 二进制直接跑；模拟器访问宿主机建议 `adb reverse tcp:8765 tcp:8765` + `http://127.0.0.1:8765`（部分镜像上 `10.0.2.2` 会卡在 SYN_SENT）。

## 改代码时的硬性约定

1. **业务参数集中**：阈值/题量/曲线参数放 `domain/ToolConfig.kt` 与 `domain/ForgettingCurve.kt`，不要在业务代码里散落魔法数字。
2. **核心逻辑必须纯 Kotlin**：遗忘曲线、判重、知识点规则、检索流水线放 `domain/`，不碰 Android API，保证可 JVM 单测；Android 相关放 `platform/` / `ui/`。
3. **AI 调用走 `AiService`**：新增能力先加接口 + DTO + 校验器，再分别实现离线（`LocalAiService`）和远程（`OpenAiCompatibleAiService`）。AI 返回一律容错解析（去 ```json 围栏、截取花括号），失败给 `AppError.AiFormatError` 可读提示。
4. **检索只作证据**：`ResearchAssistant` 流水线必须 `runCatching` 降级，任何一步失败都不能阻断知识点维护；知识库当前是 `EmptyKnowledgeBase`，接数据时实现 `domain/KnowledgeBase.kt` 的 `search()` 并替换 `platform/AppContainer.kt:83` 一行。
5. **密钥不进仓库**：必应 Key / OpenAI Key 只在 App 设置页由用户填写（SharedPreferences）；仓库内只允许占位与 stub。
6. **改 UI 要同步截图与 README**：更新 `screenshots/` 与 README 的「运行截图」清单。
7. **答题卡 JSON 规范不可破坏**：对外输出的练习卷 JSON 是「答题卡结构」（大题 → 题 → 可选小题；数字=答题行数、数组=选择题选项、字符串含 `___`=填空题）。编解码/校验集中在 `domain/AnswerSheet.kt`，题面与答案 Markdown 放在完整练习卷 JSON 里，两者都要保留。
8. **多页扫描**：文档扫描/增强在 `platform/DocumentScanEngine.kt`、`platform/ImageEnhancer.kt`；题目分割在 `domain/QuestionSegmenter.kt`（跨页合并、页眉过滤）。分割出的每道题必须独立成 `Exercise` 并带上 `sectionTitle/pageIndexes/crossPage/scanPaperId`。

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

- 教材/教师用书知识库数据接入（接口与 UI 已就绪，见 `domain/KnowledgeBase.kt`）。
- 可选：GitHub Actions 跑单测 + 构建（目前本地验证为主）。
