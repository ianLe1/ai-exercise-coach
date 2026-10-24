package com.example.aiexercise.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aiexercise.ui.screens.HomeScreen
import com.example.aiexercise.ui.screens.InAppScanScreen
import com.example.aiexercise.ui.screens.PaperScreen
import com.example.aiexercise.ui.screens.PracticeReviewScreen
import com.example.aiexercise.ui.screens.ResultScreen
import com.example.aiexercise.ui.screens.SegmentsScreen
import com.example.aiexercise.ui.screens.ScanScreen
import com.example.aiexercise.ui.screens.SettingsScreen

/**
 * 应用根组件：首页只保留「按复习时间排列的知识点列表 + 扫描按钮」，
 * 练习卷、批改、配置都在「设置」里；结果/分割/批改页为全屏。
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

    state.pendingGuard?.let { guard ->
        AlertDialog(
            onDismissRequest = { },
            title = {
                Text(if (guard.firstTime) "首次一起批改，先确认一下" else "这次作业和知识点列表对不上")
            },
            text = { Text(guard.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveGuard(true) }) { Text("是我认真做的") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveGuard(false) }) { Text("没用心，忽略这次扫描") }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {},
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when (state.screen) {
                AppScreen.Home -> HomeScreen(state, viewModel)
                AppScreen.Scan -> ScanScreen(state, viewModel)
                AppScreen.InAppScan -> InAppScanScreen(
                    onCancel = { viewModel.navigate(AppScreen.Scan) },
                    onDone = viewModel::onInAppScanCompleted,
                )
                AppScreen.Result -> ResultScreen(state, viewModel)
                AppScreen.Segments -> SegmentsScreen(state, viewModel)
                AppScreen.Paper -> PaperScreen(state, viewModel)
                AppScreen.PracticeReview -> PracticeReviewScreen(state, viewModel)
                AppScreen.Settings -> SettingsScreen(state, viewModel)
            }
        }
    }
}
