package com.example.aiexercise.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aiexercise.ui.screens.KnowledgeScreen
import com.example.aiexercise.ui.screens.PaperScreen
import com.example.aiexercise.ui.screens.ResultScreen
import com.example.aiexercise.ui.screens.ScanScreen
import com.example.aiexercise.ui.screens.SettingsScreen

/**
 * 应用根组件：底部 4 个 Tab（扫描 / 知识点 / 练习卷 / 设置），识别结果页为全屏。
 * 这里只是很薄的组装层，业务逻辑全部在 AppViewModel + ExerciseToolkit。
 */
@Composable
fun AiExerciseRoot(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message?.id) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message.text)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (state.screen != AppScreen.Result) {
                NavigationBar {
                    NavigationBarItem(
                        selected = state.screen == AppScreen.Scan,
                        onClick = { viewModel.navigate(AppScreen.Scan) },
                        icon = { Icon(Icons.Filled.Edit, contentDescription = "扫描") },
                        label = { Text("扫描") },
                    )
                    NavigationBarItem(
                        selected = state.screen == AppScreen.Knowledge,
                        onClick = { viewModel.navigate(AppScreen.Knowledge) },
                        icon = { Icon(Icons.Filled.Star, contentDescription = "知识点") },
                        label = { Text("知识点") },
                    )
                    NavigationBarItem(
                        selected = state.screen == AppScreen.Paper,
                        onClick = { viewModel.navigate(AppScreen.Paper) },
                        icon = { Icon(Icons.Filled.List, contentDescription = "练习卷") },
                        label = { Text("练习卷") },
                    )
                    NavigationBarItem(
                        selected = state.screen == AppScreen.Settings,
                        onClick = { viewModel.navigate(AppScreen.Settings) },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = "设置") },
                        label = { Text("设置") },
                    )
                }
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when (state.screen) {
                AppScreen.Scan -> ScanScreen(state, viewModel)
                AppScreen.Result -> ResultScreen(state, viewModel)
                AppScreen.Knowledge -> KnowledgeScreen(state, viewModel)
                AppScreen.Paper -> PaperScreen(state, viewModel)
                AppScreen.Settings -> SettingsScreen(state, viewModel)
            }
        }
    }
}
