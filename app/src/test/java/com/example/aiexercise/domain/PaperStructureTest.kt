package com.example.aiexercise.domain

import com.example.aiexercise.data.model.PaperQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperStructureTest {

    private fun question(
        id: String,
        section: String = "",
        score: Int = 0,
        options: List<String> = emptyList(),
        type: String = "solution",
        answer: String = "答案",
    ) = PaperQuestion(
        id = id,
        section = section,
        score = score,
        type = type,
        options = options,
        questionMarkdown = "题面 $id",
        answerMarkdown = answer,
    )

    @Test
    fun normalizesSectionsAndDistributesScoresToHundred() {
        val input = listOf(
            question("Q1", type = "choice", options = listOf("A", "B", "C", "D")),
            question("Q2", type = "choice", options = listOf("A", "B", "C", "D")),
            question("Q3", type = "recall", answer = "填空 ___"),
            question("Q4", type = "application"),
        )
        val enforced = PaperStructure.enforce(input)
        assertNotNull(enforced)
        enforced!!
        assertEquals(PaperStructure.CHOICE, enforced[0].section)
        assertEquals(PaperStructure.FILL, enforced[2].section)
        assertEquals(PaperStructure.SOLUTION, enforced[3].section)
        assertEquals(100, enforced.sumOf { it.score })
        assertTrue(enforced.all { it.score > 0 })
    }

    @Test
    fun keepsValidScoresWhenTheyAddUpToHundred() {
        val input = listOf(
            question("Q1", section = "一、选择题", score = 40, options = listOf("A", "B")),
            question("Q2", section = "二、填空题", score = 30, type = "fill"),
            question("Q3", section = "三、解答题", score = 30),
        )
        val enforced = PaperStructure.enforce(input)!!
        assertEquals(listOf(40, 30, 30), enforced.map { it.score })
    }

    @Test
    fun rejectsChoiceWithoutOptions() {
        val input = listOf(
            question("Q1", section = "一、选择题", score = 40),
            question("Q2", section = "二、填空题", score = 30, type = "fill"),
            question("Q3", section = "三、解答题", score = 30),
        )
        assertNull(PaperStructure.enforce(input))
    }

    @Test
    fun groupsBySectionInExamOrder() {
        val input = listOf(
            question("Q1", section = PaperStructure.SOLUTION, score = 50),
            question("Q2", section = PaperStructure.CHOICE, score = 50, options = listOf("A", "B")),
        )
        val groups = PaperStructure.groupBySection(input)
        assertEquals(listOf(PaperStructure.CHOICE, PaperStructure.SOLUTION), groups.map { it.first })
    }

    @Test
    fun emptyInputIsRejected() {
        assertNull(PaperStructure.enforce(emptyList()))
    }
}
