package org.openscantron.archive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.openscantron.domain.PagePoint
import org.openscantron.domain.Paper
import org.openscantron.domain.SheetLayout
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun publish(target: File, write: (File) -> Unit) {
    target.parentFile?.mkdirs()
    val temporary = File.createTempFile(".pending-", ".tmp", target.parentFile)
    try {
        write(temporary)
        FileOutputStream(temporary, true).use { it.fd.sync() }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}

object Photos {
    fun oriented(file: File, limit: Int = 3000, extraRotation: Int = 0): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The image could not be opened. Import a JPEG or PNG." }
        val options = BitmapFactory.Options()
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize.coerceAtLeast(1) > limit * 1.5) {
            options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
        }
        val source = requireNotNull(BitmapFactory.decodeFile(file.path, options)) { "Image decoding failed. Import the original again." }
        val exif = runCatching { ExifInterface(file) }.getOrNull()
        val matrix = Matrix()
        if (exif?.isFlipped == true) matrix.postScale(-1f, 1f)
        matrix.postRotate(((exif?.rotationDegrees ?: 0) + extraRotation).toFloat())
        val oriented = if (matrix.isIdentity) source else {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true).also {
                if (it !== source) source.recycle()
            }
        }
        val longest = maxOf(oriented.width, oriented.height)
        if (longest <= limit) return oriented
        val scale = limit.toDouble() / longest
        return Bitmap.createScaledBitmap(oriented, (oriented.width * scale).toInt().coerceAtLeast(1),
            (oriented.height * scale).toInt().coerceAtLeast(1), true).also { oriented.recycle() }
    }
}

object Documents {
    private fun pdf(file: File, paper: Paper, count: Int, draw: (Canvas, Int) -> Unit) {
        require(count > 0)
        publish(file) { temporary ->
            val document = PdfDocument()
            try {
                repeat(count) { index ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(
                        paper.pointsWide, paper.pointsHigh, index + 1).create())
                    try { draw(page.canvas, index) } finally { document.finishPage(page) }
                }
                temporary.outputStream().use(document::writeTo)
            } finally { document.close() }
            require(pageCount(temporary) == count) { "The generated PDF did not open." }
        }
    }

    fun pageCount(file: File): Int =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { it.pageCount }
        }

    fun preview(file: File, page: Int = 0): Bitmap =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(page).use { image ->
                    Bitmap.createBitmap(image.width * 2, image.height * 2, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.WHITE)
                        image.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    fun originals(file: File, paper: Paper, originals: List<Pair<File, Int>>, limit: Int = 3000) =
        ImagePdf.write(file, paper, originals, limit)

    fun template(file: File, layout: SheetLayout, title: String, copies: Int = 1,
                 reference: String? = null, date: String = "") =
        pdf(file, layout.paper, copies) { canvas, _ ->
            val width = layout.paper.pointsWide
            val height = layout.paper.pointsHigh
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            fun x(value: Double) = (value * width).toFloat()
            fun y(value: Double) = (value * height).toFloat()
            fun square(point: PagePoint, size: Double) {
                val r = x(size) / 2
                canvas.drawRect(x(point.x) - r, y(point.y) - r, x(point.x) + r, y(point.y) + r, paint)
            }
            fun text(value: String, left: Double, baseline: Double, size: Float) {
                paint.textAlign = Paint.Align.LEFT
                paint.textSize = size
                val count = paint.breakText(value, true, width * (.91 - left).toFloat(), null)
                canvas.drawText(value.take(count), x(left), y(baseline), paint)
            }
            canvas.drawColor(Color.WHITE)
            layout.fiducials.forEach { square(it, layout.markerWidth) }
            layout.cues.forEach { square(it, .010) }
            text(title, .13, .066, 15f)
            text("Exam ${layout.examId.take(8).uppercase()}    Form ${layout.id.take(8)}    ${layout.count} questions    $date", .13, .086, 8f)
            text("Name ________________________   Section ________", .085, .107, 10f)
            text("Student number   Fill one digit in each column", .085, .121, 8f)
            text("Fill one bubble per question. Erase changes completely.", .085, .319, 9f)
            text("Keep all four corner squares clean and visible.", .085, .339, 8f)

            val qr = QRCodeWriter().encode(layout.qr(reference), BarcodeFormat.QR_CODE, 1, 1)
            val size = x(layout.qrWidth)
            val module = size / qr.width
            val qrX = x(layout.qrCenter.x) - size / 2
            val qrY = y(layout.qrCenter.y) - size / 2
            repeat(qr.height) { row ->
                repeat(qr.width) { column ->
                    if (qr[column, row]) canvas.drawRect(qrX + column * module, qrY + row * module,
                        qrX + (column + 1) * module, qrY + (row + 1) * module, paint)
                }
            }

            fun bubble(point: PagePoint, radius: Double, label: String) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = .65f
                canvas.drawCircle(x(point.x), y(point.y), x(radius), paint)
                paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 5.6f
                paint.color = Color.rgb(153, 153, 153)
                canvas.drawText(label, x(point.x), y(point.y) + 1.9f, paint)
                paint.color = Color.BLACK
            }
            layout.identifier.forEach { column ->
                column.forEachIndexed { index, cell -> bubble(cell.center, cell.radius, index.toString()) }
            }
            layout.questions.forEach { row ->
                val first = row.bubbles.first().center
                paint.textAlign = Paint.Align.RIGHT
                paint.textSize = 7.5f
                canvas.drawText(row.number.toString(), x(first.x - .013), y(first.y) + 2.7f, paint)
                row.bubbles.forEachIndexed { index, cell -> bubble(cell.center, cell.radius, layout.labels[index]) }
            }
        }

    fun report(file: File, title: String, lines: List<String>) {
        val pages = lines.chunked(37).ifEmpty { listOf(emptyList()) }
        pdf(file, Paper.A4, pages.size) { canvas, index ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.textSize = 18f
            canvas.drawText(title.take(52), 38f, 48f, paint)
            paint.textSize = 11f
            pages[index].forEachIndexed { i, line ->
                canvas.drawText(line.take(paint.breakText(line, true, 515f, null)), 38f, 76f + i * 19, paint)
            }
            canvas.drawText("${index + 1} / ${pages.size}", 38f, 810f, paint)
        }
    }
}
