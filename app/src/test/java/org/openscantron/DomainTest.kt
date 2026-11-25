package org.openscantron

import org.junit.Assert.*
import org.junit.Test
import org.openscantron.domain.*
import org.openscantron.export.Csv
import org.openscantron.export.Datasets
import java.io.StringReader
import java.io.StringWriter

class DomainTest {
    private val key = "A B C D A B C D A B".split(" ")
    private fun one(answer: String) = Observation(Classification.SINGLE_MARK, listOf(answer))

    @Test fun acceptanceScoringAndRegrade() {
        val observed = "A B C A A B C".split(" ").map(::one) + listOf(
            Observation(Classification.BLANK), Observation(Classification.MULTIPLE_MARKS, listOf("A", "D")),
            Observation(Classification.AMBIGUOUS, listOf("B"))
        )
        val provisional = Grader.grade(key, Policy(), observed, emptyMap())
        assertEquals(2, provisional.pending)
        assertEquals(1, provisional.blank)
        assertNull(provisional.passed)
        val corrected = mapOf(8 to "BLANK", 9 to "INVALID", 10 to "B")
        val resolved = Grader.grade(key, Policy(), observed, corrected)
        assertEquals(Grade(7, 1, 1, 1, 0, 7.0, 10.0, 70.0, true, GradeState.FINAL), resolved)
        val changedKey = key.toMutableList().apply { this[3] = "A" }
        assertEquals(8.0, Grader.grade(changedKey, Policy(), observed, corrected).score, 0.0)
        assertEquals(Classification.MULTIPLE_MARKS, observed[8].classification)
    }

    @Test fun weightedDeductionsPartialAndFloor() {
        val key = listOf("A", "C")
        val observed = listOf(Observation(Classification.MULTIPLE_MARKS, listOf("A", "D")), one("D"))
        val p = Policy(correct = 2.0, wrong = -0.6, multiple = MultiplePolicy.PARTIAL,
            partialFraction = .5, weights = listOf(1.0, 2.0), passAt = 0.0, passInPoints = true)
        val raw = Grader.grade(key, p, observed, emptyMap())
        assertEquals(-.2, raw.score, 1e-12)
        assertEquals(6.0, raw.maximum, 0.0)
        assertEquals(false, raw.passed)
        val floored = Grader.grade(key, p.copy(floorAtZero = true), observed, emptyMap())
        assertEquals(0.0, floored.score, 0.0)
        assertEquals(true, floored.passed)
    }

    @Test fun zeroMaximumAndUnroundedThreshold() {
        val zero = Grader.grade(listOf("A"), Policy(correct = 0.0), listOf(one("A")), emptyMap())
        assertNull(zero.percentage)
        assertNull(zero.passed)
        val g = Grader.grade(listOf("A", "A", "A"), Policy(passAt = 66.67),
            listOf(one("A"), one("A"), one("B")), emptyMap())
        assertEquals(false, g.passed)
    }

    @Test fun csvQuotesNewlinesUnicodeAndId() {
        val rows = listOf(listOf("0017", "Kim, \"서연\"", "line\r\nnext", ""), listOf("2", "3", "", ""))
        val wire = StringWriter().also { Csv.write(it, rows.asSequence()) }.toString()
        assertEquals(rows, Csv.read(StringReader(wire)))
        assertEquals(rows, Csv.read(StringReader("\uFEFF$wire")))
        assertEquals(listOf(listOf("a", "")), Csv.read(StringReader("a,")))
        assertEquals(emptyList<List<String>>(), Csv.read(StringReader("")))
        assertThrows(IllegalArgumentException::class.java) { Csv.read(StringReader("\"unterminated")) }
        assertThrows(IllegalArgumentException::class.java) { Csv.read(StringReader("\"closed\"trailer")) }
        val header = Datasets.wideHeader(100)
        assertEquals("Q001", header[11])
        assertEquals("Q100", header[110])
        assertEquals("grading_status", header.last())
    }

    @Test fun allSupportedDimensionsKeepBubblesOnPageAndDisjoint() {
        for (paper in Paper.entries) for (size in 1..100) for (choices in 2..6) {
            val layout = SheetLayout("template", "exam", size, List(choices) { ('A' + it).toString() }, paper, 8)
            val bubbles = layout.questions.flatMap { it.bubbles } + layout.identifier.flatten()
            assertEquals((1..size).toList(), layout.questions.map { it.number })
            bubbles.forEachIndexed { index, first ->
                assertTrue(first.center.x in .06.. .94 && first.center.y in .06.. .94)
                for (i in 0 until index) {
                    val second = bubbles[i]
                    val dx = (first.center.x - second.center.x) * paper.pointsWide
                    val dy = (first.center.y - second.center.y) * paper.pointsHigh
                    assertTrue(kotlin.math.hypot(dx, dy) >= (first.radius + second.radius) * paper.pointsWide)
                }
            }
        }
    }
}
