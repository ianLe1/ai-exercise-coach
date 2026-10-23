package com.example.aiexercise.data

import com.example.aiexercise.ai.AiJson
import com.example.aiexercise.data.model.AppData
import com.example.aiexercise.data.model.AskerProfile
import com.example.aiexercise.data.model.Exercise
import com.example.aiexercise.data.model.GradeResult
import com.example.aiexercise.data.store.AppJson
import com.example.aiexercise.data.store.InMemoryStore
import com.example.aiexercise.data.store.JsonFileStore
import com.example.aiexercise.sampleKnowledgePoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppDataStoreTest {

    @Test
    fun jsonFileStoreRoundTripsFullData() = runBlocking {
        val directory = Files.createTempDirectory("aiexercise-store").toFile()
        val file = File(directory, "data/app_data.json")
        val store = JsonFileStore(file)

        val data = AppData(
            asker = AskerProfile(name = "小明", gradeLevel = "高二", goal = "数学冲刺 120"),
            exercises = listOf(
                Exercise(
                    id = "ex-1",
                    questionText = "求 f(x)=x²-4x+3 的最小值。",
                    fingerprint = "abc123",
                    subject = "数学",
                    referenceAnswer = "-1",
                    studentAnswer = "-1",
                    createdAt = 1_700_000_000_000L,
                    lastSeenAt = 1_700_000_001_000L,
                    scanCount = 2,
                ),
            ),
            knowledgePoints = listOf(sampleKnowledgePoint(now = 1_700_000_000_000L)),
            grades = listOf(
                GradeResult(
                    id = "g-1",
                    exerciseId = "ex-1",
                    score = 100,
                    passed = true,
                    analysisMarkdown = "## 正确",
                    gradedAt = 1_700_000_002_000L,
                ),
            ),
        )

        store.save(data)

        assertTrue(file.exists())
        assertEquals(data, store.load())
    }

    @Test
    fun loadReturnsNullWhenFileMissing() = runBlocking {
        val directory = Files.createTempDirectory("aiexercise-empty").toFile()
        val store = JsonFileStore(File(directory, "data/app_data.json"))
        assertNull(store.load())
    }

    @Test
    fun appDataJsonIsParseableAndRoundTrips() {
        val data = AppData(
            asker = AskerProfile(name = "小红", gradeLevel = "初三", goal = ""),
            knowledgePoints = listOf(sampleKnowledgePoint(now = 0)),
        )
        val raw = AppJson.encode(data)
        assertTrue(raw.trimStart().startsWith("{"))
        assertEquals(data, AppJson.decode(raw))
        // 与 AiJson 的 JSON 配置互相兼容（同一份数据可被后续版本读取）
        assertEquals(data.exercises, AiJson.json.decodeFromString(AppData.serializer(), raw).exercises)
    }

    @Test
    fun repositoryUsesStoreAndReturnsFailuresAsOutcome() = runBlocking {
        val store = InMemoryStore()
        val repository = AppRepository(store)
        val data = AppData(asker = AskerProfile(name = "小明"))
        assertTrue(repository.save(data) is com.example.aiexercise.domain.Outcome.Success)
        assertEquals(data, (repository.load() as com.example.aiexercise.domain.Outcome.Success).value)
    }
}
