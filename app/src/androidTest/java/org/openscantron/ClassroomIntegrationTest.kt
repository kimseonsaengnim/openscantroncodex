package org.openscantron

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.openscantron.archive.Documents
import org.openscantron.archive.sha256
import org.openscantron.data.*
import org.openscantron.domain.*
import org.openscantron.export.Csv
import java.io.File

@RunWith(AndroidJUnit4::class)
class ClassroomIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val layoutLabels = listOf("A", "B", "C", "D")
    private val key = "A B C D A B C D A B".split(" ")

    @Test fun printedPdfToDurableGradeAuditArchiveAndRestore() = runBlocking {
        val tag = newId()
        val probe = File(context.cacheDir, "integration-$tag").apply { mkdirs() }
        val dbName = "integration-$tag.db"
        fun openDb() = Room.databaseBuilder(context, ScanDatabase::class.java, dbName)
            .addMigrations(archiveAttemptMigration, effectiveAnswersMigration).build()
        fun scoped(name: String) = object : ContextWrapper(context) {
            override fun getFilesDir() = File(probe, name).apply { mkdirs() }
        }
        var database = openDb()
        val scope = scoped("source")
        var repo = Repository(scope, database, Preferences(context))
        val restoredDb = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build()
        try {
            val examDraft = ExamDraft("Fictional ten-question quiz", "Math", "", "2026-10-15",
                10, layoutLabels, Paper.A4, 4, key, Policy())
            val examId = repo.saveExam(null, examDraft)
            val first = Student(newId(), "0017", "Fictional student 17", "Red")
            val second = Student(newId(), "0018", "Fictional student 18", "Blue")
            repo.saveStudents(listOf(first, second))
            repo.reconcile()
            assertEquals(1, wide(repo, examId).size)
            val exam = requireNotNull(repo.records.exam(examId))
            val layout = repo.layout(exam.templateId)
            val print = repo.templatePdf(examId, 1, null)
            assertEquals(1, Documents.pageCount(print))
            val image = File(probe, "0017.png")
            fill(print, image, layout, first.number)
            val batch = repo.startBatch(examId)
            val id = repo.capture(batch, image::inputStream)
            database.close()
            database = openDb()
            repo = Repository(scope, database, Preferences(context))
            assertEquals("QUEUED", repo.records.submission(id)?.recognition)
            val started = android.os.SystemClock.elapsedRealtime()
            repo.reconcile()
            val firstScanMs = android.os.SystemClock.elapsedRealtime() - started

            val initial = repo.review(id)
            assertEquals(initial.submission.recognitionError, "READY", initial.submission.recognition)
            assertEquals("0017", initial.identity?.number)
            assertEquals(first.id, initial.submission.studentId)
            assertEquals("A B C A A B C BLANK MULTIPLE AMBIGUOUS".split(" "), initial.answers.map { it.token() })
            assertEquals(2, initial.submission.grade?.decode<Grade>()?.pending)
            val pdf = repo.verifiedArchive(id)
            assertEquals(1, Documents.pageCount(pdf))
            val preview = Documents.preview(pdf)
            assertTrue(preview.width >= 1000)
            assertTrue(preview.height >= 1500)
            preview.recycle()
            assertEquals("PROVISIONAL", wide(repo, examId).last().last())
            repo.correct(id, 8, "BLANK", "")
            repo.correct(id, 9, "INVALID", "Two filled choices")
            repo.correct(id, 10, "B", "Checked faint mark")
            repo.confirm(id)
            repo.reconcile()
            assertEquals(Grade(7, 1, 1, 1, 0, 7.0, 10.0, 70.0, true, GradeState.FINAL),
                repo.records.submission(id)?.grade?.decode<Grade>())
            assertEquals(listOf("BLANK", "INVALID", "B"), wide(repo, examId).last().subList(18, 21))
            assertEquals(listOf("BLANK", "INVALID", "B"),
                repo.review(id).submission.effectiveAnswers?.decode<List<String>>()?.takeLast(3))
            val audit = audit(repo, examId)
            assertEquals(11, audit.size)
            assertEquals(listOf("MULTIPLE_MARKS", "AMBIGUOUS"), audit.takeLast(2).map { it[12] })
            val checksum = sha256(pdf)
            val arguments = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            if (arguments.getString("retainFixture") == "true") {
                val evidence = arguments.getString("additionalTestOutputDir")?.let(::File)
                    ?: File(context.filesDir, "test-fixture")
                evidence.mkdirs()
                print.copyTo(File(evidence, "blank.pdf"), overwrite = true)
                image.copyTo(File(evidence, "0017.png"), overwrite = true)
                pdf.copyTo(File(evidence, "archive.pdf"), overwrite = true)
                repo.file(requireNotNull(repo.records.dataset(examId)?.wideFile)).copyTo(
                    File(evidence, "answers.csv"), overwrite = true)
                repo.file(requireNotNull(repo.records.dataset(examId)?.auditFile)).copyTo(
                    File(evidence, "audit.csv"), overwrite = true)
                val report = File(evidence, "performance.pdf")
                Documents.report(report, "Fictional result report", listOf("Student 0017", "Score 7 / 10"))
                File(evidence, "timing.txt").writeText("Single-image reconcile including PDF, CV, and CSV: $firstScanMs ms\n")
            }

            val revised = key.toMutableList().apply { this[3] = "A" }
            repo.saveExam(examId, examDraft.copy(answers = revised))
            assertEquals(7.0, repo.review(id).submission.grade?.decode<Grade>()?.score)
            assertEquals(listOf(id), repo.regradeCandidates(examId).map { it.id })
            repo.regrade(examId, requireNotNull(repo.records.exam(examId)).activeKeyId)
            repo.reconcile()
            assertEquals(8.0, repo.review(id).submission.grade?.decode<Grade>()?.score)
            assertEquals(2, repo.review(id).keyVersion)
            assertEquals(checksum, sha256(repo.verifiedArchive(id)))
            assertEquals(2, wide(repo, examId).size)
            repo.assign(id, second.id)
            repeat(2) {
                repo.retryArchive(id)
                repo.regenerate(examId)
                repo.reconcile()
            }
            assertEquals(second.number, wide(repo, examId).last()[5])
            assertEquals(2, wide(repo, examId).size)
            assertEquals(second.id, repo.records.archive(id)?.studentId)
            database.close()
            database = openDb()
            repo = Repository(scope, database, Preferences(context))
            assertEquals(id, repo.review(id).submission.id)
            assertEquals(3, repo.review(id).corrections.size)
            assertEquals(1, Documents.pageCount(repo.verifiedArchive(id)))

            val duplicateBatch = repo.startBatch(examId)
            val a = repo.capture(duplicateBatch, image::inputStream)
            repo.reconcile()
            assertEquals("DECISION_REQUIRED", repo.review(a).submission.inclusion)
            repo.assign(a, first.id, DuplicateChoice.RETAIN)
            val b = repo.capture(duplicateBatch, image::inputStream)
            repo.reconcile()
            repo.assign(b, first.id, DuplicateChoice.DISCARD)
            val c = repo.capture(duplicateBatch, image::inputStream)
            repo.reconcile()
            repo.assign(c, first.id, DuplicateChoice.REPLACE)
            assertEquals("SUPERSEDED", repo.review(a).submission.inclusion)
            assertEquals("DISCARDED", repo.review(b).submission.inclusion)
            val photo = File(probe, "0018.png")
            fill(print, photo, layout, second.number)
            val d = repo.capture(duplicateBatch, photo::inputStream)
            repo.reconcile()
            repo.assign(d, second.id, DuplicateChoice.RETAIN)
            repo.reconcile()
            val batchPdf = repo.batchPdf(duplicateBatch, false)
            assertEquals(2, Documents.pageCount(batchPdf))
            assertEquals(listOf(c, d), repo.records.batchDocuments().single().submissionIds.decode<List<String>>())
            val includedIds = wide(repo, examId).drop(1).map { it.first() }
            assertEquals(3, includedIds.size)
            assertEquals(setOf(id, c, d), includedIds.toSet())

            val backup = Backup(repo).write(true)
            val target = Repository(scoped("restored"), restoredDb, Preferences(context))
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { Backup(target).restore { "not a backup".byteInputStream() } }
            }
            assertTrue(target.records.exams().isEmpty())
            assertTrue(Backup(target).restore(backup::inputStream))
            assertEquals(id, target.review(id).submission.id)
            assertEquals(wide(repo, examId), wide(target, examId))
            assertEquals(sha256(repo.verifiedArchive(id)), sha256(target.verifiedArchive(id)))
            assertEquals(listOf(c, d), target.records.batchDocuments().single().submissionIds.decode<List<String>>())
            val source = target.review(id).submission.original
            target.deleteSource(id)
            assertFalse(target.file(source).exists())
            assertEquals(1, Documents.pageCount(target.verifiedArchive(id)))
        } finally {
            database.close()
            restoredDb.close()
            context.deleteDatabase(dbName)
            probe.deleteRecursively()
        }
    }

    @Test fun rendersLetterAndVerifiesOrderedImagePdf() {
        val directory = File(context.cacheDir, newId()).apply { mkdirs() }
        try {
            val layout = SheetLayout(newId(), newId(), 100, listOf("A", "B", "C", "D", "E", "F"), Paper.LETTER, 8)
            val document = File(directory, "letter.pdf")
            Documents.template(document, layout, "100 questions", 2)
            assertEquals(2, Documents.pageCount(document))
            Documents.preview(document).let { bitmap ->
                assertEquals(1224, bitmap.width)
                assertEquals(1584, bitmap.height)
                bitmap.recycle()
            }
            val first = File(directory, "red.png")
            val second = File(directory, "blue.png")
            listOf(first to Color.RED, second to Color.BLUE).forEach { (file, color) ->
                val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(color)
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }
            val archive = File(directory, "batch.pdf")
            Documents.originals(archive, Paper.LETTER, listOf(first to 0, second to 0))
            assertEquals(2, Documents.pageCount(archive))
            listOf(0, 1).forEach { page ->
                Documents.preview(archive, page).let { bitmap ->
                    val color = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    assertTrue(if (page == 0) Color.red(color) >= 235 else Color.blue(color) >= 235)
                    assertTrue(Color.red(bitmap.getPixel(1, 1)) >= 245)
                    bitmap.recycle()
                }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun publicationFailurePreservesRowsAndAllowsRetry() = runBlocking {
        val directory = File(context.cacheDir, newId()).apply { mkdirs() }
        val scoped = object : ContextWrapper(context) {
            override fun getFilesDir() = directory
        }
        val database = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build()
        val repo = Repository(scoped, database, Preferences(context))
        try {
            val exam = repo.saveExam(null, ExamDraft("Retry test", "", "", "", 10,
                layoutLabels, Paper.A4, 4, key, Policy()))
            repo.reconcile()
            val good = requireNotNull(repo.records.dataset(exam))
            val next = "published/datasets/$exam/${good.revision + 1}"
            repo.file(next).writeText("This file blocks the new revision directory.")
            repo.regenerate(exam)
            repo.reconcile()
            val failure = requireNotNull(repo.records.dataset(exam))
            assertNotNull(failure.error)
            assertEquals(good.revision, failure.revision)
            assertEquals(good.wideHash, sha256(repo.file(requireNotNull(good.wideFile))))
            repo.regenerate(exam)
            repo.reconcile()
            assertNull(repo.records.dataset(exam)?.error)
            assertEquals(1, wide(repo, exam).size)

            val layout = repo.layout(requireNotNull(repo.records.exam(exam)).templateId)
            val image = File(directory, "photo.png")
            fill(repo.templatePdf(exam, 1, null), image, layout, "0017")
            val batch = repo.startBatch(exam)
            val id = repo.capture(batch, image::inputStream)
            val blocker = repo.file("published/individual")
            blocker.writeText("This file blocks the archive directory.")
            repo.reconcile()
            assertEquals("ERROR", repo.records.archive(id)?.status)
            assertEquals("READY", repo.records.submission(id)?.recognition)
            assertTrue(repo.file(requireNotNull(repo.records.submission(id)?.original)).isFile)
            repo.retryArchive(id)
            repo.reconcile()
            val valid = repo.verifiedArchive(id)
            valid.appendText("unexpected bytes")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.verifiedArchive(id) } }
            repo.retryArchive(id)
            repo.reconcile()
            assertEquals(1, Documents.pageCount(repo.verifiedArchive(id)))
            assertEquals(2, wide(repo, exam).size)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    private suspend fun wide(repo: Repository, examId: String) =
        repo.file(requireNotNull(repo.records.dataset(examId)?.wideFile)).bufferedReader().use(Csv::read)
    private suspend fun audit(repo: Repository, examId: String) =
        repo.file(requireNotNull(repo.records.dataset(examId)?.auditFile)).bufferedReader().use(Csv::read)

    private fun fill(print: File, target: File, layout: SheetLayout, id: String) {
        val bitmap = Documents.preview(print)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            fun mark(bubble: Bubble, tone: Int) {
                paint.color = Color.rgb(tone, tone, tone)
                canvas.drawCircle((bubble.center.x * bitmap.width).toFloat(), (bubble.center.y * bitmap.height).toFloat(),
                    (bubble.radius * bitmap.width * .90).toFloat(), paint)
            }
            val marks = listOf("A", "B", "C", "A", "A", "B", "C", "", "AD", "B")
            layout.questions.forEachIndexed { index, row ->
                row.bubbles.forEachIndexed { choice, bubble ->
                    if (layout.labels[choice] in marks[index]) mark(bubble, if (index == 9) 184 else 24)
                }
            }
            layout.identifier.forEachIndexed { digit, cells -> mark(cells[id[digit].digitToInt()], 24) }
            target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
