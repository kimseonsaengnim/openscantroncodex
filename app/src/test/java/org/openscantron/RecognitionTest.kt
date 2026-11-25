package org.openscantron

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import nu.pattern.OpenCV
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.openscantron.domain.*
import org.openscantron.export.Csv
import org.openscantron.omr.Recognizer
import org.opencv.imgcodecs.Imgcodecs
import kotlin.math.roundToInt

class RecognitionTest {
    companion object {
        @BeforeClass @JvmStatic fun load() { OpenCV.loadLocally() }
    }

    private val grounds = checkNotNull(javaClass.getResourceAsStream("/marks-0017.csv"))
        .bufferedReader().use(Csv::read).drop(1)
    private val layout = SheetLayout("3f19c23a-812f-49a4-a6bf-fb9c90c0d743",
        "74fc0d60-2e19-434a-9a56-de436e51336b", 10, listOf("A", "B", "C", "D"), Paper.A4)

    private fun render(model: SheetLayout = layout, qr: String = model.qr()): Mat {
        val width = 1540
        val height = (width * model.paper.pointsHigh.toDouble() / model.paper.pointsWide).roundToInt()
        val image = Mat(height, width, CvType.CV_8UC1, Scalar(255.0))
        fun square(point: PagePoint, size: Double) {
            val d = (width * size).roundToInt()
            Imgproc.rectangle(image, Rect((point.x * width - d / 2.0).roundToInt(),
                (point.y * height - d / 2.0).roundToInt(), d, d), Scalar(0.0), -1)
        }
        model.fiducials.forEach { square(it, model.markerWidth) }
        model.cues.forEach { square(it, .010) }
        Imgproc.putText(image, "Fictional fixture 0017", Point(width / 8.0, height / 11.0),
            Imgproc.FONT_HERSHEY_SIMPLEX, .8, Scalar(0.0), 2, Imgproc.LINE_AA)
        val code = QRCodeWriter().encode(qr, BarcodeFormat.QR_CODE, 1, 1)
        val scale = model.qrWidth * width / code.width
        val x0 = (model.qrCenter.x - model.qrWidth / 2) * width
        val y0 = model.qrCenter.y * height - model.qrWidth * width / 2
        repeat(code.width) { x -> repeat(code.height) { y ->
            if (code[x, y]) {
                val left = (x0 + x * scale).roundToInt()
                val top = (y0 + y * scale).roundToInt()
                Imgproc.rectangle(image, Rect(left, top, (x0 + (x + 1) * scale).roundToInt() - left,
                    (y0 + (y + 1) * scale).roundToInt() - top), Scalar(0.0), -1)
            }
        } }
        model.identifier.forEachIndexed { digit, column ->
            column.forEachIndexed { i, cell -> bubble(image, cell, i.toString(),
                if ("0017"[digit].digitToInt() == i) 30.0 else null) }
        }
        model.questions.forEachIndexed { index, row ->
            val truth = grounds[index][1]
            row.bubbles.forEachIndexed { i, cell ->
                val label = model.labels[i]
                val ink = when {
                    truth == "$label faint" -> 184.0
                    label in truth.split('|') -> 24.0
                    else -> null
                }
                bubble(image, cell, label, ink)
            }
        }
        return image
    }

    private fun bubble(image: Mat, cell: Bubble, label: String, fill: Double?) {
        val x = (cell.center.x * image.cols()).roundToInt().toDouble()
        val y = (cell.center.y * image.rows()).roundToInt().toDouble()
        val r = (cell.radius * image.cols()).roundToInt()
        Imgproc.circle(image, Point(x, y), r, Scalar(0.0), 2, Imgproc.LINE_AA)
        Imgproc.putText(image, label, Point(x - 4, y + 4), Imgproc.FONT_HERSHEY_SIMPLEX, .40,
            Scalar(153.0), 1, Imgproc.LINE_AA)
        if (fill != null) Imgproc.circle(image, Point(x, y), r - 1, Scalar(fill), -1, Imgproc.LINE_AA)
    }

    @Test fun syntheticGroundTruthWithRotationLightingAndPerspective() {
        val clean = render()
        val rotated = Mat()
        val skewed = Mat()
        val shaded = Mat()
        val from = MatOfPoint2f(Point(0.0, 0.0), Point(clean.cols().toDouble(), 0.0),
            Point(clean.cols().toDouble(), clean.rows().toDouble()), Point(0.0, clean.rows().toDouble()))
        val to = MatOfPoint2f(Point(110.0, 50.0), Point(clean.cols() - 65.0, 90.0),
            Point(clean.cols() - 10.0, clean.rows() - 30.0), Point(20.0, clean.rows() - 125.0))
        val transform = Imgproc.getPerspectiveTransform(from, to)
        try {
            Core.rotate(clean, rotated, Core.ROTATE_90_CLOCKWISE)
            Imgproc.warpPerspective(clean, skewed, transform, clean.size(), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(175.0))
            Imgproc.GaussianBlur(clean, shaded, Size(5.0, 5.0), .75)
            val bytes = ByteArray(shaded.cols() * shaded.rows())
            shaded.get(0, 0, bytes)
            bytes.indices.forEach { i ->
                val light = 1 - .29 * (i % shaded.cols()) / shaded.cols() -
                    if (i / shaded.cols() > shaded.rows() * .68) .08 else 0.0
                bytes[i] = ((bytes[i].toInt() and 255) * light).roundToInt().toByte()
            }
            shaded.put(0, 0, bytes)
            listOf("clean" to clean, "rotated" to rotated, "perspective" to skewed, "shaded-soft" to shaded).forEach { (name, image) ->
                val start = System.nanoTime()
                val read = Recognizer().readGray(image, layout)
                println("$name: ${(System.nanoTime() - start) / 1_000_000} ms · ${read.answers.map { it.token() }} · ID ${read.number} · ${read.warnings}")
                assertEquals("$name classification", grounds.map { it[2] }, read.answers.map { it.classification.name })
                grounds.take(7).forEachIndexed { i, truth -> assertEquals(truth[1], read.answers[i].token()) }
                assertEquals("0017", read.number)
                assertTrue(read.warnings.none { it.startsWith("QR unreadable") })
            }
        } finally { listOf(clean, rotated, skewed, shaded, from, to, transform).forEach(Mat::release) }
    }

    @Test fun letterAndKnownConflictingQr() {
        val letter = layout.copy(paper = Paper.LETTER)
        val good = render(letter)
        val wrong = render(letter, letter.copy(examId = "wrong").qr())
        try {
            assertEquals("0017", Recognizer().readGray(good, letter).number)
            val error = assertThrows(IllegalArgumentException::class.java) { Recognizer().readGray(wrong, letter) }
            assertTrue(error.message.orEmpty().contains("different exam"))
        } finally { good.release(); wrong.release() }
    }

    @Test fun rejectNoFiducials() {
        val image = Mat(900, 700, CvType.CV_8UC1, Scalar(255.0))
        try {
            assertThrows(IllegalArgumentException::class.java) { Recognizer().readGray(image, layout) }
        } finally { image.release() }
    }

    @Test fun qrlessPageWithErasurePartialFillAndStrayInk() {
        val image = render()
        try {
            val width = image.cols()
            val height = image.rows()
            val qrRadius = (layout.qrWidth * width / 2).roundToInt() + 6
            val qrX = (layout.qrCenter.x * width).roundToInt()
            val qrY = (layout.qrCenter.y * height).roundToInt()
            Imgproc.rectangle(image, Rect(qrX - qrRadius, qrY - qrRadius, qrRadius * 2, qrRadius * 2),
                Scalar(255.0), -1)
            val erased = layout.questions[7].bubbles[0]
            val partial = layout.questions[9].bubbles[1]
            fun center(cell: Bubble) = Point(cell.center.x * width, cell.center.y * height)
            Imgproc.circle(image, center(erased), (erased.radius * width * .78).roundToInt(), Scalar(222.0), -1)
            Imgproc.circle(image, center(partial), (partial.radius * width * .87).roundToInt(), Scalar(255.0), -1)
            Imgproc.circle(image, center(partial), (partial.radius * width * .34).roundToInt(), Scalar(20.0), -1)
            Imgproc.line(image, Point(width * .29, height * .69), Point(width * .31, height * .69), Scalar(0.0), 4)
            val read = Recognizer().readGray(image, layout)
            assertTrue(read.warnings.any { it.startsWith("QR unreadable") })
            assertEquals(Classification.BLANK, read.answers[7].classification)
            assertEquals(Classification.AMBIGUOUS, read.answers[9].classification)
            assertEquals("0017", read.number)
        } finally { image.release() }
    }

    @Test fun generateOptionalDiagnosticPng() {
        val target = System.getProperty("openscantron.fixture")
        assumeNotNull(target)
        val image = render()
        try { assertTrue(Imgcodecs.imwrite(requireNotNull(target), image)) } finally { image.release() }
    }
}
