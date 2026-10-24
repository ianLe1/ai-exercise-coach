package com.example.aiexercise.data.model

import kotlinx.serialization.Serializable

/**
 * 提问者画像：AI 生成“不会的知识点”时需要知道是谁在学、学到什么阶段。
 * 目前保存在 AppData 中，可在「设置」页修改；将来可替换为账号体系 / 学情画像服务。
 */
@Serializable
data class AskerProfile(
    val name: String = "我",
    val gradeLevel: String = "高二",
    val goal: String = "",
)

@Serializable
enum class ExerciseSource {
    OCR_CAMERA,
    OCR_GALLERY,
    MANUAL,
}

/**
 * 一道被扫描过的习题。
 *
 * - fingerprint：规范化文本的 SHA-256，用于“同一习题”精确判定；
 * - referenceAnswer：该习题的答案（来自答案页 OCR 或用户补录）；
 * - studentAnswer：提问者的作答，批改对象；
 * - scanCount / refreshCount：同一习题被重复扫描的次数（特判计数）。
 */
@Serializable
data class Exercise(
    val id: String,
    val questionText: String,
    val fingerprint: String = "",
    val subject: String = "综合",
    val referenceAnswer: String = "",
    val studentAnswer: String = "",
    val source: ExerciseSource = ExerciseSource.MANUAL,
    val imagePath: String? = null,
    val createdAt: Long = 0L,
    val lastSeenAt: Long = 0L,
    val scanCount: Int = 1,
    val refreshCount: Int = 0,
    /** 最近一次“自动检索题目解析 + 教材知识库”的结果（随数据持久化）。 */
    val lastResearch: ResearchBundle? = null,
    /** 多页扫描时所属大题（如“第一大题（选择题）”）。 */
    val sectionTitle: String = "",
    /** 该题出现在哪些页（从 1 开始）；跨页题会有多个页码。 */
    val pageIndexes: List<Int> = emptyList(),
    /** 题干是否跨页（分割时由页码差与续接判断得出）。 */
    val crossPage: Boolean = false,
    /** 同一份多页扫描产生的题目共享的扫描批次 id；手动输入为空。 */
    val scanPaperId: String = "",
)

/**
 * 遗忘曲线计时器（简化 SM-2）。
 *
 * intervalDays / ease 决定下次复习时间；reviewCount、lapseCount 是历史轨迹。
 */
@Serializable
data class ReviewTimer(
    val nextReviewAt: Long,
    val intervalDays: Double = 1.0,
    val ease: Double = 2.5,
    val reviewCount: Int = 0,
    val lastReviewedAt: Long? = null,
    val lastQuality: Int? = null,
    val lapseCount: Int = 0,
)

/**
 * “不会的知识点”条目。
 *
 * 去重键 = 规范化(title) + '|' + 规范化(aspect)；
 * sourceExerciseIds 记录它与哪些习题相关（批改后据此列出相关知识点的依据）。
 */
@Serializable
data class KnowledgePoint(
    val id: String,
    val title: String,
    val aspect: String,
    val subject: String = "综合",
    val detail: String = "",
    val evidence: String = "",
    val sourceExerciseIds: List<String> = emptyList(),
    val mastery: Double = 0.2,
    val timer: ReviewTimer,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val refreshCount: Int = 0,
    val archived: Boolean = false,
)

@Serializable
data class GradeResult(
    val id: String,
    val exerciseId: String,
    val score: Int,
    val passed: Boolean,
    val correctAnswer: String = "",
    val studentAnswer: String = "",
    val analysisMarkdown: String = "",
    val errorSteps: List<String> = emptyList(),
    val relatedKnowledgePointTitles: List<String> = emptyList(),
    val gradedAt: Long = 0L,
)

@Serializable
data class PaperKnowledgePointRef(
    val id: String,
    val title: String,
    val aspect: String = "",
    val urgency: Double = 0.0,
    val nextReviewAt: Long = 0L,
)

/**
 * 结构化练习卷。questions[].questionMarkdown 即题面（Markdown）。
 */
@Serializable
data class PaperQuestion(
    val id: String,
    val type: String = "solution",
    val difficulty: String = "medium",
    val questionMarkdown: String,
    val options: List<String> = emptyList(),
    val answerMarkdown: String,
    val explanationMarkdown: String = "",
    val knowledgePointId: String = "",
    val knowledgePointTitle: String = "",
)

@Serializable
data class PracticePaper(
    val schema: String = "ai-exercise/practice-paper@1",
    val id: String,
    val aspect: String,
    val title: String,
    val summary: String = "",
    val generatedAt: Long = 0L,
    val generatedAtIso: String = "",
    val requestedCount: Int = 0,
    val knowledgePoints: List<PaperKnowledgePointRef> = emptyList(),
    val questions: List<PaperQuestion> = emptyList(),
    /**
     * 答题卡结构 JSON（规范格式，只描述答题区）：
     * `{"第一大题":{"第1题":5,"第2题":{"第2题第1小题":["A","B","C"],"第2题第2小题":"填空题___"}}}`
     * 数字 = 答题区行数，数组 = 选择题选项，字符串 = 填空（下划线为填空位置）。
     */
    val answerSheetJson: String = "{}",
    /** 答题卡结构是否由题目自动推导（AI 未给出合法结构时降级）。 */
    val answerSheetDerived: Boolean = false,
)

/**
 * 全量持久化数据（单文件 JSON；schemaVersion 用于将来迁移）。
 */
@Serializable
data class AppData(
    val schemaVersion: Int = 1,
    val asker: AskerProfile = AskerProfile(),
    val exercises: List<Exercise> = emptyList(),
    val knowledgePoints: List<KnowledgePoint> = emptyList(),
    val grades: List<GradeResult> = emptyList(),
    val papers: List<PracticePaper> = emptyList(),
    /** 多页扫描批次（页图、OCR 文本、分割出的习题 id）。 */
    val scanPapers: List<ScanPaper> = emptyList(),
    /** 练习卷答题卡扫描批改（多轮订正累积）。 */
    val practiceReviews: List<PracticeReview> = emptyList(),
    /** 引导式提问会话（只保留未解决 + 最近已解决）。 */
    val guidedSessions: List<GuidedSession> = emptyList(),
    /** 是否已经问过「这次作业你用心做了吗」——首次使用必问。 */
    val homeworkAskedOnce: Boolean = false,
    /** 用户主动忽略（没用心做）的扫描次数。 */
    val homeworkIgnoredCount: Int = 0,
)
