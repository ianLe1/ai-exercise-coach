package com.example.aiexercise.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.aiexercise.ui.AppViewModel
import com.example.aiexercise.ui.UiState
import com.example.aiexercise.ui.components.SectionHeader
import com.example.aiexercise.ui.components.Tag
import java.io.File

/**
 * 模块 1：习题扫描。
 * 三种输入：拍照（系统相机 + FileProvider）/ 相册（Photo Picker）/ 手动输入文本。
 * 拍照与相册走可替换的视觉引擎（默认 ML Kit 离线 OCR，可切 modlens 视觉桥）。
 */
@Composable
fun ScanScreen(state: UiState, viewModel: AppViewModel) {
    var cameraFile by remember { mutableStateOf<File?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        cameraFile?.let { file -> if (success) viewModel.onCameraCaptured(file) }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::onGalleryPicked)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("AI 习题教练", style = MaterialTheme.typography.headlineMedium)
        Text(
            "扫描习题 → AI 生成并维护「不会的知识点」与遗忘曲线计时器 → 批改 → 按遗忘曲线生成练习卷。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag("当前 AI：${state.aiLabel}")
            Spacer(Modifier.width(8.dp))
            Text(
                "可在「设置」切换离线/云端引擎",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag("视觉：${state.visionLabel}")
            Spacer(Modifier.width(8.dp))
            Text(
                "可切 modlens 视觉桥",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("① 扫描习题")
                Text(
                    "拍照或从相册选择，自动 OCR 识别题目文字；也可以直接手动输入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            val (file, uri) = viewModel.createCameraOutput()
                            cameraFile = file
                            cameraLauncher.launch(uri)
                        },
                        enabled = !state.ocrBusy && !state.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("拍照识别") }
                    OutlinedButton(
                        onClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        enabled = !state.ocrBusy && !state.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("从相册选择") }
                }
                if (state.ocrBusy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在识别（${state.visionLabel}）…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.imagePath?.let { path ->
                    ImagePreview(path)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "已读取图片：${File(path).name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = state.scanText,
            onValueChange = viewModel::updateScanText,
            label = { Text("题目内容（OCR 结果可编辑）") },
            minLines = 5,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.ocrBusy,
        )
        OutlinedTextField(
            value = state.referenceAnswer,
            onValueChange = viewModel::updateReferenceAnswer,
            label = { Text("参考答案（AI 据此生成知识点、批改时使用）") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.ocrBusy,
        )
        OutlinedTextField(
            value = state.studentAnswer,
            onValueChange = viewModel::updateStudentAnswer,
            label = { Text("我的作答（批改时使用，可稍后补充）") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.ocrBusy,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = viewModel::scan,
                enabled = !state.busy && !state.ocrBusy,
                modifier = Modifier.weight(1f),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("AI 分析中…")
                } else {
                    Text("AI 分析知识点")
                }
            }
            TextButton(onClick = viewModel::fillDemo, enabled = !state.busy) {
                Text("填入示例习题")
            }
        }

        // 固定流水线的阶段提示：联网搜索本题解析 → AI 过滤 → 知识库 → AI 分类
        if (state.busy && state.scanStage.isNotBlank()) {
            Text(
                "⏳ ${state.scanStage}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        state.lastScan?.let { scan ->
            TextButton(onClick = { viewModel.navigate(com.example.aiexercise.ui.AppScreen.Result) }) {
                Text("查看上次识别结果：${scan.knowledgePoints.size} 个知识点，已扫描 ${scan.exercise.scanCount} 次")
            }
        }

        Text(
            "提示：同一道题连续扫描会触发「同一习题」特判 —— 不会重复创建知识点，" +
                "而是复用并刷新这些知识点的遗忘曲线计时器。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImagePreview(path: String) {
    val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "扫描的习题图片",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    }
}
