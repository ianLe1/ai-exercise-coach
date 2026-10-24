package com.example.aiexercise.ui.screens

import android.app.Activity
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
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
import com.example.aiexercise.ui.AppScreen
import com.example.aiexercise.ui.AppViewModel
import com.example.aiexercise.ui.UiState
import com.example.aiexercise.ui.components.ScreenHeader
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
    val activity = LocalContext.current as? Activity

    // 无 Google 文档扫描器时：相机拍一页 → 本地增强 → 追加为扫描页（可反复拍）
    var pageCameraFile by remember { mutableStateOf<File?>(null) }
    val pageCameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        pageCameraFile?.let { file ->
            if (success) {
                viewModel.onGalleryPickedMultiple(listOf(android.net.Uri.fromFile(file)))
                viewModel.notify("已拍照加入扫描页（可继续拍下一页）", false)
            }
        }
    }
    val galleryMultiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_SCAN_PAGES),
    ) { uris -> viewModel.onGalleryPickedMultiple(uris) }
    val documentScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onDocumentScanResult(result.data)
        } else {
            viewModel.notify("已取消文档扫描", false)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(
            title = "扫描习题",
            subtitle = "拍照 / 相册 / 多页试卷；参考答案由 AI 自动生成，你只需在纸上作答",
            onBack = { viewModel.navigate(AppScreen.Home) },
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag("当前 AI：${state.aiLabel}")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag("视觉：${state.visionLabel}")
                Spacer(Modifier.width(8.dp))
                Text(
                    "排版结果以 Markdown 保存",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("① 扫描习题（多页 · 自动矫正 · 分割题目）")
                Text(
                    "拍下整页试卷：有 Google 服务时用文档扫描器自动透视矫正与增强；没有时用「拍照扫描」逐页拍，" +
                        "也可以从相册多选。识别后会把题目一道一道切出来，跨页题自动合并。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (state.documentScanAvailable && activity != null) {
                                viewModel.startDocumentScan(activity) { intentSender ->
                                    documentScanLauncher.launch(
                                        IntentSenderRequest.Builder(intentSender).build(),
                                    )
                                }
                            } else {
                                // 华为等无 Google Play 服务的设备：内置拍照扫描（一页一张，可连拍）+ 本地增强
                                val (file, uri) = viewModel.createCameraOutput()
                                pageCameraFile = file
                                pageCameraLauncher.launch(uri)
                                viewModel.notify(
                                    "此设备没有 Google 文档扫描器，已启用内置拍照扫描：拍一页加一页，可连续拍。",
                                    false,
                                )
                            }
                        },
                        enabled = !state.segmentsBusy && !state.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (state.documentScanAvailable) "文档扫描" else "拍照扫描") }
                    OutlinedButton(
                        onClick = {
                            galleryMultiLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        enabled = !state.segmentsBusy && !state.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("相册多选") }
                }
                if (state.scanPages.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.scanPages, key = { it.id }) { page ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.width(96.dp),
                            ) {
                                ImagePreview(page.imagePath, height = 120)
                                Text(
                                    "第 ${page.index} 页",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = { viewModel.removeScanPage(page.id) },
                                    enabled = !state.segmentsBusy,
                                ) { Text("删除", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = viewModel::ocrAndSegment,
                            enabled = !state.segmentsBusy && !state.busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (state.segmentsBusy) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("处理中…")
                            } else {
                                Text("识别并分割题目（${state.scanPages.size} 页）")
                            }
                        }
                        if (state.segments.isNotEmpty()) {
                            TextButton(onClick = viewModel::openSegments) {
                                Text("上次分割 ${state.segments.size} 题")
                            }
                        }
                    }
                    if (state.segmentsBusy && state.segmentStage.isNotBlank()) {
                        Text(
                            "⏳ ${state.segmentStage}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    TextButton(onClick = viewModel::clearScanPages, enabled = !state.segmentsBusy) {
                        Text("清空扫描页")
                    }
                } else {
                    Text(
                        "还没有扫描页：点「文档扫描」连续拍多页，或「相册多选」导入多张图。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(
            "识别后会把题目一道一道切出来并自动分析知识点；纸面上的作答也会被识别用于批改。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("想再练一张整卷？")
                Text(
                    "按遗忘曲线生成整张试卷 → 打印或在纸上作答 → 回首页「扫描习题」直接批改（不另设批改入口）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { viewModel.navigate(AppScreen.Paper) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("查看 / 生成整张试卷") }
            }
        }

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
private fun ImagePreview(path: String, height: Int = 150) {
    val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "扫描的习题图片",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(height.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    }
}

/** 单次最多支持多少扫描页（文档扫描器与相册多选共用）。 */
private const val MAX_SCAN_PAGES = 20
