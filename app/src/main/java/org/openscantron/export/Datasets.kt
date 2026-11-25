package org.openscantron.export

import org.openscantron.data.*
import org.openscantron.domain.Grade
import org.openscantron.domain.GradeState
import org.openscantron.domain.Observation
import org.openscantron.domain.Policy
import org.openscantron.domain.MultiplePolicy

data class Response(
    val submission: Submission, val student: Student?, val key: AnswerKeyVersion,
    val answers: List<Observation>, val corrections: Map<Int, AnswerCorrection>, val grade: Grade,
    val policy: Policy, val effectiveAnswers: List<String>
) {
    fun answer(index: Int) = effectiveAnswers[index]
    fun pending(index: Int) = answer(index) in setOf("AMBIGUOUS", "PENDING_REVIEW") ||
        (answer(index) == "MULTIPLE" && policy.multiple == MultiplePolicy.REVIEW)
    val final get() = grade.state == GradeState.FINAL && submission.confirmed && student != null
}

object Datasets {
    fun wideHeader(count: Int) = listOf("submission_id", "exam_id", "exam_title", "subject",
        "exam_date", "student_id", "student_name", "section", "batch_id", "scanned_at", "answer_key_version") +
        (1..count).map { "Q" + it.toString().padStart(3, '0') } +
        listOf("correct_count", "incorrect_count", "blank_count", "invalid_count", "pending_count",
            "score", "max_score", "percentage", "passing_status", "grading_status")

    fun wide(exam: Examination, response: Response, width: Int): List<String> {
        val (s, student, key, _, _, g) = response
        return listOf(s.id, exam.id, exam.title, exam.subject, exam.date, student?.number.orEmpty(),
            student?.name.orEmpty(), student?.section.orEmpty(), s.batchId, s.scannedAt, key.version.toString()) +
            List(width) { i -> if (i < response.answers.size) response.answer(i) else "" } +
            listOf(g.correct.toString(), g.incorrect.toString(), g.blank.toString(), g.invalid.toString(),
                g.pending.toString(), if (response.final) g.score.toString() else "", g.maximum.toString(),
                if (response.final) g.percentage?.toString().orEmpty() else "",
                if (response.final) g.passed?.let { if (it) "PASS" else "FAIL" }.orEmpty() else "",
                if (response.final) "FINAL" else "PROVISIONAL")
    }

    val auditHeader = listOf("submission_id", "exam_id", "student_id", "batch_id", "template_id",
        "answer_key_version", "question", "machine_answer", "candidates", "effective_answer",
        "mark_margin", "choice_evidence", "classification", "review_status", "manual", "corrected_at",
        "effective_attempt")

    fun audit(response: Response): Sequence<List<String>> = response.answers.asSequence().mapIndexed { i, mark ->
        val s = response.submission
        val correction = response.corrections[i + 1]
        listOf(s.id, s.examId, response.student?.number.orEmpty(), s.batchId, s.templateId,
            response.key.version.toString(), (i + 1).toString(), mark.token(), mark.candidates.joinToString("|"),
            response.answer(i), mark.margin.toString(), mark.evidence.joinToString("|"),
            mark.classification.name, if (response.pending(i)) "PENDING_REVIEW" else "RESOLVED",
            (correction != null).toString(), correction?.time.orEmpty(), s.effective.toString())
    }
}
