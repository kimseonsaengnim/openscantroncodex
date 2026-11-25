package org.openscantron.export

import org.openscantron.domain.Grade

data class ItemResult(val question: Int, val correct: Int, val incorrect: Int, val blank: Int,
                      val invalid: Int, val choices: Map<String, Int>, val distractor: String?, val total: Int) {
    val difficulty get() = correct.toDouble() / total
}

data class Summary(
    val final: List<Response>, val provisional: Int, val mean: Double?, val median: Double?,
    val minimum: Double?, val maximum: Double?, val passed: Int, val failed: Int, val items: List<ItemResult>
)

object Statistics {
    fun compute(rows: List<Response>, keys: Map<String, List<String>>): Summary {
        val final = rows.filter { it.final && it.submission.effective }
        val scores = final.map { it.grade.score }.sorted()
        val median = scores.takeIf { it.isNotEmpty() }?.let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2.0 }
        val count = final.maxOfOrNull { it.answers.size } ?: 0
        val items = (0 until count).map { index ->
            val eligible = final.filter { index in it.answers.indices }
            val choices = mutableMapOf<String, Int>()
            val distractors = mutableMapOf<String, Int>()
            var right = 0
            var wrong = 0
            var blank = 0
            var invalid = 0
            eligible.forEach { row ->
                val answer = row.answer(index)
                when {
                    answer == "BLANK" -> blank++
                    answer in setOf("INVALID", "MULTIPLE") -> invalid++
                    answer == keys[row.key.id]?.get(index) -> {
                        right++
                        choices[answer] = (choices[answer] ?: 0) + 1
                    }
                    else -> {
                        wrong++
                        choices[answer] = (choices[answer] ?: 0) + 1
                        distractors[answer] = (distractors[answer] ?: 0) + 1
                    }
                }
            }
            val distractor = distractors.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { it.key }).firstOrNull()?.key
            ItemResult(index + 1, right, wrong, blank, invalid, choices.toSortedMap(), distractor, eligible.size)
        }
        return Summary(final, rows.count { !it.final }, scores.takeIf { it.isNotEmpty() }?.average(), median,
            scores.firstOrNull(), scores.lastOrNull(), final.count { it.grade.passed == true },
            final.count { it.grade.passed == false }, items)
    }

    fun score(grade: Grade) = "${format(grade.score)} / ${format(grade.maximum)}" +
        (grade.percentage?.let { " (${format(it)}%)" } ?: "")

    fun format(value: Double?) = value?.let { java.lang.String.format(java.util.Locale.ROOT, "%.2f", it) } ?: "—"
}
