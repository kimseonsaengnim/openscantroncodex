package org.openscantron.data

import kotlinx.serialization.Serializable
import org.openscantron.archive.Documents
import org.openscantron.archive.publish
import org.openscantron.archive.sha256
import org.openscantron.export.Csv
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

@Serializable
data class BackupManifest(
    val version: Int, val createdAt: String, val includesOriginals: Boolean, val files: Map<String, String>,
    val exams: List<Examination>, val students: List<Student>, val templates: List<AnswerSheetTemplate>,
    val keys: List<AnswerKeyVersion>, val batches: List<ScanBatch>, val submissions: List<Submission>,
    val observations: List<QuestionRecognition>, val corrections: List<AnswerCorrection>,
    val history: List<GradeHistory>, val archives: List<AnswerSheetArchive>, val datasets: List<DatasetArchive>,
    val batchDocuments: List<BatchDocument>
)

class Backup @Inject constructor(private val repository: Repository) {
    private val records = repository.records

    suspend fun write(includeOriginals: Boolean): File = repository.atomic {
        val submissions = records.submissions()
        val archives = records.archives()
        val datasets = records.datasets()
        val batch = records.batchDocuments()
        val paths = paths(submissions, archives, datasets, batch, includeOriginals)
        val hashes = paths.associateWith {
            val file = repository.file(it)
            require(file.isFile) { "A referenced file is missing. Regenerate the affected file before backing up." }
            sha256(file)
        }
        val manifest = BackupManifest(1, now(), includeOriginals, hashes,
            records.exams(), records.students(), records.templates(), records.keys(), records.batches(),
            submissions.map { if (includeOriginals) it else it.copy(original = "") },
            submissions.flatMap { records.observations(it.id) }, submissions.flatMap { records.corrections(it.id) },
            submissions.flatMap { records.grades(it.id) },
            archives.map { if (includeOriginals) it else it.copy(source = "") }, datasets, batch)
        val out = repository.exportFile("zip", "OPENSCANTRON_BACKUP")
        publish(out) { temp ->
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.encode().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                hashes.keys.sorted().forEach { path ->
                    zip.putNextEntry(ZipEntry("files/$path"))
                    repository.file(path).inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        out
    }

    suspend fun restore(open: () -> InputStream): Boolean = repository.atomic {
        require(records.exams().isEmpty() && records.students().isEmpty() && records.submissions().isEmpty()) {
            "Restore is available on an empty installation. Export a backup of this device before changing it."
        }
        val stage = File(repository.root, ".restore-${newId()}")
        require(stage.mkdir())
        val created = mutableListOf<File>()
        try {
            val manifest = stageBackup(open, stage)
            validate(manifest)
            manifest.files.forEach { (path, expected) ->
                val staged = File(stage, path)
                require(staged.isFile && sha256(staged) == expected) { "Backup checksum mismatch." }
                when {
                    path.endsWith(".pdf") -> require(Documents.pageCount(staged) > 0)
                    path.endsWith(".csv") -> staged.bufferedReader().use { require(Csv.read(it).isNotEmpty()) }
                }
                val target = repository.file(path)
                if (!target.exists()) {
                    publish(target) { staged.copyTo(it, overwrite = true) }
                    created += target
                } else require(sha256(target) == expected) { "A local file differs from the backup. Restore into a fresh installation." }
            }
            manifest.exams.forEach { records.save(it) }
            manifest.students.forEach { records.save(it) }
            manifest.templates.forEach { records.add(it) }
            manifest.keys.forEach { records.add(it) }
            manifest.batches.forEach { records.save(it) }
            val observations = manifest.observations.groupBy { it.submissionId }
            val corrections = manifest.corrections.groupBy { it.submissionId }
            manifest.submissions.forEach { submitted ->
                val items = observations[submitted.id].orEmpty().sortedBy { it.question }
                val manual = corrections[submitted.id].orEmpty().associateBy { it.question }
                val effective = items.map { item ->
                    manual[item.question]?.answer ?: item.evidence.decode<org.openscantron.domain.Observation>().token()
                }
                require(submitted.effectiveAnswers == null || submitted.effectiveAnswers.decode<List<String>>() == effective) {
                    "Saved effective answers disagree with the correction history."
                }
                records.save(submitted.copy(effectiveAnswers = effective.takeIf { it.isNotEmpty() }?.encode()))
            }
            manifest.observations.forEach { records.add(it) }
            manifest.corrections.forEach { records.add(it) }
            manifest.history.forEach { records.add(it) }
            manifest.archives.forEach { records.save(it) }
            manifest.datasets.forEach { records.save(it) }
            manifest.batchDocuments.forEach { records.add(it) }
            manifest.includesOriginals
        } catch (e: Exception) {
            created.forEach(File::delete)
            throw e
        } finally {
            stage.deleteRecursively()
        }
    }.also { repository.schedule() }

    private fun stageBackup(open: () -> InputStream, stage: File): BackupManifest {
        var total = 0L
        ZipInputStream(open().buffered()).use { zip ->
            val first = zip.nextEntry
            require(first?.name == "manifest.json") { "Choose an OpenScantron backup ZIP." }
            val manifestBytes = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(65536)
            while (true) {
                val count = zip.read(chunk)
                if (count < 0) break
                require(manifestBytes.size() + count <= 16 * 1024 * 1024) { "Backup manifest is too large." }
                manifestBytes.write(chunk, 0, count)
            }
            val bytes = manifestBytes.toByteArray()
            val manifest = bytes.toString(Charsets.UTF_8).decode<BackupManifest>()
            require(manifest.version == 1) { "This backup version is unsupported." }
            val encountered = mutableSetOf<String>()
            val buffer = ByteArray(65536)
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name.removePrefix("files/")
                require(entry.name == "files/$path" && path in manifest.files &&
                    path.matches(Regex("(originals|rectified|published)/[A-Za-z0-9_./-]+")) &&
                    path.split('/').none { it == ".." || it == "." || it.isEmpty() } && encountered.add(path)) {
                    "The backup contains an unexpected file path."
                }
                val destination = File(stage, path)
                destination.parentFile?.mkdirs()
                destination.outputStream().use { output ->
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 4L * 1024 * 1024 * 1024) { "Backup exceeds the 4 GB restore limit." }
                        output.write(buffer, 0, count)
                    }
                }
                zip.closeEntry()
            }
            require(encountered == manifest.files.keys) { "The backup is incomplete." }
            return manifest
        }
    }

    private fun validate(m: BackupManifest) {
        val exams = m.exams.associateBy { it.id }
        val students = m.students.associateBy { it.id }
        val templates = m.templates.associateBy { it.id }
        val keys = m.keys.associateBy { it.id }
        val batches = m.batches.associateBy { it.id }
        val submissions = m.submissions.associateBy { it.id }
        require(listOf(exams.size, students.size, templates.size, keys.size, batches.size, submissions.size) ==
            listOf(m.exams.size, m.students.size, m.templates.size, m.keys.size, m.batches.size, m.submissions.size))
        require(m.exams.all { exam ->
            templates[exam.templateId]?.examId == exam.id &&
                keys[exam.activeKeyId]?.let { it.examId == exam.id && it.templateId == exam.templateId } == true
        })
        require(m.templates.all { t ->
            val layout = t.definition.decode<org.openscantron.domain.SheetLayout>()
            t.examId in exams && layout.id == t.id && layout.examId == t.examId
        })
        require(m.keys.all { key ->
            val layout = requireNotNull(templates[key.templateId]).definition.decode<org.openscantron.domain.SheetLayout>()
            val labels = key.answers.decode<List<String>>()
            key.examId == layout.examId && labels.size == layout.count && labels.all { it in layout.labels } &&
                runCatching { key.policy.decode<org.openscantron.domain.Policy>().validate(layout.count) }.isSuccess
        })
        require(m.batches.all { b ->
            b.examId in exams && keys[b.keyId]?.let { it.examId == b.examId && it.templateId == b.templateId } == true
        })
        require(m.submissions.all { s ->
            batches[s.batchId]?.let { it.examId == s.examId && it.templateId == s.templateId && it.keyId == s.keyId } == true &&
                keys[s.gradedKeyId]?.let { it.examId == s.examId && it.templateId == s.templateId } == true &&
                (s.studentId == null || s.studentId in students) &&
                (s.original.isBlank() || m.files[s.original] == s.sourceHash)
        })
        require(m.observations.all { it.submissionId in submissions } &&
            m.corrections.all { submissions[it.submissionId]?.examId == it.examId } &&
            m.history.all { it.submissionId in submissions && it.keyId in keys })
        require(m.archives.all {
            submissions[it.submissionId]?.examId == it.examId && (it.file == null || m.files[it.file] == it.checksum)
        } && m.datasets.all {
            it.examId in exams && (it.wideFile == null || m.files[it.wideFile] == it.wideHash) &&
                (it.auditFile == null || m.files[it.auditFile] == it.auditHash)
        } && m.batchDocuments.all {
            it.batchId in batches && m.files[it.file] == it.checksum &&
                it.submissionIds.decode<List<String>>().all { id -> submissions[id]?.batchId == it.batchId }
        })
        require(paths(m.submissions, m.archives, m.datasets, m.batchDocuments, m.includesOriginals) == m.files.keys)
    }

    private fun paths(submissions: List<Submission>, archives: List<AnswerSheetArchive>,
                      datasets: List<DatasetArchive>, batches: List<BatchDocument>, originals: Boolean) =
        (submissions.flatMap { listOfNotNull(it.rectified, it.original.takeIf { path -> originals && path.isNotBlank() }) } +
            archives.mapNotNull { it.file } + datasets.flatMap { listOfNotNull(it.wideFile, it.auditFile) } +
            batches.map { it.file }).toSet()
}
