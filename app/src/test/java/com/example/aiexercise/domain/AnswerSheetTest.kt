package com.example.aiexercise.domain

import com.example.aiexercise.ai.PaperPayload
import com.example.aiexercise.data.model.PaperQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 练习卷答题卡 JSON 规范：数字=答题行数、数组=选择题、字符串=填空、对象=小题。 */
class AnswerSheetTest {

    private fun question(
        id: String = "q1",
        type: String = "choice",
        questionMarkdown: String = "题干",
        options: List<String> = emptyList(),
        answerMarkdown: String = "答案",
    ) = PaperQuestion(
        id = id,
        type = type,
        difficulty = "easy",
        questionMarkdown = questionMarkdown,
        options = options,
        answerMarkdown = answerMarkdown,
    )

    @Test
    fun encodeUsesNumberArrayStringAndSubQuestionShape() {
        val sheet = AnswerSheet(
            sections = linkedMapOf(
                "第一大题" to linkedMapOf(
                    "第1题" to AnswerSheetItem.Written(5),
                    "第2题" to AnswerSheetItem.SubQuestions(
                        linkedMapOf(
                            "第2题第1小题" to AnswerSheetItem.Choice(listOf("选项A", "选项B", "选项C")),
                            "第2题第2小题" to AnswerSheetItem.FillIn("填空题___"),
                        ),
                    ),
                ),
            ),
        )
        val json = AnswerSheetCodec.encodeToString(sheet)
        assertTrue("必须是合法 JSON 对象", json.trimStart().startsWith("{"))
        assertTrue(json.contains("\"第1题\": 5"))
        assertTrue(json.contains("\"选项A\""))
        assertTrue(json.contains("填空题___"))

        val decoded = when (val result = AnswerSheetCodec.parse(json)) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> error("应能解析：${result.error.userMessage}")
        }
        assertEquals(2, decoded.questionCount)
        assertEquals(2, decoded.subQuestionCount)
        assertEquals(
            AnswerSheetItem.Written(5),
            decoded.sections["第一大题"]?.get("第1题"),
        )
    }

    @Test
    fun parseAcceptsUserSpecExample() {
        val raw = """
            {
              "第一大题": {
                "第1题": 5,
                "第2题": {
                  "第2题第1小题": ["选项A", "选项B", "选项C"],
                  "第2题第2小题": "填空题___"
                }
              }
            }
        """.trimIndent()
        val sheet = (AnswerSheetCodec.parse(raw) as? Outcome.Success)?.value
            ?: error("用户规范的示例必须能解析")
        val second = sheet.sections["第一大题"]?.get("第2题")
        assertTrue(second is AnswerSheetItem.SubQuestions)
        assertEquals(2, (second as AnswerSheetItem.SubQuestions).items.size)
    }

    @Test
    fun parseRejectsIllegalShapesWithReadableErrors() {
        val cases = mapOf(
            "[]" to "必须是 JSON 对象",
            "{}" to "至少要有一个大题",
            """{"第一大题":[]}""" to "必须是一个对象",
            """{"第一大题":{}}""" to "至少要有 1 道题",
            """{"第一大题":{"第1题":99}}""" to "答题行数必须在 1",
            """{"第一大题":{"第1题":["只有一个选项"]}}""" to "选项少于",
            """{"第一大题":{"第1题":"没有下划线的填空"}}""" to "必须有下划线",
            """{"第一大题":{"第1题":{"小题":{"嵌套":3}}}}""" to "不能再嵌套",
        )
        cases.forEach { (raw, expected) ->
            val result = AnswerSheetCodec.parse(raw)
            assertTrue("$raw 应当被拒绝", result is Outcome.Failure)
            val message = (result as Outcome.Failure).error.userMessage
            assertTrue("$raw 的错误提示应含「$expected」，实际：$message", message.contains(expected))
        }
    }

    @Test
    fun deriveGroupsChoiceFillAndSolutionSections() {
        val questions = listOf(
            question(id = "a", type = "choice", options = listOf("A. 甲", "B. 乙", "C. 丙")),
            question(id = "b", type = "fill", questionMarkdown = "填空题 ___"),
            question(id = "c", type = "application"),
        )
        val sheet = AnswerSheetCodec.derive(questions)
        assertEquals(listOf("第一大题（选择题）", "第二大题（填空题）", "第三大题（解答题）"), sheet.sections.keys.toList())
        assertTrue(sheet.sections["第一大题（选择题）"]?.get("第1题") is AnswerSheetItem.Choice)
        assertTrue(sheet.sections["第二大题（填空题）"]?.get("第1题") is AnswerSheetItem.FillIn)
        assertEquals(5, (sheet.sections["第三大题（解答题）"]?.get("第1题") as AnswerSheetItem.Written).rows)
    }

    @Test
    fun itemForFallsBackToWrittenRowsByQuestionType() {
        assertEquals(2, (AnswerSheetCodec.itemFor(question(type = "recall")) as AnswerSheetItem.Written).rows)
        assertEquals(3, (AnswerSheetCodec.itemFor(question(type = "solution")) as AnswerSheetItem.Written).rows)
        assertTrue(AnswerSheetCodec.itemFor(question(type = "fill")) is AnswerSheetItem.FillIn)
        assertTrue(
            AnswerSheetCodec.itemFor(
                question(type = "solution", answerMarkdown = "x = ___"),
            ) is AnswerSheetItem.FillIn,
        )
    }

    @Test
    fun paperPayloadKeepsAnswerSheetJsonElement() {
        // AI 返回的答题卡结构可以随 PaperPayload 一起回来（JsonElement 透传）。
        val payload = PaperPayload(
            title = "t",
            aspect = "二次函数",
            summary = "",
            questions = emptyList(),
            answerSheet = kotlinx.serialization.json.Json.parseToJsonElement(
                """{"第一大题":{"第1题":3}}""",
            ),
        )
        val element = payload.answerSheet ?: error("答题卡结构丢失")
        val sheet = (AnswerSheetCodec.decode(element) as? Outcome.Success)?.value ?: error("应能解析")
        assertEquals(AnswerSheetItem.Written(3), sheet.sections["第一大题"]?.get("第1题"))
    }
}
