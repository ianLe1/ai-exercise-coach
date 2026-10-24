package com.example.aiexercise.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.aiexercise.ai.SearchHit
import com.example.aiexercise.ai.SearchMode
import com.example.aiexercise.data.model.ExerciseSource
import com.example.aiexercise.data.model.GradeResult
import com.example.aiexercise.data.model.ResearchBundle
import com.example.aiexercise.ui.AppScreen
import com.example.aiexercise.ui.AppViewModel
import com.example.aiexercise.ui.UiState
import com.example.aiexercise.ui.components.EmptyHint
import com.example.aiexercise.ui.components.KnowledgePointCard
import com.example.aiexercise.ui.components.ScreenHeader
import com.example.aiexercise.ui.components.SectionHeader
import com.example.aiexercise.ui.components.Tag

/**
 * 模块 2/3/4 的结果页：
 * 展示本次扫描的习题、AI 生成的“不会的知识点”、批改结果与相关知识点列表。
 */
@Composable
fun ResultScreen(state: UiState, viewModel: AppViewModel) {
    val scan = state.lastScan
    if (scan == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenHeader("识别结果", "还没有扫描记录", onBack = { viewModel.backFromResult() })
            EmptyHint("暂无识别结果", "回到扫描页拍一张习题照片，或点「填入示例习题」体验完整流程。")
            Button(onClick = { viewModel.backFromResult() }) { Text("去扫描") }
        }
        return
    }

    val now = state.lastScan?.let { System.currentTimeMillis() } ?: System.currentTimeMillis()
    val exercise = scan.exercise
    val relatedPoints = scan.knowledgePoints

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(
            title = "识别结果",
            subtitle = "习题 ${exercise.id.take(8)} · ${sourceLabel(exercise.source)} · 已扫描 ${exercise.scanCount} 次",
            onBack = { viewModel.backFromResult() },
        )

        // 模块 5：连续扫描同一习题的特判提示
        if (scan.isDuplicate) {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("已触发「同一习题」特判", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "本次没有新建习题或知识点：复用 ${relatedPoints.size} 个关联知识点，" +
                            "并把其中 ${scan.refreshedKnowledgePointCount} 个的遗忘曲线计时器重置为 1 天后、" +
                            "掌握度压回 35% 以下。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        scan.warning?.let { warning ->
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    "知识点合并时 AI 调用失败：${warning.userMessage}。已保留原知识点并刷新计时器。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // 题目
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("题目", trailing = { Tag(exercise.subject) })
                Text(exercise.questionText, style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(sourceLabel(exercise.source))
                    if (exercise.sectionTitle.isNotBlank()) {
                        Tag(exercise.sectionTitle)
                    }
                    if (exercise.pageIndexes.isNotEmpty()) {
                        Tag(exercisePageLabel(exercise.pageIndexes, exercise.crossPage))
                    }
                    if (exercise.fingerprint.isNotBlank()) {
                        Tag("指纹 ${exercise.fingerprint.take(10)}")
                    }
                }
            }
        }

        // 自动资料检索（AI 分类前固定流水线生成的证据，折叠展示，不打断主线）
        (scan.research ?: exercise.lastResearch)?.let { bundle -> ResearchCard(bundle) }

        // 模块 2/4：不会的知识点
        SectionHeader("② 本题的不会知识点（${relatedPoints.size}）")
        if (relatedPoints.isEmpty()) {
            EmptyHint(
                "没有识别到知识点",
                "可能是题目太简单或参考答案缺失。补充参考答案后重新分析，AI 会据此生成薄弱知识点。",
            )
        } else {
            relatedPoints.forEach { point ->
                KnowledgePointCard(
                    point = point,
                    now = now,
                    onReview = { quality -> viewModel.review(point.id, quality) },
                )
            }
        }

        // 联网搜索（必应）：题目正文 + 关联知识点作为关键词
        if (state.searchMode != SearchMode.OFF) {
            SearchSection(state = state, onSearch = viewModel::searchRelated)
        }

        // 后续动作：知识点已入库，回到首页列表；需要出卷/批改都去「设置」
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = viewModel::rescanLastExercise,
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) { Text("重新扫描此题") }
            Button(
                onClick = { viewModel.navigate(AppScreen.Home) },
                modifier = Modifier.weight(1f),
            ) { Text("返回知识点列表") }
        }
    }
}

@Composable
private fun SearchSection(state: UiState, onSearch: () -> Unit) {
    val context = LocalContext.current
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("联网搜索（必应）")
            Text(
                "把题目正文与关联知识点作为关键词，搜索讲解资料或同类题（结果来自必应，仅供参考）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onSearch,
                enabled = !state.searchBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.searchBusy) {
                    CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("搜索中…")
                } else {
                    Text("用必应搜索相关资料 / 同类题")
                }
            }
            if (state.searchQuery.isNotBlank()) {
                Text(
                    "关键词：${state.searchQuery}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.searchHits.forEach { hit ->
                SearchHitCard(hit) { url -> openUrl(context, url) }
            }
        }
    }
}

@Composable
private fun SearchHitCard(hit: SearchHit, onOpen: (String) -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(hit.url) },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(hit.title, style = MaterialTheme.typography.titleSmall)
            if (hit.snippet.isNotBlank()) {
                Text(hit.snippet, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                hit.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
}

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "无法打开链接：$url", Toast.LENGTH_SHORT).show() }
}

@Composable
private fun GradeCard(grade: GradeResult) {
    val container = if (grade.passed) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = container),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${grade.score}", style = MaterialTheme.typography.displaySmall)
                Text(" 分", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Tag(if (grade.passed) "通过" else "未通过")
            }
            Text("批改解析（Markdown）", style = MaterialTheme.typography.titleSmall)
            Text(grade.analysisMarkdown, style = MaterialTheme.typography.bodyMedium)
            if (grade.errorSteps.isNotEmpty()) {
                HorizontalDivider()
                Text("失分点", style = MaterialTheme.typography.titleSmall)
                grade.errorSteps.forEach { step ->
                    Text("• $step", style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
            Text("正确答案：${grade.correctAnswer}", style = MaterialTheme.typography.bodySmall)
            if (grade.relatedKnowledgePointTitles.isNotEmpty()) {
                Text(
                    "AI 认为涉及的知识点：${grade.relatedKnowledgePointTitles.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!grade.passed) {
                Text(
                    "未通过：相关知识点已提前到 1 天内复习，掌握度同步下调。",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

fun sourceLabel(source: ExerciseSource): String = when (source) {
    ExerciseSource.OCR_CAMERA -> "拍照 OCR"
    ExerciseSource.OCR_GALLERY -> "相册 OCR"
    ExerciseSource.MANUAL -> "手动输入"
}

/**
 * 自动资料检索证据卡：
 * 展示“网络搜索 → AI 过滤出本题解析 → 知识库依据”三步的结果状态，
 * 默认收起，避免打断“维护不会的知识点列表”这条主线。
 */
@Composable
private fun ResearchCard(bundle: ResearchBundle) {
    var expanded by remember { mutableStateOf(false) }
    val status = buildString {
        append(
            when (bundle.webState) {
                ResearchBundle.WEB_OK -> "网络解析：已筛出本题解析"
                ResearchBundle.WEB_NO_MATCH -> "网络解析：结果与本题不匹配"
                ResearchBundle.WEB_FAILED -> "网络解析：检索失败"
                else -> "网络解析：未启用"
            },
        )
        append(" · ")
        append(
            when (bundle.kbState) {
                ResearchBundle.KB_OK -> "教材依据：${bundle.kbCitations.size} 条"
                ResearchBundle.KB_EMPTY -> "教材依据：无匹配"
                ResearchBundle.KB_FAILED -> "教材依据：检索失败"
                else -> "教材依据：知识库未接入"
            },
        )
        if (bundle.webHits.isNotEmpty()) append(" · 命中 ${bundle.webHits.size} 条")
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("① 自动资料检索（AI 分类前）")
            Text(status, style = MaterialTheme.typography.bodySmall)
            if (bundle.digest.relevant && bundle.digest.solutionMarkdown.isNotBlank()) {
                Text("AI 筛出的本题解析", style = MaterialTheme.typography.labelLarge)
                Text(bundle.digest.solutionMarkdown, style = MaterialTheme.typography.bodySmall)
                if (bundle.digest.matchedTitles.isNotEmpty()) {
                    Text(
                        "采纳来源：${bundle.digest.matchedTitles.joinToString("、")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (bundle.warning.isNotBlank()) {
                Text(
                    "提示：${bundle.warning}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起检索详情" else "展开检索详情（${bundle.webHits.size} 条网络命中）")
            }
            if (expanded) {
                bundle.webHits.forEach { hit ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("• ${hit.title}", style = MaterialTheme.typography.labelLarge)
                        if (hit.snippet.isNotBlank()) {
                            Text(hit.snippet, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            hit.url,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (bundle.kbCitations.isNotEmpty()) {
                    HorizontalDivider()
                    Text("教材 / 教师用书依据", style = MaterialTheme.typography.labelLarge)
                    bundle.kbCitations.forEach { citation ->
                        Text("· ${citation.citationLabel()}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** 题目页码范围标签，例如「第1–2页 · 跨页」。 */
private fun exercisePageLabel(pageIndexes: List<Int>, crossPage: Boolean): String = when {
    pageIndexes.size <= 1 -> "第 ${pageIndexes.first()} 页"
    else -> "第 ${pageIndexes.min()}–${pageIndexes.max()} 页" + if (crossPage) " · 跨页" else ""
}
