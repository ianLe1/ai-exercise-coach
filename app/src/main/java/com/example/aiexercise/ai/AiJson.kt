package com.example.aiexercise.ai

import com.example.aiexercise.data.model.SolutionDigest
import kotlinx.serialization.json.Json

/**
 * AI JSON 解析工具（可替换点：若换用 function calling / 各家 SDK，
 * 只需替换本文件与 Remote 实现）。
 *
 * LLM 输出常见的三种脏格式都能处理：
 * 1. 纯 JSON；
 * 2. ```json ... ``` 代码块；
 * 3. 前后夹带解释文字。
 */
object AiJson {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = true
    }

    fun extractJsonObject(raw: String): String? {
        val fenced = Regex("```(?:json|JSON)?\\s*([\\s\\S]*?)```").find(raw)?.groupValues?.getOrNull(1)
        val candidate = fenced ?: raw
        val start = candidate.indexOf('{')
        val end = candidate.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return candidate.substring(start, end + 1)
    }

    fun parseKnowledgePoints(raw: String): KpGenerationPayload? = runCatching {
        val objectText = extractJsonObject(raw) ?: return null
        json.decodeFromString(KpGenerationPayload.serializer(), objectText)
    }.getOrNull()

    fun parseGrade(raw: String): GradePayload? = runCatching {
        val objectText = extractJsonObject(raw) ?: return null
        json.decodeFromString(GradePayload.serializer(), objectText)
    }.getOrNull()

    fun parseSolutionDigest(raw: String): SolutionDigest? = runCatching {
        val objectText = extractJsonObject(raw) ?: return null
        json.decodeFromString(SolutionDigest.serializer(), objectText)
    }.getOrNull()

    fun parsePaper(raw: String): PaperPayload? = runCatching {
        val objectText = extractJsonObject(raw) ?: return null
        json.decodeFromString(PaperPayload.serializer(), objectText)
    }.getOrNull()

    fun parseSegments(raw: String): SegmentPayload? = runCatching {
        val objectText = extractJsonObject(raw) ?: return null
        json.decodeFromString(SegmentPayload.serializer(), objectText)
    }.getOrNull()

    fun encodePaper(paper: com.example.aiexercise.data.model.PracticePaper): String =
        json.encodeToString(com.example.aiexercise.data.model.PracticePaper.serializer(), paper)

    fun decodePaper(raw: String): com.example.aiexercise.data.model.PracticePaper =
        json.decodeFromString(com.example.aiexercise.data.model.PracticePaper.serializer(), raw)

    fun parseAnswers(raw: String): AnswerReadPayload? = runCatching {
        val objectText = extractJsonObject(raw) ?: return null
        json.decodeFromString(AnswerReadPayload.serializer(), objectText)
    }.getOrNull()

    fun parseGuided(raw: String): GuidedPayload? = runCatching {
        val objectText = extractJsonObject(raw) ?: return null
        json.decodeFromString(GuidedPayload.serializer(), objectText)
    }.getOrNull()
}
