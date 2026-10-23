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
import com.example.aiexercise.data.model.PaperQuestion
import com.example.aiexercise.data.model.PracticePaper
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
            title = "练习卷",
            subtitle = "按遗忘曲线挑选最该复习的知识点，交给 AI 生成结构化练习卷",
        )

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("⑥ 出卷设置")
                OutlinedTextField(
                    value = state.paperAspect,
                    onValueChange = viewModel::updatePaperAspect,
                    label = { Text("指定方面（如：二次函数 / 英语语法；留空=全部）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (aspects.isNotEmpty()) {
                    Text("可用的方面：", style = MaterialTheme.typography.labelSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(aspects.size) { index ->
                            val aspect = aspects[index]
                            AssistChip(
                                onClick = { viewModel.updatePaperAspect(aspect) },
                                label = { Text(aspect) },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("题目数量：", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(
                        onClick = { viewModel.updatePaperCount(state.paperCount - 1) },
                        enabled = state.paperCount > 1,
                    ) { Text("−") }
                    Spacer(Modifier.width(8.dp))
                    Text("${state.paperCount}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.updatePaperCount(state.paperCount + 1) },
                        enabled = state.paperCount < 20,
                    ) { Text("+") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = state.dueOnly,
                        onCheckedChange = { viewModel.toggleDueOnly() },
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("只从「已到期待复习」的知识点中选题", style = MaterialTheme.typography.bodySmall)
                }
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
                        Text("生成练习卷")
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
                "还没有生成过练习卷",
                "上方选择方面与题目数量，点「生成练习卷」。生成结果会是一个 JSON 文件内容，题面为 Markdown。",
            )
        } else {
            PaperContent(
                paper = paper,
                json = state.lastPaperJson,
                showJson = state.showPaperJson,
                onToggleJson = viewModel::toggleShowPaperJson,
                onCopyJson = {
                    state.lastPaperJson?.let { clipboard.setText(AnnotatedString(it)) }
                    viewModel.notify("练习卷 JSON 已复制到剪贴板")
                },
                now = now,
            )
        }
    }
}

@Composable
private fun PaperContent(
    paper: PracticePaper,
    json: String?,
    showJson: Boolean,
    onToggleJson: () -> Unit,
    onCopyJson: () -> Unit,
    now: Long,
) {
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
            HorizontalDivider()
            paper.questions.forEachIndexed { index, question ->
                QuestionCard(index = index, question = question)
            }
            HorizontalDivider()
            Row {
                TextButton(onClick = onToggleJson) {
                    Text(if (showJson) "隐藏 JSON" else "查看 JSON（Markdown 题面）")
                }
                TextButton(onClick = onCopyJson) { Text("复制 JSON") }
            }
            if (showJson && json != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    SelectionContainer {
                        Text(
                            text = json,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
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
                Tag(difficultyLabel(question.difficulty), containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(8.dp))
                Tag(question.knowledgePointTitle)
            }
            Text(
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
                Text("答案（Markdown）", style = MaterialTheme.typography.titleSmall)
                Text(question.answerMarkdown, style = MaterialTheme.typography.bodyMedium)
                if (question.explanationMarkdown.isNotBlank()) {
                    Text("解析（Markdown）", style = MaterialTheme.typography.titleSmall)
                    Text(question.explanationMarkdown, style = MaterialTheme.typography.bodyMedium)
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
