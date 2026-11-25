package org.openscantron.domain

import kotlinx.serialization.Serializable

enum class Classification { BLANK, SINGLE_MARK, MULTIPLE_MARKS, AMBIGUOUS, INVALID }
enum class MultiplePolicy { REVIEW, INVALID, PARTIAL }
enum class GradeState { PROVISIONAL, FINAL }

@Serializable
data class Observation(
    val classification: Classification,
    val candidates: List<String> = emptyList(),
    val evidence: List<Double> = emptyList(),
    val margin: Double = 0.0
) {
    fun token() = when (classification) {
        Classification.BLANK -> "BLANK"
        Classification.SINGLE_MARK -> candidates.single()
        Classification.MULTIPLE_MARKS -> "MULTIPLE"
        Classification.AMBIGUOUS -> "AMBIGUOUS"
        Classification.INVALID -> "INVALID"
    }
}

@Serializable
data class Policy(
    val correct: Double = 1.0,
    val wrong: Double = 0.0,
    val blank: Double = 0.0,
    val invalid: Double = 0.0,
    val multiple: MultiplePolicy = MultiplePolicy.REVIEW,
    val partialFraction: Double = 0.5,
    val weights: List<Double> = emptyList(),
    val floorAtZero: Boolean = false,
    val passAt: Double = 60.0,
    val passInPoints: Boolean = false
) {
    fun validate(count: Int) {
        require(correct.isFinite() && correct >= 0.0)
        require(listOf(wrong, blank, invalid).all { it.isFinite() && it <= correct })
        require(partialFraction in 0.0..1.0)
        require(weights.isEmpty() || weights.size == count)
        require(weights.all { it.isFinite() && it >= 0.0 })
        require(passAt.isFinite() && passAt >= 0 && (passInPoints || passAt <= 100))
        val totalWeight = if (weights.isEmpty()) count.toDouble() else weights.sum()
        require(totalWeight.isFinite() && listOf(correct, wrong, blank, invalid).all { (it * totalWeight).isFinite() })
    }
}

@Serializable
data class Grade(
    val correct: Int,
    val incorrect: Int,
    val blank: Int,
    val invalid: Int,
    val pending: Int,
    val score: Double,
    val maximum: Double,
    val percentage: Double?,
    val passed: Boolean?,
    val state: GradeState
)

object Grader {
    fun effective(observation: Observation, correction: String?) = correction ?: observation.token()

    fun grade(key: List<String>, policy: Policy, observations: List<Observation>,
              corrections: Map<Int, String>): Grade {
        require(observations.size == key.size)
        policy.validate(key.size)
        var correct = 0
        var wrong = 0
        var blank = 0
        var invalid = 0
        var pending = 0
        var score = 0.0
        var maximum = 0.0
        key.forEachIndexed { index, answer ->
            val weight = policy.weights.getOrElse(index) { 1.0 }
            maximum += policy.correct * weight
            val observation = observations[index]
            val points = when (effective(observation, corrections[index + 1])) {
                answer -> { correct++; policy.correct }
                "BLANK" -> { blank++; policy.blank }
                "AMBIGUOUS", "PENDING_REVIEW" -> { pending++; 0.0 }
                "MULTIPLE" -> when (policy.multiple) {
                    MultiplePolicy.REVIEW -> { pending++; 0.0 }
                    MultiplePolicy.INVALID -> { invalid++; policy.invalid }
                    MultiplePolicy.PARTIAL -> {
                        invalid++
                        if (answer in observation.candidates) policy.correct * policy.partialFraction
                        else policy.invalid
                    }
                }
                "INVALID" -> { invalid++; policy.invalid }
                else -> { wrong++; policy.wrong }
            }
            score += points * weight
        }
        if (policy.floorAtZero) score = score.coerceAtLeast(0.0)
        val percentage = if (maximum > 0) 100 * (score / maximum) else null
        val state = if (pending == 0) GradeState.FINAL else GradeState.PROVISIONAL
        val passed = if (pending != 0) null else if (policy.passInPoints) score >= policy.passAt
            else percentage?.let { it >= policy.passAt }
        return Grade(correct, wrong, blank, invalid, pending, score, maximum, percentage, passed, state)
    }
}
