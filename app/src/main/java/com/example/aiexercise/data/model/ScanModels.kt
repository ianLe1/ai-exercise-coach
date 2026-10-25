package com.example.aiexercise.data.model

import kotlinx.serialization.Serializable

/**
 * 多页扫描中的一页。
 *
 * - imagePath：矫正并增强后的图片（文件缓存路径）；
 * - corrected：是否经文档扫描器/本地增强矫正（旋转、裁边、对比度）；
 * - ocrText：这一页的识别文本，分割题目时按页拼接。
 */
/**
 * OCR 一行文字的归一化位置（0..1，相对该页图片），用来在扫描件上框出题目区域。
 */
@Serializable
data class OcrLine(
    val text: String = "",
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
)

@Serializable
data class ScanPage(
    val id: String,
    val index: Int,
    val imagePath: String,
    val enhancedPath: String? = null,
    val ocrText: String = "",
    /** 视觉证据（Markdown 排版）：同一页重复分析时复用，避免重复调用视觉模型。 */
    val markdown: String = "",
    /** 逐行位置（归一化），用于在扫描件上框题。 */
    val lines: List<OcrLine> = emptyList(),
    val corrected: Boolean = false,
)

/**
 * 一次多页扫描会话：页 → OCR → 分割出的一道道题目（每道题是独立 Exercise）。
 * 题目可能跨页，因此 Exercise.pageIndexes 可能是多个页码。
 */
@Serializable
data class ScanPaper(
    val id: String,
    val createdAt: Long = 0L,
    val pages: List<ScanPage> = emptyList(),
    val exerciseIds: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)
