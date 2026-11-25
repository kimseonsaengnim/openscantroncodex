package org.openscantron.domain

import kotlinx.serialization.Serializable
import kotlin.math.ceil

@Serializable
enum class Paper(val pointsWide: Int, val pointsHigh: Int) { A4(595, 842), LETTER(612, 792) }

data class PagePoint(val x: Double, val y: Double)
data class Bubble(val center: PagePoint, val radius: Double)
data class QuestionRow(val number: Int, val bubbles: List<Bubble>)

@Serializable
data class SheetLayout(
    val id: String,
    val examId: String,
    val count: Int,
    val labels: List<String>,
    val paper: Paper,
    val digits: Int = 4,
    val schema: Int = 1
) {
    init {
        require(schema == 1 && count in 1..100 && digits in 1..8)
        require(labels.size in 2..6 && labels.distinct().size == labels.size)
        require(labels.all { it.matches(Regex("[A-Z0-9]{1,2}")) })
    }

    val fiducials get() = listOf(
        PagePoint(.055, .045), PagePoint(.945, .045),
        PagePoint(.945, .955), PagePoint(.055, .955)
    )
    val markerWidth get() = .025
    val cues get() = listOf(PagePoint(.10, .045), PagePoint(.91, .31), PagePoint(.11, .915))
    val qrCenter get() = PagePoint(.795, .161)
    val qrWidth get() = .18
    val identifier get() = List(digits) { digit ->
        List(10) { number -> Bubble(PagePoint(.118 + digit * .056, .133 + number * .0165), .0074) }
    }
    val questions get(): List<QuestionRow> {
        val columns = ceil(count / 25.0).toInt()
        val rows = ceil(count / columns.toDouble()).toInt()
        val step = .84 / columns
        return List(count) { index ->
            val left = .084 + index / rows * step
            QuestionRow(index + 1, List(labels.size) { choice ->
                Bubble(PagePoint(left + step * (.16 + (choice + .5) * .79 / labels.size),
                    .367 + (index % rows) * .0214), .0080)
            })
        }
    }

    fun qr(studentReference: String? = null) =
        listOf("OS", schema, examId, id, studentReference.orEmpty()).joinToString(":")
}
