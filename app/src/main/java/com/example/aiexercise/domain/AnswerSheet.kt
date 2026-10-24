package com.example.aiexercise.domain

import com.example.aiexercise.data.model.PaperQuestion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 练习卷「答题卡结构」——只描述答题区，不含题干（题干另有 Markdown 字段）。
 *
 * 规范 JSON 形状（用户给定）：
 * ```json
 * {
 *   "第一大题": {
 *     "第1题": 5,
 *     "第2题": {
 *       "第2题第1小题": ["选项A", "选项B", "选项C"],
 *       "第2题第2小题": "填空题___"
 *     }
 *   }
 * }
 * ```
 * - 数字 = 答题区行数（写几行）；
 * - 数组 = 选择题选项；
 * - 字符串 = 填空题模板（下划线 ___ 为填空位置）；
 * - 必须有大题和题；小题可有可无（最多再嵌一层）。
 */
sealed interface AnswerSheetItem {
    /** 数字：答题区行数。 */
    data class Written(val rows: Int) : AnswerSheetItem

    /** 数组：选择题选项。 */
    data class Choice(val options: List<String>) : AnswerSheetItem

    /** 字符串：填空题模板，下划线是填空位置。 */
    data class FillIn(val template: String) : AnswerSheetItem

    /** 对象：小题（小题只能是数字/数组/字符串，不能再嵌套）。 */
    data class SubQuestions(val items: Map<String, AnswerSheetItem>) : AnswerSheetItem
}

data class AnswerSheet(val sections: Map<String, Map<String, AnswerSheetItem>>) {
    val questionCount: Int get() = sections.values.sumOf { it.size }
    val subQuestionCount: Int get() = sections.values.sumOf { questions ->
        questions.values.sumOf { (it as? AnswerSheetItem.SubQuestions)?.items?.size ?: 0 }
    }
}

object AnswerSheetCodec {

    const val MAX_WRITTEN_ROWS = 30
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 8

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    // ---------------------------------------------------------------- 编码

    fun encode(sheet: AnswerSheet): JsonObject = buildJsonObject {
        sheet.sections.forEach { (section, questions) ->
            put(
                section,
                buildJsonObject {
                    questions.forEach { (label, item) -> put(label, encodeItem(item)) }
                },
            )
        }
    }

    fun encodeToString(sheet: AnswerSheet): String =
        prettyJson.encodeToString(JsonObject.serializer(), encode(sheet))

    private fun encodeItem(item: AnswerSheetItem): JsonElement = when (item) {
        is AnswerSheetItem.Written -> JsonPrimitive(item.rows)
        is AnswerSheetItem.Choice -> JsonArray(item.options.map { JsonPrimitive(it) })
        is AnswerSheetItem.FillIn -> JsonPrimitive(item.template)
        is AnswerSheetItem.SubQuestions -> buildJsonObject {
            item.items.forEach { (label, sub) -> put(label, encodeItem(sub)) }
        }
    }

    // ---------------------------------------------------------------- 解码 + 校验

    /** 从 AI 返回的 JSON 文本解析答题卡结构；不合法时给出可直接展示的错误。 */
    fun parse(raw: String): Outcome<AnswerSheet> {
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
            ?: return invalid("答题卡结构不是合法 JSON")
        return decode(element)
    }

    fun decode(element: JsonElement): Outcome<AnswerSheet> {
        val root = element as? JsonObject
            ?: return invalid("答题卡结构必须是 JSON 对象")
        if (root.isEmpty()) return invalid("答题卡结构为空，至少要有一个大题")

        val sections = LinkedHashMap<String, Map<String, AnswerSheetItem>>()
        for ((sectionName, questionsElement) in root) {
            val sectionLabel = sectionName.trim()
            if (sectionLabel.isEmpty()) return invalid("大题名称不能为空")
            val questionsObject = questionsElement as? JsonObject
                ?: return invalid("「$sectionLabel」必须是一个对象（里面放题目）")
            if (questionsObject.isEmpty()) return invalid("「$sectionLabel」下至少要有 1 道题")

            val questions = LinkedHashMap<String, AnswerSheetItem>()
            for ((questionName, itemElement) in questionsObject) {
                val questionLabel = questionName.trim()
                if (questionLabel.isEmpty()) return invalid("「$sectionLabel」里有题目名称为空")
                when (val result = decodeItem(itemElement, path = "$sectionLabel › $questionLabel")) {
                    is ItemResult.Ok -> questions[questionLabel] = result.item
                    is ItemResult.Err -> return invalid(result.reason)
                }
            }
            sections[sectionLabel] = questions
        }
        return Outcome.Success(AnswerSheet(sections))
    }

    private sealed interface ItemResult {
        data class Ok(val item: AnswerSheetItem) : ItemResult
        data class Err(val reason: String) : ItemResult
    }

    private fun decodeItem(
        element: JsonElement,
        path: String,
        allowSubQuestions: Boolean = true,
    ): ItemResult {
        return when (element) {
            is JsonArray -> {
                val options = element.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
                    .filter { it.isNotEmpty() }
                when {
                    options.size < MIN_OPTIONS -> ItemResult.Err("「$path」是选择题，但选项少于 $MIN_OPTIONS 个")
                    options.size > MAX_OPTIONS -> ItemResult.Err("「$path」选项超过 $MAX_OPTIONS 个")
                    else -> ItemResult.Ok(AnswerSheetItem.Choice(options))
                }
            }

            is JsonObject -> {
                if (!allowSubQuestions) return ItemResult.Err("「$path」小题下不能再嵌套对象")
                if (element.isEmpty()) return ItemResult.Err("「$path」的小题列表为空")
                val subs = LinkedHashMap<String, AnswerSheetItem>()
                for ((subName, subElement) in element) {
                    val subLabel = subName.trim()
                    if (subLabel.isEmpty()) return ItemResult.Err("「$path」里有小题名称为空")
                    when (val sub = decodeItem(subElement, path = "$path › $subLabel", allowSubQuestions = false)) {
                        is ItemResult.Ok -> subs[subLabel] = sub.item
                        is ItemResult.Err -> return sub
                    }
                }
                ItemResult.Ok(AnswerSheetItem.SubQuestions(subs))
            }

            is JsonPrimitive -> when {
                element.isString -> {
                    val template = element.content.trim()
                    when {
                        template.isEmpty() -> ItemResult.Err("「$path」填空模板为空")
                        !template.contains("__") ->
                            ItemResult.Err("「$path」是填空题，模板里必须有下划线 ___ 表示填空位置")
                        else -> ItemResult.Ok(AnswerSheetItem.FillIn(template))
                    }
                }

                else -> {
                    val rows = element.content.toIntOrNull()
                    when {
                        rows == null -> ItemResult.Err("「$path」的答题行数必须是整数")
                        rows < 1 || rows > MAX_WRITTEN_ROWS ->
                            ItemResult.Err("「$path」的答题行数必须在 1–$MAX_WRITTEN_ROWS 之间")
                        else -> ItemResult.Ok(AnswerSheetItem.Written(rows))
                    }
                }
            }

            else -> ItemResult.Err("「$path」的答题区只能是数字 / 数组 / 字符串 / 小题对象")
        }
    }

    private fun invalid(reason: String): Outcome.Failure =
        Outcome.Failure(AppError.GenerationInvalid(reason))

    // ---------------------------------------------------------------- 从题目推导

    /**
     * AI 没给出合法答题卡结构时的确定性兜底：
     * 选择题 → 选项数组；填空/带下划线 → 填空模板；其余按题型给答题行数。
     * 大题按“选择题 / 填空题 / 解答题”分组。
     */
    fun derive(questions: List<PaperQuestion>): AnswerSheet {
        val choiceGroup = mutableListOf<AnswerSheetItem>()
        val fillGroup = mutableListOf<AnswerSheetItem>()
        val solutionGroup = mutableListOf<AnswerSheetItem>()
        questions.forEach { question ->
            when (val item = itemFor(question)) {
                is AnswerSheetItem.Choice -> choiceGroup += item
                is AnswerSheetItem.FillIn -> fillGroup += item
                else -> solutionGroup += item
            }
        }
        val sections = LinkedHashMap<String, Map<String, AnswerSheetItem>>()
        fun putGroup(section: String, items: List<AnswerSheetItem>) {
            if (items.isEmpty()) return
            val labeled = LinkedHashMap<String, AnswerSheetItem>()
            items.forEachIndexed { index, item -> labeled["第${index + 1}题"] = item }
            sections[section] = labeled
        }
        putGroup("第一大题（选择题）", choiceGroup)
        putGroup("第二大题（填空题）", fillGroup)
        putGroup("第三大题（解答题）", solutionGroup)
        return AnswerSheet(sections)
    }

    /** 单道题 → 答题区条目。 */
    fun itemFor(question: PaperQuestion): AnswerSheetItem {
        if (question.options.size >= MIN_OPTIONS) {
            return AnswerSheetItem.Choice(question.options.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_OPTIONS))
        }
        val looksFill = question.type.lowercase() in FILL_TYPES ||
            question.answerMarkdown.contains("___") ||
            question.questionMarkdown.contains("___")
        if (looksFill) {
            return AnswerSheetItem.FillIn("填空题 ___")
        }
        return AnswerSheetItem.Written(rowsFor(question))
    }

    private val FILL_TYPES = setOf("fill", "blank", "fill_blank", "fillblank")

    private fun rowsFor(question: PaperQuestion): Int = when (question.type.lowercase()) {
        "recall" -> 2
        "application" -> 5
        "choice" -> 1
        else -> 3
    }.coerceIn(1, MAX_WRITTEN_ROWS)
}
