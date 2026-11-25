package org.openscantron.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.openscantron.archive.Documents
import org.openscantron.archive.Photos
import org.openscantron.archive.publish
import org.openscantron.archive.sha256
import org.openscantron.domain.*
import org.openscantron.export.Csv
import org.openscantron.export.Datasets
import org.openscantron.export.Response
import org.openscantron.omr.Recognition
import org.openscantron.omr.Recognizer
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

val codec = Json { encodeDefaults = true }
fun newId(): String = UUID.randomUUID().toString()
fun now(): String = Instant.now().toString()
inline fun <reified T> String.decode(): T = codec.decodeFromString(this)
inline fun <reified T> T.encode(): String = codec.encodeToString(this)

data class ExamDraft(
    val title: String, val subject: String, val description: String, val date: String,
    val count: Int, val labels: List<String>, val paper: Paper, val digits: Int,
    val answers: List<String>, val policy: Policy
)

data class Review(
    val submission: Submission, val answers: List<Observation>, val corrections: List<AnswerCorrection>,
    val key: List<String>, val keyVersion: Int, val layout: SheetLayout, val policy: Policy,
    val identity: Recognition?, val grades: List<GradeHistory>
)

enum class DuplicateChoice { REPLACE, RETAIN, DISCARD }

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): ScanDatabase =
        Room.databaseBuilder(context, ScanDatabase::class.java, "openscantron.db")
            .addMigrations(archiveAttemptMigration, effectiveAnswersMigration).build()
}

@dagger.hilt.EntryPoint
@InstallIn(SingletonComponent::class)
interface RepositoryEntry { fun repository(): Repository }

class VaultWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(applicationContext, RepositoryEntry::class.java).repository()
        return try {
            repository.reconcile()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

@Singleton
class Repository @Inject constructor(@ApplicationContext private val context: Context, private val db: ScanDatabase, private val preferences: Preferences) {
    val records = db.records()
    internal val root = File(context.filesDir, "vault").canonicalFile.apply { mkdirs() }
    private val gate = Mutex()
    private val recognizer = Recognizer()

    fun file(path: String): File {
        val candidate = File(root, path).canonicalFile
        require(candidate.toPath().startsWith(root.toPath()) && candidate != root)
        return candidate
    }

    fun exportFile(extension: String, prefix: String): File =
        File(File(context.cacheDir, "exports/${newId()}").apply { mkdirs() }, "$prefix.$extension")

    fun schedule() {
        WorkManager.getInstance(context).enqueueUniqueWork("vault",
            ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<VaultWorker>().build())
    }

    private suspend fun <T> exclusive(action: suspend () -> T): T =
        withContext(Dispatchers.IO) { gate.withLock { action() } }

    internal suspend fun <T> atomic(action: suspend () -> T): T = exclusive { db.withTransaction { action() } }

    private suspend fun <T> change(action: suspend () -> T): T =
        atomic(action).also { schedule() }

    private suspend fun touch(examId: String) {
        records.exam(examId)?.let { records.save(it.copy(revision = it.revision + 1, changedAt = now())) }
    }

    suspend fun saveExam(id: String?, draft: ExamDraft): String = change {
        require(draft.title.isNotBlank()) { "Enter an exam title." }
        if (draft.date.isNotBlank()) requireNotNull(runCatching { LocalDate.parse(draft.date) }.getOrNull()) {
            "Use an ISO date (YYYY-MM-DD)."
        }
        require(draft.count == draft.answers.size && draft.answers.all { it in draft.labels }) {
            "Every question needs one answer from the configured labels."
        }
        draft.policy.validate(draft.count)
        val previous = id?.let { requireNotNull(records.exam(it)) }
        val examId = previous?.id ?: newId()
        val proposed = SheetLayout(newId(), examId, draft.count, draft.labels, draft.paper, draft.digits)
        val oldLayout = previous?.let { layout(it.templateId) }
        val layout = oldLayout?.takeIf { it == proposed.copy(id = it.id) } ?: proposed
        if (layout.id != oldLayout?.id) records.add(AnswerSheetTemplate(layout.id, examId, layout.encode()))
        val oldKey = previous?.let { records.key(it.activeKeyId) }
        val key = if (oldKey?.templateId == layout.id && oldKey.answers == draft.answers.encode() &&
            oldKey.policy == draft.policy.encode()) oldKey else {
            AnswerKeyVersion(newId(), examId, layout.id, (oldKey?.version ?: 0) + 1,
                draft.answers.encode(), draft.policy.encode(), now()).also { records.add(it) }
        }
        records.save(Examination(examId, draft.title.trim(), draft.subject.trim(), draft.description.trim(),
            draft.date, layout.id, key.id, (previous?.revision ?: 0) + 1, now(), previous?.archived ?: false))
        if (previous == null) records.save(DatasetArchive(examId))
        examId
    }

    suspend fun examDraft(id: String): ExamDraft {
        val exam = requireNotNull(records.exam(id))
        val key = requireNotNull(records.key(exam.activeKeyId))
        val layout = layout(exam.templateId)
        return ExamDraft(exam.title, exam.subject, exam.description, exam.date, layout.count,
            layout.labels, layout.paper, layout.digits, key.answers.decode(), key.policy.decode())
    }

    suspend fun archiveExam(id: String, archived: Boolean) = atomic {
        records.exam(id)?.let { records.save(it.copy(archived = archived)) }
    }

    suspend fun deleteExam(id: String) {
        val paths = atomic {
            val dataset = records.dataset(id)
            val batches = records.batches().filter { it.examId == id }.map { it.id }.toSet()
            val documents = records.batchDocuments().filter { it.batchId in batches }
            val submissions = records.submissionsFor(id)
            val files = submissions.flatMap { s ->
                val archive = records.archive(s.id)
                records.deleteObservations(s.id)
                records.deleteCorrections(s.id)
                records.deleteGrades(s.id)
                listOfNotNull(s.original.ifBlank { null }, s.rectified, archive?.file)
            } + documents.map { it.file } + listOfNotNull(dataset?.wideFile, dataset?.auditFile)
            batches.forEach { records.deleteBatchDocuments(it) }
            records.deleteArchives(id)
            records.deleteDataset(id)
            records.deleteSubmissions(id)
            records.deleteBatches(id)
            records.deleteKeys(id)
            records.deleteTemplates(id)
            records.deleteExam(id)
            files
        }
        withContext(Dispatchers.IO) { paths.forEach { file(it).delete() } }
    }

    suspend fun saveStudents(students: List<Student>) = change {
        require(students.map { it.number }.distinct().size == students.size) { "The import contains duplicate IDs." }
        val exams = mutableSetOf<String>()
        students.forEach { student ->
            require(student.name.isNotBlank() && student.number.isNotBlank()) { "Student number and name are required." }
            val other = records.studentByNumber(student.number)
            require(other == null || other.id == student.id) { "Student number ${student.number} is already in the roster." }
            records.save(student)
        }
        val ids = students.map { it.id }.toSet()
        records.submissions().filter { it.studentId in ids }.forEach { exams += it.examId }
        exams.forEach { touch(it) }
    }

    suspend fun deleteStudent(id: String) = change {
        val affected = records.submissions().filter { it.studentId == id }
        affected.forEach { s ->
            records.save(s.copy(studentId = null, confirmed = false, effective = false))
            records.archive(s.id)?.let {
                records.save(it.copy(studentId = null, status = if (s.original.isBlank()) it.status else "QUEUED"))
            }
            touch(s.examId)
        }
        records.deleteStudent(id)
    }

    suspend fun startBatch(examId: String, keyId: String? = null): String = atomic {
        val exam = requireNotNull(records.exam(examId))
        require(!exam.archived) { "Restore the exam before starting another batch." }
        val key = requireNotNull(records.key(keyId ?: exam.activeKeyId))
        require(key.examId == exam.id) { "Choose a key from the selected exam." }
        ScanBatch(newId(), exam.id, key.templateId, key.id, now()).also { records.save(it) }.id
    }

    suspend fun closeBatch(id: String) = atomic {
        val batch = requireNotNull(records.batch(id))
        records.save(batch.copy(closedAt = now()))
    }

    suspend fun capture(batchId: String, open: () -> InputStream): String = exclusive {
        val batch = requireNotNull(records.batch(batchId))
        require(batch.closedAt == null) { "This batch is closed. Start a new batch." }
        val id = newId()
        val relative = "originals/$id.img"
        val target = file(relative)
        val hash = try {
            publish(target) { temporary ->
                open().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            total += size
                            require(total <= 40L * 1024 * 1024) { "Image exceeds 40 MB. Choose a smaller original." }
                            output.write(buffer, 0, size)
                        }
                        require(total > 0) { "The selected image is empty." }
                    }
                }
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(temporary.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Select a supported photo, such as a JPEG or PNG." }
            }
            sha256(target)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        try {
            db.withTransaction {
                val position = (records.inBatch(batchId).lastOrNull()?.position ?: 0) + 1
                records.save(Submission(id, batch.examId, batch.id, position, batch.templateId, batch.keyId,
                    batch.keyId, now(), relative, hash))
                records.save(AnswerSheetArchive(id, batch.examId, null, relative))
            }
        } catch (e: Exception) {
            if (e !is CancellationException) target.delete()
            throw e
        }
        schedule()
        id
    }

    suspend fun layout(id: String): SheetLayout = requireNotNull(records.template(id)).definition.decode()

    suspend fun review(id: String): Review = db.withTransaction {
        val s = requireNotNull(records.submission(id))
        val key = requireNotNull(records.key(s.gradedKeyId))
        Review(s, records.observations(id).map { it.evidence.decode() }, records.corrections(id),
            key.answers.decode(), key.version, layout(s.templateId), key.policy.decode(),
            s.identityEvidence?.decode<Recognition>(), records.grades(id))
    }

    private suspend fun grade(s: Submission): Submission {
        val key = requireNotNull(records.key(s.gradedKeyId))
        val corrections = records.corrections(s.id).associate { it.question to it.answer }
        val observations = records.observations(s.id).map { it.evidence.decode<Observation>() }
        val result = Grader.grade(key.answers.decode(), key.policy.decode(), observations, corrections)
        val effectiveAnswers = observations.mapIndexed { index, observation ->
            Grader.effective(observation, corrections[index + 1])
        }
        return s.copy(grade = result.encode(), effectiveAnswers = effectiveAnswers.encode(),
            confirmed = s.confirmed && result.pending == 0)
    }

    private suspend fun remember(s: Submission) {
        s.grade?.let { records.add(GradeHistory(newId(), s.id, s.gradedKeyId, it, now())) }
    }

    private suspend fun chooseEffective(examId: String, studentId: String?) {
        if (studentId == null) return
        val group = records.submissionsFor(examId).filter { it.studentId == studentId }
        val chosen = group.filter { it.inclusion == "INCLUDED" }.let { candidates ->
            candidates.firstOrNull { it.effective && it.confirmed } ?: candidates.firstOrNull { it.confirmed }
                ?: candidates.firstOrNull { it.effective } ?: candidates.firstOrNull()
        }
        group.forEach { s -> if (s.effective != (s.id == chosen?.id)) records.save(s.copy(effective = s.id == chosen?.id)) }
    }

    suspend fun correct(id: String, question: Int, answer: String, note: String) = change {
        val s = requireNotNull(records.submission(id))
        require(s.recognition == "READY" && s.inclusion == "INCLUDED") { "Resolve inclusion and recognition first." }
        val l = layout(s.templateId)
        require(question in 1..l.count && answer in l.labels + listOf("BLANK", "INVALID"))
        records.add(AnswerCorrection(newId(), id, question, s.examId, s.studentId, answer, note, now()))
        val changed = grade(s)
        records.save(changed)
        if (changed.confirmed) remember(changed)
        chooseEffective(s.examId, s.studentId)
        touch(s.examId)
    }

    private suspend fun duplicates(s: Submission, studentId: String?) = records.submissionsFor(s.examId)
        .filter { it.id != s.id && it.inclusion == "INCLUDED" &&
            ((studentId != null && it.studentId == studentId) || it.sourceHash == s.sourceHash) }

    suspend fun conflicts(id: String, studentId: String?): List<Submission> =
        duplicates(requireNotNull(records.submission(id)), studentId)

    suspend fun assign(id: String, studentId: String?, decision: DuplicateChoice? = null) = change {
        val s = requireNotNull(records.submission(id))
        if (studentId != null) requireNotNull(records.student(studentId))
        val matches = duplicates(s, studentId)
        if (matches.isNotEmpty()) require(decision != null) { "Choose how to handle the duplicate first." }
        val changed = when (decision) {
            DuplicateChoice.DISCARD -> s.copy(inclusion = "DISCARDED", effective = false, confirmed = false)
            DuplicateChoice.REPLACE -> {
                val predecessor = matches.firstOrNull { it.studentId == studentId && it.effective }
                    ?: matches.firstOrNull { it.studentId == studentId } ?: matches.firstOrNull()
                predecessor?.let { records.save(it.copy(inclusion = "SUPERSEDED", effective = false)) }
                s.copy(studentId = studentId, inclusion = "INCLUDED", effective = false, confirmed = false)
            }
            else -> s.copy(studentId = studentId, inclusion = "INCLUDED", effective = false,
                confirmed = s.confirmed && studentId != null)
        }
        records.save(changed)
        if (changed.studentId != s.studentId) records.archive(id)?.let {
            records.save(it.copy(studentId = changed.studentId, status = if (s.original.isBlank()) it.status else "QUEUED"))
        }
        (matches.map { it.studentId } + listOf(s.studentId, studentId)).distinct().forEach { chooseEffective(s.examId, it) }
        touch(s.examId)
    }

    suspend fun confirm(id: String) = change {
        val s = requireNotNull(records.submission(id))
        require(s.inclusion == "INCLUDED" && s.studentId != null &&
            s.grade?.decode<Grade>()?.pending == 0) { "Assign a student and finish mark and duplicate review first." }
        val confirmed = s.copy(confirmed = true)
        records.save(confirmed)
        if (!s.confirmed) remember(confirmed)
        chooseEffective(s.examId, s.studentId)
        touch(s.examId)
    }

    suspend fun regradeCandidates(examId: String): List<Submission> {
        val exam = requireNotNull(records.exam(examId))
        return records.submissionsFor(examId).filter {
            it.recognition == "READY" && it.inclusion == "INCLUDED" &&
                it.templateId == exam.templateId && it.gradedKeyId != exam.activeKeyId
        }
    }

    suspend fun regrade(examId: String, expectedKey: String) = change {
        val exam = requireNotNull(records.exam(examId))
        require(exam.activeKeyId == expectedKey) { "The active key changed. Review the updated regrade scope." }
        regradeCandidates(examId).forEach { s ->
            val changed = grade(s.copy(gradedKeyId = exam.activeKeyId))
            records.save(changed)
            remember(changed)
            chooseEffective(s.examId, s.studentId)
        }
        touch(examId)
    }

    suspend fun retryArchive(id: String) = change {
        val archive = requireNotNull(records.archive(id))
        require(archive.source.isNotBlank() && file(archive.source).isFile) { "The original image is unavailable. Restore it from backup." }
        records.save(archive.copy(status = "QUEUED", error = null))
    }

    suspend fun retryRecognition(id: String, rotate: Boolean) = change {
        val s = requireNotNull(records.submission(id))
        require(s.recognition != "READY" && s.original.isNotBlank()) { "Recorded machine observations are preserved." }
        val rotation = if (rotate) (s.rotation + 90) % 360 else s.rotation
        records.save(s.copy(recognition = "QUEUED", recognitionError = null, rotation = rotation))
        if (rotate) records.archive(id)?.let { records.save(it.copy(status = "QUEUED")) }
    }

    suspend fun regenerate(examId: String) = change {
        records.dataset(examId)?.let { records.save(it.copy(error = null)) }
        touch(examId)
    }

    suspend fun verifiedArchive(id: String): File = withContext(Dispatchers.IO) {
        val archive = requireNotNull(records.archive(id))
        val path = requireNotNull(archive.file) { "Wait for the PDF or retry it from the archive." }
        file(path).also {
            require(it.isFile && sha256(it) == archive.checksum && Documents.pageCount(it) == 1) {
                "PDF is missing or damaged. Retry from the original sheet."
            }
        }
    }

    suspend fun deleteSource(id: String) = exclusive {
        verifiedArchive(id)
        val s = requireNotNull(records.submission(id))
        require(s.recognition == "READY") { "Resolve recognition before deleting the original." }
        val archive = requireNotNull(records.archive(id))
        db.withTransaction {
            records.save(s.copy(original = ""))
            records.save(archive.copy(source = ""))
        }
        if (s.original.isNotBlank()) file(s.original).delete()
    }

    suspend fun templatePdf(examId: String, copies: Int, studentId: String?): File = withContext(Dispatchers.IO) {
        require(copies in 1..50) { "Choose 1–50 copies." }
        val exam = requireNotNull(records.exam(examId))
        exportFile("pdf", "SHEET_${exam.id.take(8)}").also {
            Documents.template(it, layout(exam.templateId), exam.title, copies, studentId, exam.date)
        }
    }

    suspend fun batchPdf(batchId: String, sortByNumber: Boolean): File = exclusive {
        val batch = requireNotNull(records.batch(batchId))
        val students = records.students().associateBy { it.id }
        val scans = records.inBatch(batchId).filter { it.inclusion == "INCLUDED" }
            .let { if (sortByNumber) it.sortedBy { s -> students[s.studentId]?.number ?: "~" } else it }
        require(scans.isNotEmpty()) { "This batch has no included scans." }
        require(scans.all { it.original.isNotBlank() && file(it.original).isFile }) {
            "One or more originals are unavailable. Restore them before building a batch PDF."
        }
        val id = newId()
        val relative = "published/batch/BATCH_$id.pdf"
        val target = file(relative)
        Documents.originals(target, layout(batch.templateId).paper, scans.map { file(it.original) to it.rotation }, 1600)
        records.add(BatchDocument(id, batchId, relative, scans.map { it.id }.encode(), target.length(), sha256(target), now()))
        target
    }

    suspend fun responses(examId: String): List<Response> = db.withTransaction {
        val students = records.students().associateBy { it.id }
        records.submissionsFor(examId).mapNotNull { s ->
            if (s.recognition != "READY" || s.inclusion != "INCLUDED" || s.grade == null) return@mapNotNull null
            val key = requireNotNull(records.key(s.gradedKeyId))
            val observations = records.observations(s.id).map { it.evidence.decode<Observation>() }
            val corrections = records.corrections(s.id).associateBy { it.question }
            val effective = s.effectiveAnswers?.decode<List<String>>() ?: observations.mapIndexed { index, observation ->
                Grader.effective(observation, corrections[index + 1]?.answer)
            }
            Response(s, students[s.studentId], key, observations, corrections, s.grade.decode(), key.policy.decode(), effective)
        }
    }

    suspend fun consolidated(): File = exclusive {
        val exams = records.exams()
        val groups = exams.map { it to responses(it.id) }
        val counts = exams.map { layout(it.templateId).count }
        val width = maxOf(counts.maxOrNull() ?: 0, groups.flatMap { it.second }.maxOfOrNull { it.answers.size } ?: 0)
        exportFile("csv", "ALL_EXAMS").also { out ->
            publish(out) { temp -> temp.bufferedWriter().use { writer ->
                Csv.write(writer, sequence {
                    yield(Datasets.wideHeader(width))
                    groups.forEach { (exam, rows) -> rows.forEach { yield(Datasets.wide(exam, it, width)) } }
                })
            } }
        }
    }

    suspend fun reconcile() = exclusive {
        val queued = records.submissions().filter { it.recognition == "QUEUED" }
        for (archive in records.archives().filter { it.status == "QUEUED" }) runArchive(archive)
        for (s in queued) runRecognition(s)
        for (exam in records.exams()) {
            val archive = records.dataset(exam.id) ?: DatasetArchive(exam.id)
            if (archive.revision != exam.revision && archive.error == null) runDataset(exam, archive)
        }
        pruneUnreferencedFiles()
    }

    private suspend fun pruneUnreferencedFiles() {
        val saved = buildSet {
            records.submissions().forEach {
                add(it.original)
                it.rectified?.let(::add)
            }
            records.archives().mapNotNullTo(this) { it.file }
            records.datasets().forEach {
                it.wideFile?.let(::add)
                it.auditFile?.let(::add)
            }
            records.batchDocuments().mapTo(this) { it.file }
        }
        for (folder in listOf("originals", "rectified", "published")) {
            file(folder).walkBottomUp().forEach { candidate ->
                if (candidate.isFile && candidate.relativeTo(root).invariantSeparatorsPath !in saved) candidate.delete()
                if (candidate.isDirectory && candidate.list()?.isEmpty() == true) candidate.delete()
            }
        }
        root.listFiles()?.filter { it.isDirectory && it.name.startsWith(".restore-") }
            ?.forEach { it.deleteRecursively() }
    }

    private suspend fun runArchive(archive: AnswerSheetArchive) {
        val s = requireNotNull(records.submission(archive.submissionId))
        val attempted = archive.copy(attemptedAt = now())
        val part = archive.studentId?.let { records.student(it)?.number }
            ?.replace(Regex("[^A-Za-z0-9_-]"), "_")?.take(40)?.ifBlank { null }
        val label = part?.let { "STUDENT_${it}_" }.orEmpty()
        val relative = "published/individual/EXAM_${s.examId}_${label}SUBMISSION_${s.id}.pdf"
        try {
            val target = file(relative)
            Documents.originals(target, layout(s.templateId).paper, listOf(file(s.original) to s.rotation), preferences.resolution.first())
            records.save(attempted.copy(file = relative, bytes = target.length(), checksum = sha256(target),
                generatedAt = now(), status = "READY", error = null))
            archive.file?.takeIf { it != relative }?.let { file(it).delete() }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            records.save(attempted.copy(status = "ERROR",
                error = "PDF could not be saved. Free storage or restore the original, then retry."))
        }
    }

    private suspend fun runRecognition(s: Submission) {
        val layout = layout(s.templateId)
        val rectified = "rectified/${s.id}.jpg"
        try {
            val source = Photos.oriented(file(s.original), extraRotation = s.rotation)
            val read = try {
                recognizer.read(source, layout) { image ->
                    publish(file(rectified)) { temp -> temp.outputStream().use {
                        check(image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it))
                    } }
                }
            } finally { source.recycle() }
            db.withTransaction {
                val fromNumber = read.number?.let { records.studentByNumber(it) }
                val fromQr = read.reference?.let { records.student(it) }
                val conflict = fromNumber != null && fromQr != null && fromNumber.id != fromQr.id ||
                    (read.number != null && fromQr != null && read.number != fromQr.number)
                val student = if (conflict) null else fromNumber ?: fromQr
                val matched = read.copy(warnings = read.warnings + buildList {
                    if (conflict) add("Bubbled number and QR disagree. Assign the student manually.")
                    if (read.number != null && fromNumber == null) add("Bubbled number is absent from the roster.")
                    if (read.reference != null && fromQr == null) add("Personalized QR is absent from the roster.")
                })
                val duplicate = duplicates(s, student?.id).isNotEmpty()
                read.answers.forEachIndexed { i, observation ->
                    records.add(QuestionRecognition(s.id, i + 1, observation.encode()))
                }
                val result = grade(s.copy(recognition = "READY", recognitionError = null,
                    rectified = rectified, identityEvidence = matched.encode(), studentId = student?.id,
                    inclusion = if (duplicate) "DECISION_REQUIRED" else "INCLUDED"))
                records.save(result)
                chooseEffective(s.examId, student?.id)
                if (student != null) records.archive(s.id)?.let {
                    records.save(it.copy(studentId = student.id, status = "QUEUED"))
                }
                touch(s.examId)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val detail = if (e is IllegalArgumentException) e.message else null
            records.save(s.copy(recognition = "ERROR", recognitionError = detail
                ?: "Recognition failed. Check storage, then retry or capture another photo."))
            return
        }
        records.archive(s.id)?.takeIf { it.status == "QUEUED" }?.let { runArchive(it) }
    }

    private suspend fun runDataset(exam: Examination, archive: DatasetArchive) {
        val attempted = archive.copy(attemptedAt = now())
        val rows = responses(exam.id)
        val width = maxOf(layout(exam.templateId).count, rows.maxOfOrNull { it.answers.size } ?: 0)
        val directory = "published/datasets/${exam.id}/${exam.revision}"
        val wide = "$directory/ANSWERS_${exam.id}.csv"
        val audit = "$directory/AUDIT_${exam.id}.csv"
        try {
            publish(file(wide)) { temp -> temp.bufferedWriter().use { writer ->
                Csv.write(writer, sequenceOf(Datasets.wideHeader(width)) + rows.asSequence().map { Datasets.wide(exam, it, width) })
            } }
            publish(file(audit)) { temp -> temp.bufferedWriter().use { writer ->
                Csv.write(writer, sequenceOf(Datasets.auditHeader) + rows.asSequence().flatMap(Datasets::audit))
            } }
            if (records.exam(exam.id)?.revision == exam.revision) {
                records.save(attempted.copy(revision = exam.revision, generation = archive.generation + 1,
                    wideFile = wide, auditFile = audit, records = rows.size,
                    bytes = file(wide).length() + file(audit).length(),
                    wideHash = sha256(file(wide)), auditHash = sha256(file(audit)), generatedAt = now(), error = null))
                listOfNotNull(archive.wideFile, archive.auditFile).filter { it != wide && it != audit }
                    .forEach { file(it).delete() }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            records.save(attempted.copy(error = "CSV publication failed. Free storage and choose Regenerate."))
        }
    }

    suspend fun exportTo(source: File, uri: Uri) = withContext(Dispatchers.IO) {
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
            source.inputStream().use { it.copyTo(output) }
        }
    }
}
