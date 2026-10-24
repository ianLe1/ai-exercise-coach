package com.example.aiexercise.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aiexercise.ui.AppScreen
import com.example.aiexercise.ui.AppViewModel
import com.example.aiexercise.ui.UiState
import com.example.aiexercise.ui.components.ScreenHeader
import com.example.aiexercise.ui.components.Tag

/**
 * 多页扫描的分割结果：一道题一行，点击进入该题自己的识别结果页
 * （每道题都是独立习题，各自维护「不会的知识点」与遗忘曲线计时器）。
 */
@Composable
fun SegmentsScreen(state: UiState, viewModel: AppViewModel) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                ScreenHeader(
                    title = "题目分割结果",
                    subtitle = "共 ${state.segments.size} 道题 · 已逐题建立习题与知识点",
                    onBack = { viewModel.navigate(AppScreen.Scan) },
                )
                Text(
                    "跨页题已合并为一条，并标注页码范围；点击任意题目可查看它自己的不会知识点与批改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 4.dp))
                Button(
                    onClick = viewModel::gradeScannedSegments,
                    enabled = !state.busy && state.segments.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(state.scanStage.ifBlank { "批改中…" })
                    } else {
                        Text("批改这次扫描（按纸面作答）")
                    }
                }
            }
        }
        items(state.segments, key = { it.exercise.id }) { segment ->
            val exercise = segment.exercise
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { viewModel.openSegment(segment) },
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Tag(exercise.sectionTitle.ifBlank { "未分组" })
                        Tag(pageLabel(exercise.pageIndexes, exercise.crossPage))
                        if (segment.isDuplicate) Tag("同一习题特判")
                        state.scanGrades[exercise.id]?.let { grade ->
                            Tag(if (grade.passed) "已批改 · ${grade.score} 分" else "已批改 · ${grade.score} 分（错）")
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${segment.knowledgePoints.size} 个知识点",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        exercise.questionText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (segment.knowledgePoints.isNotEmpty()) {
                        Text(
                            segment.knowledgePoints.joinToString("、") { it.title },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    segment.warning?.let { warning ->
                        Text(
                            warning.userMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        item {
            Spacer(Modifier.padding(bottom = 16.dp))
            Text(
                "批次：${state.scanPaperId.take(8)} · 知识点页可用「按习题查看」只看某一题的不会知识点。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun pageLabel(pageIndexes: List<Int>, crossPage: Boolean): String = when {
    pageIndexes.isEmpty() -> "页码未知"
    pageIndexes.size == 1 -> "第 ${pageIndexes.first()} 页"
    else -> "第 ${pageIndexes.min()}–${pageIndexes.max()} 页" + if (crossPage) " · 跨页" else ""
}
