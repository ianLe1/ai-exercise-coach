package com.example.aiexercise.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import com.example.aiexercise.ai.LlmPresets
import com.example.aiexercise.ui.AppScreen
import com.example.aiexercise.ui.AppViewModel
import com.example.aiexercise.ui.UiState
import com.example.aiexercise.ui.components.ScreenHeader
import com.example.aiexercise.ui.components.SectionHeader

/**
 * 设置页只保留两块：
 * 1. 大模型（离线规则 / OpenAI 兼容接口）；
 * 2. 用户信息（称呼、年级、目标）。
 * 视觉识别、联网搜索、教材知识库都走内置默认实现，不再暴露配置项。
 */
@Composable
fun SettingsScreen(state: UiState, viewModel: AppViewModel) {
    var mode by remember(state.aiMode) { mutableStateOf(state.aiMode) }
    var baseUrl by remember(state.settingsBaseUrl) { mutableStateOf(state.settingsBaseUrl) }
    var apiKey by remember(state.settingsApiKey) { mutableStateOf(state.settingsApiKey) }
    var model by remember(state.settingsModel) { mutableStateOf(state.settingsModel) }

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
            subtitle = "只保留大模型与用户信息",
            onBack = { viewModel.navigate(AppScreen.Home) },
        )

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("大模型")
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
                Text(
                    "快速预设（来自 Free-LLM-Collection，需自备 Key）",
                    style = MaterialTheme.typography.labelSmall,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LlmPresets.all.size) { index ->
                        val preset = LlmPresets.all[index]
                        AssistChip(
                            onClick = {
                                baseUrl = preset.baseUrl
                                model = preset.model
                                mode = AiMode.REMOTE
                            },
                            label = { Text(preset.name) },
                        )
                    }
                }
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
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("保存并测试")
                    }
                }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("用户信息")
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
                    Text("保存用户信息")
                }
            }
        }
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
