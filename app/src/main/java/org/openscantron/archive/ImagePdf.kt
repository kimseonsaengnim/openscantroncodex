package org.openscantron.archive

import android.graphics.Bitmap
import org.openscantron.domain.Paper
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.min

internal object ImagePdf {
    fun write(file: File, paper: Paper, originals: List<Pair<File, Int>>, limit: Int) {
        require(originals.isNotEmpty())
        publish(file) { temporary ->
            val jpeg = File.createTempFile(".pending-image-", ".tmp", temporary.parentFile)
            try {
                RandomAccessFile(temporary, "rw").use { out ->
                    val offsets = LongArray(3 + 3 * originals.size)
                    fun ascii(text: String) = out.writeBytes(text)
                    fun start(id: Int) { offsets[id] = out.filePointer; ascii("$id 0 obj\n") }
                    fun end() = ascii("endobj\n")
                    ascii("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n")
                    start(1); ascii("<< /Type /Catalog /Pages 2 0 R >>\n"); end()
                    start(2)
                    val children = originals.indices.joinToString(" ") { "${3 + it * 3} 0 R" }
                    ascii("<< /Type /Pages /Count ${originals.size} /Kids [$children] >>\n")
                    end()
                    originals.forEachIndexed { index, (source, rotation) ->
                        val bitmap = Photos.oriented(source, limit, rotation)
                        val bitmapWidth = bitmap.width
                        val bitmapHeight = bitmap.height
                        try {
                            jpeg.outputStream().use {
                                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)) { "JPEG encoding failed." }
                            }
                        } finally { bitmap.recycle() }
                        val page = 3 + index * 3
                        val image = page + 1
                        val content = page + 2
                        start(page)
                        ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${paper.pointsWide} ${paper.pointsHigh}] " +
                            "/Resources << /XObject << /Photo $image 0 R >> >> /Contents $content 0 R >>\n")
                        end()
                        start(image)
                        ascii("<< /Type /XObject /Subtype /Image /Width $bitmapWidth /Height $bitmapHeight " +
                            "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.length()} >>\nstream\n")
                        jpeg.inputStream().buffered().use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val size = input.read(buffer)
                                if (size < 0) break
                                out.write(buffer, 0, size)
                            }
                        }
                        ascii("\nendstream\n")
                        end()
                        val scale = min((paper.pointsWide - 32.0) / bitmapWidth, (paper.pointsHigh - 32.0) / bitmapHeight)
                        val width = bitmapWidth * scale
                        val height = bitmapHeight * scale
                        val commands = String.format(Locale.ROOT, "q %.6f 0 0 %.6f %.6f %.6f cm /Photo Do Q\n",
                            width, height, (paper.pointsWide - width) / 2, (paper.pointsHigh - height) / 2)
                        start(content)
                        ascii("<< /Length ${commands.length} >>\nstream\n$commands" + "endstream\n")
                        end()
                    }
                    val xref = out.filePointer
                    ascii("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
                    offsets.drop(1).forEach { ascii(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
                    ascii("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
                }
                require(Documents.pageCount(temporary) == originals.size) { "The generated PDF did not open." }
            } finally { jpeg.delete() }
        }
    }
}
