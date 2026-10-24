package com.example.aiexercise.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.aiexercise.ai.AiJson
import com.example.aiexercise.data.model.PaperQuestion
import com.example.aiexercise.data.model.PracticePaper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.example.aiexercise.ui.AppScreen
import com.example.aiexercise.ui.AppViewModel
import com.example.aiexercise.ui.UiState
import com.example.aiexercise.ui.components.EmptyHint
import com.example.aiexercise.ui.components.ScreenHeader
import com.example.aiexercise.ui.components.SectionHeader
import com.example.aiexercise.ui.components.Tag
import com.example.aiexercise.ui.components.formatDateTime
import kotlin.math.roundToInt

/**
 * 模块 6：练习卷生成。
 * 由遗忘曲线计时器挑出最紧迫的知识点 → AI 生成结构化练习卷（JSON），题面为 Markdown。
 */
@Composable
fun PaperScreen(state: UiState, viewModel: AppViewModel) {
    val now = System.currentTimeMillis()
    val clipboard = LocalClipboardManager.current
    val answerPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9),
    ) { uris -> viewModel.onPracticePagesPicked(uris) }
    var answerCameraFile by remember { mutableStateOf<java.io.File?>(null) }
    val answerCameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        answerCameraFile?.let { file ->
            if (success) {
                viewModel.onPracticePagesPicked(listOf(android.net.Uri.fromFile(file)))
                viewModel.notify("已拍下第 ${state.practicePages.size + 1} 页答题卡，可继续拍", false)
            }
        }
    }
    val aspects = state.data.knowledgePoints.filter { !it.archived }.flatMap { listOf(it.aspect, it.subject) }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(
            title = "试卷",
            subtitle = "按遗忘曲线出一整张正规试卷，作答后用「扫描习题」批改",
            onBack = { viewModel.navigate(AppScreen.Home) },
        )

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("整卷生成")
                Text(
                    "按遗忘曲线紧迫度自动选题，生成一整张正规试卷：总分 100 分、90 分钟，" +
                        "含选择题 / 填空题 / 解答题三大题。生成后可打印或在纸上作答，再用「扫描习题」批改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { viewModel.generatePaper() },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("AI 出卷中…")
                    } else {
                        Text("生成一整张试卷")
                    }
                }
            }
        }

        if (state.data.papers.isNotEmpty()) {
            SectionHeader("历史练习卷（${state.data.papers.size}）")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.data.papers.size) { index ->
                    val paper = state.data.papers[index]
                    AssistChip(
                        onClick = { viewModel.openPaper(paper.id) },
                        label = { Text("${paper.aspect} · ${formatDateTime(paper.generatedAt)}") },
                    )
                }
            }
        }

        val paper = state.lastPaper
        if (paper == null) {
            EmptyHint(
                "还没有生成过试卷",
                "点上方「生成一整张试卷」，AI 会按遗忘曲线挑知识点，生成 100 分 / 90 分钟的正规试卷。",
            )
        } else {
            PaperContent(
                paper = paper,
                sheetJson = paper.answerSheetJson.ifBlank { state.lastPaperJson ?: "{}" },
                showJson = state.showPaperJson,
                onToggleJson = viewModel::toggleShowPaperJson,
                onCopyJson = {
                    val text = paper.answerSheetJson.ifBlank { state.lastPaperJson.orEmpty() }
                    clipboard.setText(AnnotatedString(text))
                    viewModel.notify("答题卡 JSON 已复制到剪贴板")
                },
                showFullJson = state.showFullPaperJson,
                onToggleFullJson = viewModel::toggleShowFullPaperJson,
                now = now,
            )
        }
    }
}

@Composable
private fun PaperContent(
    paper: PracticePaper,
    sheetJson: String,
    showJson: Boolean,
    onToggleJson: () -> Unit,
    onCopyJson: () -> Unit,
    showFullJson: Boolean,
    onToggleFullJson: () -> Unit,
    now: Long,
) {
    val fullJson = remember(paper) { AiJson.encodePaper(paper) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(paper.title, trailing = { Tag(paper.aspect) })
            if (paper.summary.isNotBlank()) {
                Text(paper.summary, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "生成于 ${formatDateTime(paper.generatedAt)} · 依据 ${paper.knowledgePoints.size} 个知识点的遗忘曲线选取",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                paper.knowledgePoints.joinToString("；") {
                    val dueText = if (it.nextReviewAt <= now) "已到期" else "约 ${daysBetween(now, it.nextReviewAt)} 天后复习"
                    "${it.title}（紧迫度 ${(it.urgency * 100).roundToInt()}，$dueText）"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "总分 100 分 · 考试时间 90 分钟 · 共 ${paper.questions.size} 题",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            var questionNo = 0
            com.example.aiexercise.domain.PaperStructure.groupBySection(paper.questions).forEach { (section, questions) ->
                Text(
                    section,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "共 ${questions.size} 题 · ${questions.sumOf { it.score }} 分",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                questions.forEach { question ->
                    questionNo += 1
                    QuestionCard(index = questionNo - 1, question = question)
                }
            }
            HorizontalDivider()
            Text(
                "答题卡 JSON 规范：数字 = 答题区行数；数组 = 选择题选项；字符串（含 ___）= 填空题；" +
                    "对象 = 小题（可无）。题面与答案是 Markdown，另存于练习卷数据，可切换「完整 JSON」查看。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = onToggleJson) {
                    Text(if (showJson) "隐藏答题卡 JSON" else "查看答题卡 JSON")
                }
                TextButton(onClick = onCopyJson) { Text("复制答题卡 JSON") }
                TextButton(onClick = onToggleFullJson) {
                    Text(if (showFullJson) "隐藏完整 JSON" else "完整 JSON（含题面）")
                }
            }
            if (showJson) {
                JsonBlock(sheetJson)
            }
            if (showFullJson) {
                JsonBlock(fullJson)
            }
        }
    }
}

@Composable
private fun QuestionCard(index: Int, question: PaperQuestion) {
    var showAnswer by remember(question.id) { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag("第 ${index + 1} 题")
                Spacer(Modifier.width(8.dp))
                Tag("${question.score} 分")
                Spacer(Modifier.width(8.dp))
                Tag(difficultyLabel(question.difficulty), containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(8.dp))
                Tag(question.knowledgePointTitle)
            }
            com.example.aiexercise.ui.components.MarkdownText(
                question.questionMarkdown,
                style = MaterialTheme.typography.bodyLarge,
            )
            // 选择题的选项通常已经写进 Markdown 题面（AI 模板会带 A/B/C/D），
            // 只有题面里没有选项时才单独渲染 options，避免重复显示。
            val optionsAlreadyInMarkdown = question.options.isNotEmpty() &&
                question.options.all { question.questionMarkdown.contains(it) }
            if (question.options.isNotEmpty() && !optionsAlreadyInMarkdown) {
                question.options.forEach { option -> Text(option, style = MaterialTheme.typography.bodyMedium) }
            }
            TextButton(onClick = { showAnswer = !showAnswer }) {
                Text(if (showAnswer) "收起答案与解析" else "显示答案与解析")
            }
            if (showAnswer) {
                Text("答案", style = MaterialTheme.typography.titleSmall)
                com.example.aiexercise.ui.components.MarkdownText(question.answerMarkdown)
                if (question.explanationMarkdown.isNotBlank()) {
                    Text("解析", style = MaterialTheme.typography.titleSmall)
                    com.example.aiexercise.ui.components.MarkdownText(question.explanationMarkdown)
                }
            }
        }
    }
}

private fun difficultyLabel(value: String): String = when (value.lowercase()) {
    "easy" -> "易"
    "hard" -> "难"
    else -> "中"
}

private fun daysBetween(from: Long, to: Long): Long {
    val diff = (to - from).coerceAtLeast(0L)
    return diff / (24 * 60 * 60 * 1000)
}

@Composable
private fun JsonBlock(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        SelectionContainer {
            Text(
                text = text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}
