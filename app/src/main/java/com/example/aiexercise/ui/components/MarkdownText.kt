package com.example.aiexercise.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 极简 Markdown 渲染：标题（#/##/###）、粗体（**text**）、列表（- ）、空行分段。
 * 不引入第三方库；公式（$...$）按原文显示，避免把试卷排版符号直接暴露给学生。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val blocks = markdown.trim().lines()
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("### ") -> {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        line.removePrefix("### ").trim(),
                        style = style.copy(fontWeight = FontWeight.SemiBold, fontSize = style.fontSize * 1.05f),
                    )
                }
                line.startsWith("## ") -> {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        line.removePrefix("## ").trim(),
                        style = style.copy(fontWeight = FontWeight.Bold, fontSize = style.fontSize * 1.15f),
                    )
                }
                line.startsWith("# ") -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        line.removePrefix("# ").trim(),
                        style = style.copy(fontWeight = FontWeight.Bold, fontSize = style.fontSize * 1.3f),
                    )
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    Text("• ${line.drop(2).trim()}", style = style, modifier = Modifier.padding(start = 8.dp))
                }
                line.isBlank() -> Spacer(Modifier.height(8.dp))
                else -> Text(inlineMarkdown(line), style = style)
            }
        }
    }
}

/** 行内 **粗体**、`代码` 与 *斜体*（简化处理）。 */
private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("**", index) -> {
                val end = text.indexOf("**", index + 2)
                if (end == -1) {
                    append(text.substring(index))
                    index = text.length
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(index + 2, end))
                    }
                    index = end + 2
                }
            }
            text[index] == '`' -> {
                val end = text.indexOf('`', index + 1)
                if (end == -1) {
                    append(text.substring(index))
                    index = text.length
                } else {
                    withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                }
            }
            else -> {
                append(text[index])
                index++
            }
        }
    }
}
