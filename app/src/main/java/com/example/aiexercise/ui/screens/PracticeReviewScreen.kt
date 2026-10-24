package com.example.aiexercise.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aiexercise.data.model.KnowledgePoint
import com.example.aiexercise.data.model.PracticeWrong
import com.example.aiexercise.ui.AppScreen
import com.example.aiexercise.ui.AppViewModel
import com.example.aiexercise.ui.UiState
import com.example.aiexercise.ui.components.EmptyHint
import com.example.aiexercise.ui.components.ScreenHeader
import com.example.aiexercise.ui.components.SectionHeader
import com.example.aiexercise.ui.components.Tag
import com.example.aiexercise.ui.components.formatDateTime
import com.example.aiexercise.domain.ForgettingCurve

/**
 * 模块三：完成练习 → 扫描答题卡 → 批改 → 仍不会的知识点。
 *
 * 订正后可回练习卷页再扫一次（同一份练习卷的 [PracticeReview.round] 会累加）；
 * 对仍错的题可发起「引导式提问」，会话信息在结题时并入不会的知识点列表。
 */
@Composable
fun PracticeReviewScreen(state: UiState, viewModel: AppViewModel) {
    val review = state.lastReview
    val now = System.currentTimeMillis()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(
            title = "练习批改",
            subtitle = "扫描答题卡 → 逐题批改 → 仍不会的知识点 → 订正后再扫",
            onBack = { viewModel.navigate(AppScreen.Settings) },
        )

        if (review == null) {
            EmptyHint("暂无练习批改记录", "先在「练习卷」页生成练习卷，做完后扫描答题卡。")
            return@Column
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${review.score} 分", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(10.dp))
                    Tag(
                        if (review.passed) "已通过" else "未通过",
                        containerColor = if (review.passed) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.errorContainer,
                    )
                    Spacer(Modifier.width(10.dp))
                    Tag("第 ${review.round} 轮")
                }
                Text(
                    "共 ${review.totalQuestions} 题，本次批改 ${review.gradedQuestions} 题；" +
                        "仍错 ${review.wrong.size} 题 · 涉及不会知识点 ${review.relatedKnowledgePointIds.size} 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "扫描时间：${formatDateTime(review.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (review.note.isNotBlank()) {
                    Text(review.note, style = MaterialTheme.typography.bodySmall)
                }
                if (review.hasBlockedQuestions) {
                    Text(
                        "有题目的订正质量太低，需先重做（引导式提问暂不开放）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        SectionHeader("仍不会的知识点（${state.reviewPoints.size}）")
        if (state.reviewPoints.isEmpty()) {
            EmptyHint("没有仍不会的知识点", "本次批改涉及的知识点都已掌握或未被识别为不会。")
        } else {
            state.reviewPoints.forEach { point ->
                KnowledgePointSummaryCard(point, now)
            }
        }

        SectionHeader("仍答错的题（${review.wrong.size}）")
        if (review.wrong.isEmpty()) {
            EmptyHint("全部答对", "这份练习卷本次没有答错的题。")
        } else {
            review.wrong.forEach { wrong ->
                WrongQuestionCard(
                    wrong = wrong,
                    guidedOpen = state.guidedSession?.questionId == wrong.questionId,
                    state = state,
                    viewModel = viewModel,
                )
            }
        }

        val session = state.guidedSession
        if (session != null) {
            GuidedSessionCard(state, viewModel, session)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    viewModel.clearPracticePages()
                    viewModel.navigate(AppScreen.Paper)
                },
                modifier = Modifier.weight(1f),
            ) { Text("订正后再扫一次") }
            OutlinedButton(
                onClick = { viewModel.navigate(AppScreen.Paper) },
                modifier = Modifier.weight(1f),
            ) { Text("返回练习卷") }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun KnowledgePointSummaryCard(point: KnowledgePoint, now: Long) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(point.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Tag(point.aspect.ifBlank { point.subject })
            }
            Text(
                "掌握度 ${(point.mastery * 100).toInt()}% · " +
                    ForgettingCurve.nextReviewLabel(point.timer, now) +
                    " · 紧迫度 ${ForgettingCurve.urgency(point.timer, point.mastery, now).let { (it * 100).toInt() }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (point.detail.isNotBlank()) {
                Text(point.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun WrongQuestionCard(
    wrong: PracticeWrong,
    guidedOpen: Boolean,
    state: UiState,
    viewModel: AppViewModel,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(wrong.questionTitle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Tag(
                    correctionQualityLabel(wrong.correctionQuality),
                    containerColor = if (wrong.correctionQuality == "LOW") MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.secondaryContainer,
                )
            }
            Text(wrong.questionText, style = MaterialTheme.typography.bodyMedium)
            Text("你的作答：${wrong.studentAnswer.ifBlank { "（未识别到作答）" }}", style = MaterialTheme.typography.bodySmall)
            Text("正确答案：${wrong.correctAnswer.ifBlank { "（参考答案缺失）" }}", style = MaterialTheme.typography.bodySmall)
            if (wrong.explanationMarkdown.isNotBlank()) {
                Text(wrong.explanationMarkdown, style = MaterialTheme.typography.bodySmall)
            }
            if (!wrong.mayAskGuided) {
                Text(
                    "订正质量太低（像是在猜测），先不看答案重做一遍再提问。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (!guidedOpen) {
                TextButton(onClick = { viewModel.openGuided(wrong.questionId) }) {
                    Text("针对这题提问（引导式，不直接给答案）")
                }
            }
        }
    }
}

@Composable
private fun GuidedSessionCard(state: UiState, viewModel: AppViewModel, session: com.example.aiexercise.data.model.GuidedSession) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("引导式提问：${session.questionTitle}")
            Text(
                "AI 只给思路与追问，不直接给答案；你说出关键步骤后，本次会话的信息会并入对应的不会知识点。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (session.exchanges.isEmpty()) {
                Text("（还没有对话，先说说你卡在哪一步？）", style = MaterialTheme.typography.bodySmall)
            } else {
                session.exchanges.forEach { exchange ->
                    val isStudent = exchange.role == "student"
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            if (isStudent) "我：" else "引导：",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(exchange.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (session.refused) {
                Text(
                    "AI 判断本题订正质量不足，已拒绝继续：请先重做。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(
                value = state.guidedInput,
                onValueChange = viewModel::updateGuidedInput,
                label = { Text("你的想法 / 卡住的步骤") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = viewModel::replyGuided,
                    enabled = !state.guidedBusy && state.guidedInput.isNotBlank(),
                ) {
                    if (state.guidedBusy) {
                        CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("发送")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = viewModel::closeGuided) { Text("收起") }
            }
        }
    }
}

private fun correctionQualityLabel(quality: String): String = when (quality) {
    "HIGH" -> "订正到位"
    "MEDIUM" -> "订正一般"
    "LOW" -> "订正质量低"
    else -> "未看订正"
}
