package com.example.aiexercise.domain

import com.example.aiexercise.data.model.KbCitation
import com.example.aiexercise.ai.*
import com.example.aiexercise.data.model.ResearchBundle
import com.example.aiexercise.data.model.SolutionDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动资料检索流水线：网络搜索 → AI 过滤出本题解析 → 知识库检索。
 * 全部降级路径都必须“可读提示 + 不阻断知识点分类”。
 */
class ResearchAssistantTest {

    private val question = "已知函数 f(x)=x²-4x+3，求闭区间 [0,3] 上的最大值与最小值。"
    private val reference = "最小值 f(2)=-1，最大值 f(0)=3。"
    private val clock = 1_700_000_000_000L

    private class FakeAi(private val digest: SolutionDigest) : AiService {
        override val id: String = "fake"
        override val displayName: String = "假 AI"
        override val isOffline: Boolean = true

        override suspend fun analyze(request: AnalyzeRequest) =
            Outcome.Failure(AppError.Unexpected("测试未使用"))

        override suspend fun grade(request: GradeRequest) =
            Outcome.Failure(AppError.Unexpected("测试未使用"))

        override suspend fun generatePaper(request: PaperRequest) =
            Outcome.Failure(AppError.Unexpected("测试未使用"))

        override suspend fun distillSolution(request: SolutionFilterRequest) =
            Outcome.Success(digest)

        override suspend fun segmentQuestions(request: SegmentRequest) =
            Outcome.Failure(AppError.Unexpected("测试未使用"))

        override suspend fun readAnswers(request: AnswerReadRequest) =
            Outcome.Failure(AppError.Unexpected("测试未使用"))

        override suspend fun solve(request: SolveRequest): Outcome<SolvePayload> =
            Outcome.Success(SolvePayload(referenceAnswer = "参考要点：测试用参考答案"))

        override suspend fun guidedStep(request: GuidedRequest) =
            Outcome.Failure(AppError.Unexpected("测试未使用"))
    }

    private class FakeSearch(private val result: Outcome<List<SearchHit>>) : SearchService {
        override val displayName: String = "假搜索"
        var calls: Int = 0
            private set

        override suspend fun search(query: String, count: Int): Outcome<List<SearchHit>> {
            calls++
            return result
        }
    }

    private class FakeKnowledgeBase(
        override val documentCount: Int,
        private val citations: List<KbCitation>,
    ) : KnowledgeBase {
        override val name: String = "假知识库"
        override suspend fun search(query: KnowledgeQuery): List<KbCitation> = citations
    }

    private fun assistant(
        search: SearchService? = null,
        kb: KnowledgeBase? = EmptyKnowledgeBase,
        digest: SolutionDigest = SolutionDigest(),
    ) = ResearchAssistant(
        search = search,
        knowledgeBase = kb,
        ai = FakeAi(digest),
        clock = { clock },
    )

    @Test
    fun skipsWebAndKnowledgeBaseWhenNotConfigured() = runBlocking {
        val bundle = assistant(search = null).research(question, reference)

        assertEquals(ResearchBundle.WEB_SKIPPED, bundle.webState)
        assertEquals(ResearchBundle.KB_SKIPPED, bundle.kbState)
        assertFalse(bundle.digest.relevant)
        assertTrue(bundle.warning.contains("未启用联网搜索"))
        assertTrue(bundle.warning.contains("教材知识库暂未接入"))
        assertEquals(clock, bundle.searchedAt)
    }

    @Test
    fun filtersSearchHitsWithAiAndKeepsDigest() = runBlocking {
        val search = FakeSearch(
            Outcome.Success(
                listOf(
                    SearchHit("解析一", "https://example.com/1", "最小值 -1，最大值 3。"),
                    SearchHit("解析二", "https://example.com/2", "先配方再比较端点。"),
                ),
            ),
        )
        val digest = SolutionDigest(
            relevant = true,
            solutionMarkdown = "## 本题解析\n先配方再比较端点。",
            confidence = 0.82,
            matchedTitles = listOf("解析一"),
        )

        val bundle = assistant(search = search, digest = digest).research(question, reference)

        assertEquals(1, search.calls)
        assertEquals(ResearchBundle.WEB_OK, bundle.webState)
        assertEquals(2, bundle.webHits.size)
        assertEquals(digest, bundle.digest)
        assertTrue(bundle.query.contains("解析"))
        assertTrue(bundle.hasAnyEvidence)
    }

    @Test
    fun marksWebFailedWhenSearchFails() = runBlocking {
        val bundle = assistant(
            search = FakeSearch(Outcome.Failure(AppError.AiNetworkError("boom"))),
        ).research(question, reference)

        assertEquals(ResearchBundle.WEB_FAILED, bundle.webState)
        assertTrue(bundle.warning.contains("网络搜索失败"))
        assertFalse(bundle.digest.relevant)
    }

    @Test
    fun marksNoMatchWhenAiRejectsAllHits() = runBlocking {
        val bundle = assistant(
            search = FakeSearch(Outcome.Success(listOf(SearchHit("广告", "https://ad", "下载 App")))),
            digest = SolutionDigest(relevant = false),
        ).research(question, reference)

        assertEquals(ResearchBundle.WEB_NO_MATCH, bundle.webState)
        assertTrue(bundle.warning.contains("未从搜索结果中筛出"))
    }

    @Test
    fun attachesKnowledgeBaseCitationsWhenAvailable() = runBlocking {
        val citation = KbCitation(
            documentId = "doc-1",
            title = "人教版高中数学必修一",
            stage = "高中",
            subject = "数学",
            chapter = "第二章",
            page = "第 32 页",
            snippet = "闭区间上求最值需要比较顶点与端点。",
            score = 0.9,
        )
        val kb = FakeKnowledgeBase(documentCount = 120, citations = listOf(citation))

        val bundle = assistant(kb = kb).research(question, reference)

        assertEquals(ResearchBundle.KB_OK, bundle.kbState)
        assertEquals(1, bundle.kbCitations.size)
        assertEquals(120, bundle.kbDocuments)
        assertEquals("高中 · 数学 · 第二章 · 第 32 页", citation.citationLabel())
        assertTrue(bundle.hasAnyEvidence)
    }
}
