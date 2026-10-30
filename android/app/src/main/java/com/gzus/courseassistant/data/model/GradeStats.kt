package com.gzus.courseassistant.data.model

/**
 * 单个学期的成绩统计。
 *
 * @param gpa 该学期按学分加权的绩点；没有任何可计算绩点的课程时为 null。
 * @param weightedScore 该学期按学分加权的平均分；没有任何可计算分数的课程时为 null。
 */
data class TermStat(
    val term: Term,
    val courseCount: Int,
    val credits: Double,
    val gpa: Double?,
    val weightedScore: Double?,
)

/**
 * 成绩分布（按百分制分数段）。等级制等非数字成绩计入 [unknown]。
 */
data class GradeDistribution(
    val excellent: Int = 0,
    val good: Int = 0,
    val medium: Int = 0,
    val pass: Int = 0,
    val fail: Int = 0,
    val unknown: Int = 0,
) {
    val total: Int
        get() = excellent + good + medium + pass + fail + unknown
}

/**
 * 按课程性质（必修/选修等）聚合的统计。[courseType] 为空表示教务未给出类型。
 */
data class TypeStat(
    val courseType: String,
    val courseCount: Int,
    val credits: Double,
    val gpa: Double?,
)

/**
 * 全部学期累计的成绩统计，用于「分析」页展示。
 */
data class GradeStats(
    val totalCourses: Int = 0,
    val totalCredits: Double = 0.0,
    val cumulativeGpa: Double? = null,
    val weightedScore: Double? = null,
    val failedCourses: Int = 0,
    val terms: List<TermStat> = emptyList(),
    val distribution: GradeDistribution = GradeDistribution(),
    val byType: List<TypeStat> = emptyList(),
) {
    val hasData: Boolean
        get() = totalCourses > 0
}

/**
 * 由本地缓存的成绩列表计算统计结果。纯函数、无副作用，便于单元测试。
 *
 * 计算约定：
 * - 绩点加权只统计「绩点可解析且学分 > 0」的课程，避免把未公布绩点的课程按 0 计入。
 * - 平均分加权只统计「分数为 0..100 的数字且学分 > 0」的课程。
 * - 等级制成绩（合格/优秀等）参与门数与学分统计，但不参与绩点与平均分的加权。
 */
object GradeStatsCalculator {
    fun compute(grades: List<Grade>): GradeStats {
        if (grades.isEmpty()) return GradeStats()

        var totalCredits = 0.0
        var gpaPoints = 0.0
        var gpaCredits = 0.0
        var scorePoints = 0.0
        var scoreCredits = 0.0
        var failed = 0

        val distributionCounts = intArrayOf(0, 0, 0, 0, 0, 0)
        val termGroups = LinkedHashMap<String, MutableList<Grade>>()
        val typeGroups = LinkedHashMap<String, MutableList<Grade>>()

        grades.forEach { grade ->
            val credit = grade.credit.toDoubleOrNull() ?: 0.0
            if (credit > 0) totalCredits += credit
            if (!grade.passed) failed++

            val score = grade.score.toDoubleOrNull()
            val bucket = when {
                score == null -> 5
                score >= 90 -> 0
                score >= 80 -> 1
                score >= 70 -> 2
                score >= 60 -> 3
                else -> 4
            }
            distributionCounts[bucket]++

            val gpa = grade.gpa.toDoubleOrNull()
            if (gpa != null && credit > 0) {
                gpaPoints += gpa * credit
                gpaCredits += credit
            }
            if (score != null && score in 0.0..100.0 && credit > 0) {
                scorePoints += score * credit
                scoreCredits += credit
            }

            termGroups.getOrPut(grade.term.label) { mutableListOf() }.add(grade)
            typeGroups.getOrPut(grade.courseType.trim()) { mutableListOf() }.add(grade)
        }

        val terms = termGroups.values
            .map { group -> buildTermStat(group) }
            .sortedWith(compareByDescending<TermStat> { it.term.year }.thenByDescending { it.term.number })

        val byType = typeGroups
            .map { (type, group) -> buildTypeStat(type, group) }
            .sortedWith(compareByDescending<TypeStat> { it.credits }.thenBy { it.courseType })

        return GradeStats(
            totalCourses = grades.size,
            totalCredits = totalCredits,
            cumulativeGpa = if (gpaCredits > 0) gpaPoints / gpaCredits else null,
            weightedScore = if (scoreCredits > 0) scorePoints / scoreCredits else null,
            failedCourses = failed,
            terms = terms,
            distribution = GradeDistribution(
                excellent = distributionCounts[0],
                good = distributionCounts[1],
                medium = distributionCounts[2],
                pass = distributionCounts[3],
                fail = distributionCounts[4],
                unknown = distributionCounts[5],
            ),
            byType = byType,
        )
    }

    private fun buildTermStat(grades: List<Grade>): TermStat {
        val term = grades.first().term
        var credits = 0.0
        var gpaPoints = 0.0
        var gpaCredits = 0.0
        var scorePoints = 0.0
        var scoreCredits = 0.0
        grades.forEach { grade ->
            val credit = grade.credit.toDoubleOrNull() ?: 0.0
            if (credit > 0) credits += credit
            val gpa = grade.gpa.toDoubleOrNull()
            if (gpa != null && credit > 0) {
                gpaPoints += gpa * credit
                gpaCredits += credit
            }
            val score = grade.score.toDoubleOrNull()
            if (score != null && score in 0.0..100.0 && credit > 0) {
                scorePoints += score * credit
                scoreCredits += credit
            }
        }
        return TermStat(
            term = term,
            courseCount = grades.size,
            credits = credits,
            gpa = if (gpaCredits > 0) gpaPoints / gpaCredits else null,
            weightedScore = if (scoreCredits > 0) scorePoints / scoreCredits else null,
        )
    }

    private fun buildTypeStat(courseType: String, grades: List<Grade>): TypeStat {
        var credits = 0.0
        var gpaPoints = 0.0
        var gpaCredits = 0.0
        grades.forEach { grade ->
            val credit = grade.credit.toDoubleOrNull() ?: 0.0
            if (credit > 0) credits += credit
            val gpa = grade.gpa.toDoubleOrNull()
            if (gpa != null && credit > 0) {
                gpaPoints += gpa * credit
                gpaCredits += credit
            }
        }
        return TypeStat(
            courseType = courseType,
            courseCount = grades.size,
            credits = credits,
            gpa = if (gpaCredits > 0) gpaPoints / gpaCredits else null,
        )
    }
}
