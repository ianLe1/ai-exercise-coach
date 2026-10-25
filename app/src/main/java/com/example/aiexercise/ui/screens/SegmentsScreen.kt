package com.example.aiexercise.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
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
    // 选中态不跟随 segments 列表变化重置（批改会更新列表，否则选中会跳回第 1 题）
    var selectedId by remember { mutableStateOf<String?>(null) }
    val effectiveSelectedId = selectedId
        ?.takeIf { id -> state.segments.any { it.exercise.id == id } }
        ?: state.segments.firstOrNull { it.exercise.boxLeft >= 0f }?.exercise?.id
        ?: state.segments.firstOrNull()?.exercise?.id
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
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(state.scanStage.ifBlank { "批改中…" })
                    } else {
                        Text("批改这次扫描（按纸面作答）")
                    }
                }
            }
        }
        if (state.segments.any { it.exercise.boxLeft >= 0f }) {
            item {
                ScanPaperBoxCard(
                    state = state,
                    selectedId = effectiveSelectedId,
                    onSelect = { id -> selectedId = id },
                    onGradeOne = { id -> viewModel.gradeScannedSegments(id) },
                )
            }
        }
        items(state.segments, key = { it.exercise.id }) { segment ->
            val exercise = segment.exercise
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (segment.exercise.id == effectiveSelectedId) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                        } else {
                            Modifier
                        },
                    ),
                onClick = {
                    selectedId = segment.exercise.id
                    viewModel.openSegment(segment)
                },
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


/** 扫描件上直接把每道题框出来（作业帮式），点框选题、可只批改这一题。 */
@Composable
private fun ScanPaperBoxCard(
    state: UiState,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onGradeOne: (String) -> Unit,
) {
    val selected = state.segments.firstOrNull { it.exercise.id == selectedId }
        ?: state.segments.firstOrNull { it.exercise.boxLeft >= 0f }
        ?: return
    val pageIndex = selected.exercise.pageIndexes.firstOrNull() ?: 1
    val page = state.scanPages.firstOrNull { it.index == pageIndex } ?: return
    val bitmap = remember(page.imagePath) { decodeSampledBitmap(page.imagePath) } ?: return
    val boxes = remember(state.segments, pageIndex) {
        state.segments.mapNotNull { segment ->
            val exercise = segment.exercise
            if (exercise.boxLeft < 0f) return@mapNotNull null
            if ((exercise.pageIndexes.firstOrNull() ?: 1) != pageIndex) return@mapNotNull null
            BoxedQuestion(
                id = exercise.id,
                label = (state.segments.indexOf(segment) + 1).toString(),
                left = exercise.boxLeft,
                top = exercise.boxTop,
                right = exercise.boxRight,
                bottom = exercise.boxBottom,
                passed = state.scanGrades[exercise.id]?.passed,
                scored = state.scanGrades[exercise.id] != null,
            )
        }
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("扫描件 · 第 $pageIndex 页", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    "点框选题 · 共 ${boxes.size} 题",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
                    .clip(RoundedCornerShape(10.dp))
                    .pointerInput(boxes) {
                        detectTapGestures { offset ->
                            val nx = offset.x / size.width
                            val ny = offset.y / size.height
                            val hit = boxes.lastOrNull { box ->
                                nx >= box.left && nx <= box.right && ny >= box.top && ny <= box.bottom
                            }
                            hit?.let { onSelect(it.id) }
                        }
                    },
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "扫描件第 $pageIndex 页",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    boxes.forEach { box ->
                        val isSelected = box.id == selectedId
                        val color = when {
                            isSelected -> Color(0xFFE53935)
                            box.scored && box.passed == true -> Color(0xFF2E7D32)
                            box.scored -> Color(0xFFF9A825)
                            else -> Color(0xFF1E88E5)
                        }
                        val left = box.left * size.width
                        val top = box.top * size.height
                        val right = box.right * size.width
                        val bottom = box.bottom * size.height
                        drawRect(
                            color = color.copy(alpha = if (isSelected) 0.18f else 0.10f),
                            topLeft = Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                        )
                        drawRect(
                            color = color,
                            topLeft = Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = if (isSelected) 5f else 3f,
                            ),
                        )
                        drawCircle(
                            color = color,
                            radius = if (isSelected) 15f else 12f,
                            center = Offset(left + 6f, top + 6f),
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已选第 ${(state.segments.indexOfFirst { it.exercise.id == selected.exercise.id } + 1)} 题",
                    style = MaterialTheme.typography.labelMedium,
                )
                state.scanGrades[selected.exercise.id]?.let { grade ->
                    Tag(if (grade.passed) "已批改 · ${grade.score} 分" else "已批改 · ${grade.score} 分（错）")
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onGradeOne(selected.exercise.id) },
                    enabled = !state.busy,
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("批改这一题")
                }
            }
        }
    }
}

private data class BoxedQuestion(
    val id: String,
    val label: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val scored: Boolean,
    val passed: Boolean?,
)

private fun decodeSampledBitmap(path: String, targetWidth: Int = 1080): android.graphics.Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()
