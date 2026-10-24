package com.example.aiexercise.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.aiexercise.domain.ForgettingCurve
import com.example.aiexercise.ui.AppScreen
import com.example.aiexercise.ui.AppViewModel
import com.example.aiexercise.ui.UiState
import com.example.aiexercise.ui.components.EmptyHint
import com.example.aiexercise.ui.components.KnowledgePointCard

/**
 * 首页：只做一件事 —— 按复习时间（遗忘曲线紧迫度）排列「不会的知识点」，外加一个扫描入口。
 * 练习卷、批改、视觉/搜索/知识库配置等全部收进「设置」，不再占底部导航。
 */
@Composable
fun HomeScreen(state: UiState, viewModel: AppViewModel) {
    val now = System.currentTimeMillis()
    var showWelcome by remember { mutableStateOf(false) }
    // 第一次使用：用户信息为空时提示填写（年级/目标会影响 AI 生成的知识点与练习卷）
    LaunchedEffect(state.data.asker) {
        val asker = state.data.asker
        showWelcome = asker.name.isBlank() && asker.gradeLevel.isBlank() && asker.goal.isBlank()
    }
    val points = state.data.knowledgePoints
        .filter { !it.archived }
        .sortedByDescending { ForgettingCurve.urgency(it.timer, it.mastery, now) }
    val dueCount = points.count { ForgettingCurve.isDue(it.timer, now) }

    if (showWelcome) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("第一次使用，先填一下你的信息") },
            text = { Text("填一下称呼、年级和学习目标，AI 会按你的水平调整知识点与练习难度。") },
            confirmButton = {
                TextButton(onClick = {
                    showWelcome = false
                    viewModel.navigate(AppScreen.Settings)
                }) { Text("去填写") }
            },
            dismissButton = {
                TextButton(onClick = { showWelcome = false }) { Text("稍后") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("不会的知识点", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "按复习时间排序 · 共 ${points.size} 条 · 今日到期 $dueCount 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { viewModel.navigate(AppScreen.Settings) }) {
                Icon(Icons.Filled.Settings, contentDescription = "设置")
            }
        }

        Button(
            onClick = { viewModel.navigate(AppScreen.Scan) },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("  扫描习题", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(
            onClick = viewModel::generatePaperFull,
            enabled = !state.busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text("生成一整张试卷（100 分 / 90 分钟）")
        }
        Text(
            "拍下不会的题，AI 会整理成知识点并安排复习时间；试卷生成后打印或在纸上作答，再用上面的扫描批改。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (points.isEmpty()) {
            EmptyHint(
                "还没有知识点",
                "点上面的「扫描习题」，拍下题目后 AI 会自动整理成不会的知识点，并按遗忘曲线排好复习时间。",
            )
        } else {
            points.forEach { point ->
                KnowledgePointCard(
                    point = point,
                    now = now,
                    onReview = { quality -> viewModel.review(point.id, quality) },
                )
            }
        }
    }
}
