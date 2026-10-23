package com.example.aiexercise.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.aiexercise.ai.AiMode
import com.example.aiexercise.ai.SearchMode
import com.example.aiexercise.ai.VisionMode
import com.example.aiexercise.ui.AppViewModel
import com.example.aiexercise.ui.UiState
import com.example.aiexercise.ui.components.ScreenHeader
import com.example.aiexercise.ui.components.SectionHeader

/**
 * 设置页：
 * - AI 引擎（离线规则 / OpenAI 兼容接口，可替换为任意厂商）；
 * - 提问者信息（AI 生成知识点与出卷时会参考年级、目标）；
 * - 本地数据管理。
 */
@Composable
fun SettingsScreen(state: UiState, viewModel: AppViewModel) {
    var mode by remember(state.aiMode) { mutableStateOf(state.aiMode) }
    var baseUrl by remember(state.settingsBaseUrl) { mutableStateOf(state.settingsBaseUrl) }
    var apiKey by remember(state.settingsApiKey) { mutableStateOf(state.settingsApiKey) }
    var model by remember(state.settingsModel) { mutableStateOf(state.settingsModel) }

    var visionMode by remember(state.visionMode) { mutableStateOf(state.visionMode) }
    var modlensUrl by remember(state.settingsModlensUrl) { mutableStateOf(state.settingsModlensUrl) }
    var visionFallback by remember(state.settingsVisionFallback) { mutableStateOf(state.settingsVisionFallback) }

    var searchMode by remember(state.searchMode) { mutableStateOf(state.searchMode) }
    var bingEndpoint by remember(state.settingsBingEndpoint) { mutableStateOf(state.settingsBingEndpoint) }
    var bingKey by remember(state.settingsBingKey) { mutableStateOf(state.settingsBingKey) }

    var askerName by remember(state.askerName) { mutableStateOf(state.askerName) }
    var askerGrade by remember(state.askerGrade) { mutableStateOf(state.askerGrade) }
    var askerGoal by remember(state.askerGoal) { mutableStateOf(state.askerGoal) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(
            title = "设置",
            subtitle = "AI 引擎可替换；数据默认保存在本机 JSON 文件",
        )

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("AI 引擎")
                Text(
                    "当前：${state.aiLabel}（${if (state.aiMode == AiMode.OFFLINE) "离线，无需网络" else "云端"}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ModeRow(
                    label = "离线规则引擎（默认，无需 Key）",
                    selected = mode == AiMode.OFFLINE,
                    onSelect = { mode = AiMode.OFFLINE },
                )
                ModeRow(
                    label = "OpenAI 兼容接口（OpenAI / DeepSeek / 通义 / Ollama 等）",
                    selected = mode == AiMode.REMOTE,
                    onSelect = { mode = AiMode.REMOTE },
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL（如 https://api.openai.com/v1）") },
                    singleLine = true,
                    enabled = mode == AiMode.REMOTE,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型名（如 gpt-4o-mini / deepseek-chat）") },
                    singleLine = true,
                    enabled = mode == AiMode.REMOTE,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key（仅保存在本机，不会写入数据文件）") },
                    singleLine = true,
                    enabled = mode == AiMode.REMOTE,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { viewModel.saveSettings(mode, baseUrl, apiKey, model) },
                        modifier = Modifier.weight(1f),
                    ) { Text("保存设置") }
                    OutlinedButton(
                        onClick = {
                            viewModel.saveSettings(mode, baseUrl, apiKey, model)
                            viewModel.testAi()
                        },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (state.busy) {
                            CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("保存并测试")
                    }
                }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("视觉识别（扫描）")
                Text(
                    "当前：${state.visionLabel}。图片只在本机（或本机运行的桥接服务）处理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ModeRow(
                    label = "ML Kit 中文 OCR（离线，默认）",
                    selected = visionMode == VisionMode.MLKIT,
                    onSelect = { visionMode = VisionMode.MLKIT },
                )
                ModeRow(
                    label = "modlens 视觉桥（多模态模型读题，需先运行 tools/modlens-bridge）",
                    selected = visionMode == VisionMode.MODLENS,
                    onSelect = { visionMode = VisionMode.MODLENS },
                )
                OutlinedTextField(
                    value = modlensUrl,
                    onValueChange = { modlensUrl = it },
                    label = { Text("桥接地址（模拟器：adb reverse tcp:8765 tcp:8765 后填 http://127.0.0.1:8765；或 http://10.0.2.2:8765）") },
                    singleLine = true,
                    enabled = visionMode == VisionMode.MODLENS,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = visionFallback, onCheckedChange = { visionFallback = it })
                    Text("modlens 失败时自动回退 ML Kit", style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    onClick = { viewModel.saveVisionSettings(visionMode, modlensUrl, visionFallback) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存视觉设置") }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("联网搜索（必应）")
                Text(
                    "用于在识别结果页搜索「本题相关资料 / 同类题」。需要 Azure 必应搜索 API Key；关闭时该入口隐藏。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ModeRow(
                    label = "关闭",
                    selected = searchMode == SearchMode.OFF,
                    onSelect = { searchMode = SearchMode.OFF },
                )
                ModeRow(
                    label = "必应 Web Search API v7",
                    selected = searchMode == SearchMode.BING,
                    onSelect = { searchMode = SearchMode.BING },
                )
                OutlinedTextField(
                    value = bingEndpoint,
                    onValueChange = { bingEndpoint = it },
                    label = { Text("端点（默认 https://api.bing.microsoft.com/v7.0/search）") },
                    singleLine = true,
                    enabled = searchMode == SearchMode.BING,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bingKey,
                    onValueChange = { bingKey = it },
                    label = { Text("Ocp-Apim-Subscription-Key（仅存本机）") },
                    singleLine = true,
                    enabled = searchMode == SearchMode.BING,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { viewModel.saveSearchSettings(searchMode, bingEndpoint, bingKey) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存搜索设置") }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("提问者信息")
                Text(
                    "AI 生成知识点与练习卷时会参考这些信息（年级、学习目标）调整难度与措辞。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = askerName,
                    onValueChange = { askerName = it },
                    label = { Text("称呼") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = askerGrade,
                    onValueChange = { askerGrade = it },
                    label = { Text("年级 / 水平（如：高二）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = askerGoal,
                    onValueChange = { askerGoal = it },
                    label = { Text("学习目标（如：期末数学冲刺 120 分）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { viewModel.saveAsker(askerName, askerGrade, askerGoal) }) {
                    Text("保存提问者信息")
                }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("本地数据")
                Text(
                    "习题 ${state.data.exercises.size} 道 · 知识点 ${state.data.knowledgePoints.size} 条 · " +
                        "批改记录 ${state.data.grades.size} 条 · 练习卷 ${state.data.papers.size} 份",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "存储位置：应用私有目录 data/app_data.json（原子写入）。数据模型与存储实现均可替换。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = viewModel::requestClearData) {
                    Text("清空所有本地数据")
                }
            }
        }

        Text(
            "可替换点：① 视觉引擎（platform/VisionEngine.kt：ML Kit 离线 / ModlensVisionEngine 桥接，可再加云端 OCR）；" +
                "② AI 服务（ai/AiService.kt + AiServiceFactory，当前离线规则 + OpenAI 兼容接口）；" +
                "③ 联网搜索（ai/BingSearchService.kt 的 SearchService 接口，可换 Google/Brave/SearXNG）；" +
                "④ 存储（data/store/AppDataStore.kt，当前单文件 JSON）；" +
                "⑤ 遗忘曲线参数（domain/ForgettingCurve.kt 的 ForgettingCurveConfig）；" +
                "⑥ 判重策略（domain/ToolConfig.kt 的 similarityThreshold 与 ExerciseIdentity 归一化规则）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.showClearDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClearDialog,
            title = { Text("清空所有本地数据？") },
            text = { Text("将删除全部习题、知识点、计时器、批改记录与练习卷，且不可恢复。") },
            confirmButton = {
                TextButton(onClick = viewModel::clearAllData) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClearDialog) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ModeRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
