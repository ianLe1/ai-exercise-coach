package com.example.aiexercise.ui.screens

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.aiexercise.domain.ForgettingCurve
import com.example.aiexercise.ui.AppViewModel
import com.example.aiexercise.ui.UiState
import com.example.aiexercise.ui.components.EmptyHint
import com.example.aiexercise.ui.components.KnowledgePointCard
import com.example.aiexercise.ui.components.ScreenHeader
import com.example.aiexercise.ui.components.SectionHeader
import kotlin.math.roundToInt

/**
 * 模块 4：知识点汇总。
 * 列出全部“不会的知识点”，按遗忘曲线紧迫度排序，并支持复习自评更新计时器。
 */
@Composable
fun KnowledgeScreen(state: UiState, viewModel: AppViewModel) {
    val now = System.currentTimeMillis()
    var filter by remember { mutableStateOf("") }
    var dueOnly by remember { mutableStateOf(false) }

    val allPoints = state.data.knowledgePoints.filter { !it.archived }
    val dueCount = allPoints.count { ForgettingCurve.isDue(it.timer, now) }
    val selectedExerciseId = state.filterExerciseId
    val exercisesWithPoints = state.data.exercises
        .filter { exercise -> allPoints.any { exercise.id in it.sourceExerciseIds } }
        .sortedByDescending { it.lastSeenAt }
    val filtered = allPoints
        .filter { point ->
            selectedExerciseId == null || selectedExerciseId in point.sourceExerciseIds
        }
        .filter { point ->
            val keywordMatch = filter.isBlank() ||
                point.title.contains(filter, ignoreCase = true) ||
                point.aspect.contains(filter, ignoreCase = true) ||
                point.subject.contains(filter, ignoreCase = true) ||
                point.detail.contains(filter, ignoreCase = true)
            keywordMatch && (!dueOnly || ForgettingCurve.isDue(point.timer, now))
        }
        .sortedByDescending { ForgettingCurve.urgency(it.timer, it.mastery, now) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(
            title = "不会的知识点",
            subtitle = "共 ${allPoints.size} 条 · 今日到期 $dueCount 条 · 计时器随复习自动更新",
        )

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("概览")
                val averageMastery = if (allPoints.isEmpty()) 0.0 else allPoints.map { it.mastery }.average()
                Text(
                    "平均掌握度 ${(averageMastery * 100).roundToInt()}% · " +
                        "累计复习 ${allPoints.sumOf { it.timer.reviewCount }} 次 · " +
                        "逾期未复习 ${allPoints.count { ForgettingCurve.overdueDays(it.timer, now) > 0 }} 条",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "遗忘曲线算法：简化 SM-2 + 指数遗忘（R=e^(-t/S)）。紧迫度 = 保持率缺口 0.35 + 逾期 0.25 + 掌握度缺口 0.20 + 到期加成 0.20。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 按习题查看：随时列出“某道习题内的不会知识点”
        if (exercisesWithPoints.isNotEmpty()) {
            SectionHeader("按习题查看")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedExerciseId == null,
                    onClick = { viewModel.filterKnowledgeByExercise(null) },
                    label = { Text("全部（${allPoints.size}）") },
                )
                exercisesWithPoints.forEach { exercise ->
                    val count = allPoints.count { exercise.id in it.sourceExerciseIds }
                    val label = exercise.questionText.replace("\n", " ").take(12)
                    FilterChip(
                        selected = selectedExerciseId == exercise.id,
                        onClick = {
                            viewModel.filterKnowledgeByExercise(
                                if (selectedExerciseId == exercise.id) null else exercise.id,
                            )
                        },
                        label = { Text("$label…（$count）") },
                    )
                }
            }
        }

        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("按方面 / 学科 / 标题 / 细节筛选") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = dueOnly, onCheckedChange = { dueOnly = it })
            Spacer(Modifier.width(4.dp))
            Text("只显示已到期待复习的知识点", style = MaterialTheme.typography.bodySmall)
        }

        if (allPoints.isEmpty()) {
            EmptyHint(
                "还没有知识点",
                "先扫描一道习题并补充参考答案，AI 会生成「不会的知识点」并开始计时。",
            )
        } else if (filtered.isEmpty()) {
            EmptyHint("没有匹配的知识点", "换个关键词，或取消“只看已到期”的筛选。")
        } else {
            filtered.forEach { point ->
                KnowledgePointCard(
                    point = point,
                    now = now,
                    onReview = { quality -> viewModel.review(point.id, quality) },
                )
            }
        }
    }
}
