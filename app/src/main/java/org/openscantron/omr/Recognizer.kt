package org.openscantron.omr

import android.graphics.Bitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.serialization.Serializable
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.openscantron.domain.Bubble
import org.openscantron.domain.Classification
import org.openscantron.domain.Observation
import org.openscantron.domain.PagePoint
import org.openscantron.domain.SheetLayout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

@Serializable
data class Recognition(
    val answers: List<Observation>,
    val number: String?,
    val reference: String?,
    val idEvidence: List<Observation>,
    val warnings: List<String>,
    val sharpness: Double
)

data class MarkThresholds(val blank: Double = .12, val filled: Double = .34, val separation: Double = .14)

class Recognizer(private val thresholds: MarkThresholds = MarkThresholds()) {
    private companion object {
        val nativeAvailable by lazy { OpenCVLoader.initLocal() }
    }

    private data class Marker(val center: Point, val area: Double)

    private class Mats {
        private val allocated = mutableListOf<Mat>()
        fun <T : Mat> keep(mat: T): T = mat.also(allocated::add)
        fun close() = allocated.asReversed().forEach(Mat::release)
    }

    fun read(source: Bitmap, layout: SheetLayout, onRectified: (Bitmap) -> Unit = {}): Recognition {
        check(nativeAvailable) { "OpenCV could not load on this device." }
        val rgba = Mat()
        val gray = Mat()
        try {
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            return readGray(gray, layout) { rectified ->
                val preview = Bitmap.createBitmap(rectified.cols(), rectified.rows(), Bitmap.Config.ARGB_8888)
                try {
                    Utils.matToBitmap(rectified, preview)
                    onRectified(preview)
                } finally { preview.recycle() }
            }
        } finally {
            gray.release()
            rgba.release()
        }
    }

    internal fun readGray(gray: Mat, layout: SheetLayout, onRectified: (Mat) -> Unit = {}): Recognition {
        require(minOf(gray.cols(), gray.rows()) >= 620) {
            "Resolution is too low. Move closer and include all four squares."
        }
        val mats = Mats()
        try {
            val sharpness = sharpness(gray, mats)
            val width = 1120
            val height = canonicalHeight(layout, width)
            val rectified = mats.keep(Mat())
            val best = align(gray, layout, width, height, rectified, mats)
            require(best) { "The sheet could not be aligned. Use the matching blank template and retake with all corner squares visible." }

            val warnings = mutableListOf<String>()
            if (sharpness < 32) warnings += "The photograph appears soft. Check faint marks or retake."
            val pixels = ByteArray(width * height)
            rectified.get(0, 0, pixels)
            val raster = Raster(width, height, pixels)
            val payload = readQr(rectified, layout, mats)
            val qrParts = payload?.split(':', limit = 5)
            if (qrParts == null) {
                warnings += "QR unreadable. Alignment was validated using the selected layout."
            } else {
                require(qrParts.size == 5 && qrParts[0] == "OS" && qrParts[1] == layout.schema.toString() &&
                    qrParts[2] == layout.examId && qrParts[3] == layout.id) {
                    "QR belongs to a different exam or template. Select the matching exam and import again."
                }
            }
            val answers = layout.questions.map { row ->
                classify(row.bubbles.map(raster::darkness), layout.labels)
            }
            val id = layout.identifier.map { column ->
                classify(column.map(raster::darkness), (0..9).map(Int::toString))
            }
            val number = if (id.all { it.classification == Classification.SINGLE_MARK }) {
                id.joinToString("") { it.candidates.single() }
            } else null
            if (number == null && id.any { it.classification != Classification.BLANK }) {
                warnings += "Student number contains faint, missing, or multiple digits. Assign the student manually."
            }
            onRectified(rectified)
            return Recognition(answers, number, qrParts?.get(4)?.ifBlank { null }, id, warnings, sharpness)
        } finally { mats.close() }
    }

    fun previewAligned(width: Int, height: Int, luminance: ByteArray, layout: SheetLayout): Boolean {
        if (minOf(width, height) < 360 || !nativeAvailable) return false
        val mats = Mats()
        try {
            val gray = mats.keep(Mat(height, width, org.opencv.core.CvType.CV_8UC1))
            gray.put(0, 0, luminance)
            return align(gray, layout, 700, canonicalHeight(layout, 700), mats.keep(Mat()), mats)
        } finally { mats.close() }
    }

    private fun canonicalHeight(layout: SheetLayout, width: Int) =
        (width.toDouble() * layout.paper.pointsHigh / layout.paper.pointsWide).roundToInt()

    private fun align(gray: Mat, layout: SheetLayout, width: Int, height: Int, rectified: Mat, mats: Mats): Boolean {
        val binary = mats.keep(Mat())
        Imgproc.adaptiveThreshold(gray, binary, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV, 71, 13.0)
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(binary, contours, mats.keep(Mat()), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        val markers = contours.mapNotNull { contour ->
            try { findMarker(contour, gray) } finally { contour.release() }
        }.sortedByDescending { it.area }.fold(mutableListOf<Marker>()) { unique, candidate ->
            if (unique.none { hypot(it.center.x - candidate.center.x, it.center.y - candidate.center.y) < 11 }) {
                unique.add(candidate)
            }
            unique
        }.take(16)
        val corners = mats.keep(MatOfPoint2f(*layout.fiducials.map { point(it, width, height) }.toTypedArray()))
        return choosePage(markers, gray, layout, corners, width, height, rectified)
    }

    fun classify(evidence: List<Double>, labels: List<String>): Observation {
        val ranking = evidence.indices.sortedByDescending { evidence[it] }
        val top = evidence[ranking[0]]
        val margin = top - evidence[ranking[1]]
        val strong = ranking.filter { evidence[it] >= thresholds.filled }.map(labels::get)
        val possible = ranking.filter { evidence[it] >= thresholds.blank }.map(labels::get)
        val classification = when {
            top < thresholds.blank -> Classification.BLANK
            strong.size > 1 -> Classification.MULTIPLE_MARKS
            strong.size == 1 && margin >= thresholds.separation && possible.size == 1 -> Classification.SINGLE_MARK
            else -> Classification.AMBIGUOUS
        }
        val choices = when (classification) {
            Classification.SINGLE_MARK, Classification.MULTIPLE_MARKS -> strong
            else -> possible
        }
        return Observation(classification, choices, evidence, margin)
    }

    private fun findMarker(contour: MatOfPoint, gray: Mat): Marker? {
        val area = Imgproc.contourArea(contour)
        val page = gray.total().toDouble()
        if (area !in (page * .000045)..(page * .013)) return null
        val bounds = Imgproc.boundingRect(contour)
        if (bounds.width.toDouble() / bounds.height !in .55..1.8 ||
            area / (bounds.width * bounds.height) < .58) return null
        val curve = MatOfPoint2f(*contour.toArray())
        val approximation = MatOfPoint2f()
        try {
            Imgproc.approxPolyDP(curve, approximation, Imgproc.arcLength(curve, true) * .035, true)
            if (approximation.rows() != 4) return null
            val center = Point(bounds.x + bounds.width / 2.0, bounds.y + bounds.height / 2.0)
            if (gray.get(center.y.toInt(), center.x.toInt())[0] > 145) return null
            return Marker(center, area)
        } finally {
            curve.release()
            approximation.release()
        }
    }

    private fun choosePage(markers: List<Marker>, gray: Mat, layout: SheetLayout, corners: MatOfPoint2f,
                           width: Int, height: Int, target: Mat): Boolean {
        if (markers.size < 4) return false
        val candidates = mutableListOf<Pair<Double, List<Point>>>()
        for (a in 0 until markers.size - 3) for (b in a + 1 until markers.size - 2)
            for (c in b + 1 until markers.size - 1) for (d in c + 1 until markers.size) {
                val group = listOf(markers[a], markers[b], markers[c], markers[d])
                if (group.maxOf { it.area } / group.minOf { it.area } > 5.5) continue
                val center = Point(group.map { it.center.x }.average(), group.map { it.center.y }.average())
                val ordered = group.map { it.center }.sortedBy { atan2(it.y - center.y, it.x - center.x) }
                val edges = ordered.indices.map { i ->
                    val p = ordered[i]
                    val q = ordered[(i + 1) % 4]
                    hypot(p.x - q.x, p.y - q.y)
                }
                if (edges.min() < minOf(gray.width(), gray.height()) * .34 ||
                    edges.max() / edges.min() > 2.85) continue
                val crosses = ordered.indices.map { i ->
                    val (p, q, r) = (0..2).map { ordered[(i + it) % 4] }
                    (q.x - p.x) * (r.y - q.y) - (q.y - p.y) * (r.x - q.x)
                }
                if (crosses.any { it <= 0 } || crosses.max() / crosses.min() > 3.8) continue
                val area = crosses.sum() / 4
                candidates += area to ordered
            }
        val cues = MatOfPoint2f(*layout.cues.map { point(it, width, height) }.toTypedArray())
        val mapped = MatOfPoint2f()
        try {
            for ((_, ordered) in candidates.sortedByDescending { it.first }.take(36)) {
                repeat(4) { rotation ->
                    val source = MatOfPoint2f(*(ordered.drop(rotation) + ordered.take(rotation)).toTypedArray())
                    val inverse = Imgproc.getPerspectiveTransform(corners, source)
                    try {
                        Core.perspectiveTransform(cues, mapped, inverse)
                        if (mapped.toArray().all { p ->
                            val x = p.x.roundToInt()
                            val y = p.y.roundToInt()
                            x in 2 until gray.width() - 2 && y in 2 until gray.height() - 2 &&
                                gray.get(y, x)[0] < 150
                        }) {
                            val forward = Imgproc.getPerspectiveTransform(source, corners)
                            try {
                                Imgproc.warpPerspective(gray, target, forward, Size(width.toDouble(), height.toDouble()),
                                    Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, org.opencv.core.Scalar(255.0))
                                val buffer = ByteArray(width * height)
                                target.get(0, 0, buffer)
                                val raster = Raster(width, height, buffer)
                                val rings = (layout.questions.flatMap { it.bubbles } + layout.identifier.flatten())
                                    .filterIndexed { index, _ -> index % 3 == 0 }
                                if (rings.count(raster::hasOutline) >= rings.size * .76) return true
                            } finally { forward.release() }
                        }
                    } finally {
                        inverse.release()
                        source.release()
                    }
                }
            }
            return false
        } finally {
            mapped.release()
            cues.release()
        }
    }

    private fun readQr(gray: Mat, layout: SheetLayout, mats: Mats): String? {
        val center = point(layout.qrCenter, gray.width(), gray.height())
        val radius = (layout.qrWidth * gray.width() / 2).toInt() + 11
        val qr = mats.keep(gray.submat(Rect(center.x.toInt() - radius, center.y.toInt() - radius,
            radius * 2, radius * 2)))
        val colors = IntArray(qr.cols() * qr.rows())
        val row = ByteArray(qr.cols())
        repeat(qr.rows()) { y ->
            qr.get(y, 0, row)
            row.forEachIndexed { x, value ->
                colors[y * qr.cols() + x] = 0xff000000.toInt() or ((value.toInt() and 255) * 0x010101)
            }
        }
        return runCatching {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(
                RGBLuminanceSource(qr.cols(), qr.rows(), colors)))).text
        }.getOrNull()
    }

    private fun sharpness(gray: Mat, mats: Mats): Double {
        val scaled = mats.keep(Mat())
        Imgproc.resize(gray, scaled, Size(0.0, 0.0), 850.0 / gray.width(), 850.0 / gray.width())
        val laplacian = mats.keep(Mat())
        Imgproc.Laplacian(scaled, laplacian, org.opencv.core.CvType.CV_64F)
        val mean = mats.keep(MatOfDouble())
        val deviation = mats.keep(MatOfDouble())
        Core.meanStdDev(laplacian, mean, deviation)
        val std = deviation.toArray().single()
        return std * std
    }

    private fun point(value: PagePoint, width: Int, height: Int) = Point(value.x * width, value.y * height)

    private class Raster(val width: Int, val height: Int, val pixels: ByteArray) {
        fun get(x: Int, y: Int) = pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)].toInt() and 255

        fun hasOutline(cell: Bubble): Boolean {
            val x = cell.center.x * width
            val y = cell.center.y * height
            val r = cell.radius * width
            return (0..11).count { angle ->
                val theta = angle * PI / 6
                listOf(.82, .93, 1.02, 1.11, 1.20).any { scale ->
                    get((x + r * scale * kotlin.math.cos(theta)).roundToInt(),
                        (y + r * scale * kotlin.math.sin(theta)).roundToInt()) < 160
                }
            } >= 8
        }

        fun darkness(cell: Bubble): Double {
            val x = (cell.center.x * width).roundToInt()
            val y = (cell.center.y * height).roundToInt()
            val r = cell.radius * width
            val limit = (r * 1.55).roundToInt()
            val border = mutableListOf<Int>()
            val interior = mutableListOf<Int>()
            for (dy in -limit..limit) for (dx in -limit..limit) {
                val distance = dx * dx + dy * dy
                if (distance <= r * r * .51) interior += get(x + dx, y + dy)
                else if (distance.toDouble() in r * r * 1.68..r * r * 2.4) border += get(x + dx, y + dy)
            }
            border.sort()
            val paper = border[(border.size * .83).toInt()].coerceAtLeast(48)
            return interior.map { pixel ->
                ((paper - pixel - 22.0) / (paper * .63)).coerceIn(0.0, 1.0)
            }.average()
        }
    }
}
