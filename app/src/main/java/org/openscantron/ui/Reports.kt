package org.openscantron.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openscantron.archive.Documents
import org.openscantron.archive.publish
import org.openscantron.archive.sha256
import org.openscantron.data.*
import org.openscantron.domain.Grade
import org.openscantron.export.Csv
import org.openscantron.export.Response
import org.openscantron.export.Statistics
import java.io.File

private fun storage(bytes: Long) = java.lang.String.format(java.util.Locale.ROOT, "%.2f MB", bytes / 1_048_576.0)

@Composable
fun Archives(nav: NavHostController, model: ClassroomModel, exams: List<Examination>, students: List<Student>,
             scans: List<Submission>, archives: List<AnswerSheetArchive>) {
    var query by rememberSaveable { mutableStateOf("") }
    var examId by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf("") }
    var batch by rememberSaveable { mutableStateOf("") }
    val batchFiles by loadState(emptyList<BatchDocument>(), scans, archives) {
        model.repository.records.batchDocuments()
    }
    val selected = archives.filter { a ->
        val s = scans.find { it.id == a.submissionId }
        val student = students.find { it.id == a.studentId }
        val exam = exams.find { it.id == a.examId }
        (examId.isBlank() || a.examId == examId) && (batch.isBlank() || s?.batchId == batch) &&
            (date.isBlank() || s?.scannedAt?.startsWith(date) == true) &&
            (query.isBlank() || listOfNotNull(student?.name, student?.number, exam?.title, s?.id).any { it.contains(query, true) })
    }.sortedByDescending { a -> scans.find { it.id == a.submissionId }?.scannedAt }
    Screen("PDF archive") {
        Tile("Private PDFs", "${archives.count { it.file != null }} individual · ${batchFiles.size} batch · " +
            storage(archives.sumOf { it.bytes } + batchFiles.sumOf { it.bytes }))
        Field("Search student, exam, or submission ID", query, { query = it })
        Field("Capture date prefix (YYYY-MM-DD)", date, { date = it })
        Actions {
            Picker("Exam", exams.find { it.id == examId }?.title ?: "All",
                listOf("" to "All exams") + exams.map { it.id to it.title }) { examId = it }
            Picker("Batch", batch.take(8).ifBlank { "All" },
                listOf("" to "All batches") + scans.map { it.batchId }.distinct().map { it to it.take(8) }) { batch = it }
        }
        if (selected.isEmpty()) Text("No matching individual archives.")
        selected.forEach { a ->
            val s = scans.find { it.id == a.submissionId }
            val student = students.find { it.id == a.studentId }
            val title = student?.let { "${it.number} · ${it.name}" } ?: "Unassigned · ${a.submissionId.take(8)}"
            Tile(title, "${exams.find { it.id == a.examId }?.title.orEmpty()} · ${s?.scannedAt?.take(10).orEmpty()} · " +
                "${a.status.lowercase()} · ${storage(a.bytes)}") {
                a.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Actions {
                    TextButton(enabled = a.file != null, onClick = { model.perform {
                        model.open(model.repository.verifiedArchive(a.submissionId), "application/pdf")
                    } }) { Text("Preview PDF") }
                    TextButton(onClick = { nav.navigate("review/${a.submissionId}") }) { Text("Open submission") }
                    if (a.source.isNotBlank()) TextButton(onClick = { model.perform {
                        model.repository.retryArchive(a.submissionId)
                    } }) { Text("Retry PDF") }
                }
            }
        }
        val batchToExam = scans.associate { it.batchId to it.examId }
        batchFiles.filter {
            (batch.isBlank() || it.batchId == batch) && (examId.isBlank() || batchToExam[it.batchId] == examId) &&
                (date.isBlank() || it.generatedAt.startsWith(date))
        }.forEach { archive ->
            Tile("Batch ${archive.batchId.take(8)}", "${archive.submissionIds.decode<List<String>>().size} pages · ${storage(archive.bytes)}") {
                TextButton(onClick = { model.perform {
                    val file = model.repository.file(archive.file)
                    withContext(Dispatchers.IO) {
                        require(file.isFile && sha256(file) == archive.checksum && Documents.pageCount(file) > 0) {
                            "The batch PDF is missing or damaged. Rebuild it from the batch."
                        }
                    }
                    model.open(file, "application/pdf")
                } }) { Text("Preview batch PDF") }
            }
        }
    }
}

@Composable
fun DatasetScreen(nav: NavHostController, model: ClassroomModel, exams: List<Examination>, scans: List<Submission>,
                  students: List<Student>, datasets: List<DatasetArchive>) {
    var chosen by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    val exam = exams.find { it.id == chosen } ?: exams.firstOrNull()
    val dataset = datasets.find { it.examId == exam?.id }
    val rows by loadState(emptyList<Response>(), exam, scans, students) {
        exam?.let { withContext(Dispatchers.IO) { model.repository.responses(it.id) } } ?: emptyList()
    }
    val tableWidth by loadState(0, exam, rows) {
        exam?.let { maxOf(model.repository.layout(it.templateId).count,
            rows.maxOfOrNull { r -> r.answers.size } ?: 0) } ?: 0
    }
    Screen("CSV datasets") {
        if (exam == null) Text("Examination datasets appear when an exam is created.")
        else {
            Picker("Exam", exam.title, exams.map { it.id to it.title }) { chosen = it }
            val current = dataset?.revision == exam.revision
            Tile(if (current) "Synchronized" else "Publication pending",
                "${dataset?.records ?: 0} saved rows · $tableWidth questions · ${rows.count { !it.final }} provisional\n" +
                    "Revision ${dataset?.revision ?: 0} / ${exam.revision} · Generation ${dataset?.generation ?: 0} · " +
                    "${storage(dataset?.bytes ?: 0L)}") {
                Text("Last publication: ${dataset?.generatedAt ?: "Pending"}")
                Text("Last exam change: ${exam.changedAt}")
                dataset?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Actions {
                    TextButton(enabled = dataset?.wideFile != null, onClick = { model.perform {
                        model.open(checkedCsv(model, dataset?.wideFile, dataset?.wideHash), "text/csv")
                    } }) { Text("Answers CSV") }
                    TextButton(enabled = dataset?.auditFile != null, onClick = { model.perform {
                        model.open(checkedCsv(model, dataset?.auditFile, dataset?.auditHash), "text/csv")
                    } }) { Text("Audit CSV") }
                    TextButton(onClick = { model.perform { model.repository.regenerate(exam.id) } }) { Text("Regenerate") }
                }
            }
            Field("Find records by student or submission", query, { query = it })
            Picker("Status", filter, listOf("All", "Final", "Provisional").map { it to it }) { filter = it }
            val visible = rows.filter {
                matches(it, query) && (filter == "All" || (filter == "Final") == it.final)
            }
            if (visible.isEmpty()) Text("No matching response rows.")
            visible.forEach { row ->
                Tile(row.student?.let { "${it.number} · ${it.name}" } ?: "Unassigned · ${row.submission.id.take(8)}",
                    "${if (row.final) "Final" else "Provisional"} · ${row.grade.pending} pending\n" +
                        row.answers.indices.joinToString(" ") { row.answer(it) },
                    { nav.navigate("review/${row.submission.id}") })
            }
            OutlinedButton(onClick = { model.perform {
                model.open(model.repository.consolidated(), "text/csv")
            } }) { Text("Export all exams") }
        }
    }
}

private suspend fun checkedCsv(model: ClassroomModel, path: String?, checksum: String?): File =
    withContext(Dispatchers.IO) {
        model.repository.file(requireNotNull(path)).also { file ->
            require(file.isFile && sha256(file) == checksum) { "CSV is missing or damaged. Regenerate the dataset." }
        }
    }

private fun matches(row: Response, query: String) = listOf(row.student?.number, row.student?.name,
    row.student?.section, row.submission.id).any { it?.contains(query, true) == true }

@Composable
fun Results(nav: NavHostController, model: ClassroomModel, exams: List<Examination>, scans: List<Submission>,
            students: List<Student>, archives: List<AnswerSheetArchive>, datasets: List<DatasetArchive>,
            keys: List<AnswerKeyVersion>) {
    var chosen by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf("") }
    var order by rememberSaveable { mutableStateOf("Newest") }
    var finalOnly by rememberSaveable { mutableStateOf(false) }
    var showItems by rememberSaveable { mutableStateOf(false) }
    val exam = exams.find { it.id == chosen } ?: exams.firstOrNull()
    val rows by loadState(emptyList<Response>(), exam, scans, students) {
        exam?.let { withContext(Dispatchers.IO) { model.repository.responses(it.id) } } ?: emptyList()
    }
    val group = rows.filter { section.isBlank() || it.student?.section == section }
    val summary = remember(group, keys) { Statistics.compute(group, keys.associate { it.id to it.answers.decode() }) }
    val possible = keys.find { it.id == exam?.activeKeyId }?.let { key ->
        val policy = key.policy.decode<org.openscantron.domain.Policy>()
        policy.correct * if (policy.weights.isEmpty()) key.answers.decode<List<String>>().size.toDouble() else policy.weights.sum()
    }
    val rate = (summary.passed + summary.failed).takeIf { it > 0 }?.let { 100.0 * summary.passed / it }
    val pdfs = archives.count { it.examId == exam?.id && it.status == "READY" }
    val csv = datasets.find { it.examId == exam?.id }?.records ?: 0
    Screen("Results & reports") {
        if (exam == null) Text("Create and scan an exam to view results.")
        else {
            Picker("Exam", exam.title, exams.map { it.id to it.title }) { chosen = it; section = "" }
            val sections = rows.mapNotNull { it.student?.section?.ifBlank { null } }.distinct().sorted()
            Picker("Section", section.ifBlank { "All" }, listOf("" to "All sections") + sections.map { it to it }) { section = it }
            Tile("Summary", "${summary.final.size} final effective attempts · ${summary.provisional} provisional\n" +
                "Active maximum ${Statistics.format(possible)} · Mean ${Statistics.format(summary.mean)} · Median ${Statistics.format(summary.median)}\n" +
                "High ${Statistics.format(summary.maximum)} · Low ${Statistics.format(summary.minimum)}\n" +
                "${summary.passed} passed · ${summary.failed} failed · ${Statistics.format(rate)}% pass rate\n" +
                "${group.sumOf { it.grade.pending }} unresolved marks · $pdfs exam PDFs · $csv exam CSV records") {
                Actions {
                    TextButton(onClick = { model.perform {
                        val file = model.repository.exportFile("pdf", "REPORT_${exam.id.take(8)}")
                        withContext(Dispatchers.IO) {
                            Documents.report(file, exam.title, listOf(
                                "Section: ${section.ifBlank { "All" }} · ${summary.final.size} final results",
                                "Mean ${Statistics.format(summary.mean)}   Median ${Statistics.format(summary.median)}",
                                "${summary.passed} passed   ${summary.failed} failed   ${summary.provisional} provisional",
                                "Student number   Name   Section   Score"
                            ) + summary.final.map {
                                "${it.student?.number.orEmpty()}   ${it.student?.name.orEmpty()}   " +
                                    "${it.student?.section.orEmpty()}   ${Statistics.score(it.grade)}"
                            })
                        }
                        model.open(file, "application/pdf")
                    } }) { Text("Report PDF") }
                    TextButton(onClick = { model.perform {
                        val file = model.repository.exportFile("csv", "RESULTS_${exam.id.take(8)}")
                        withContext(Dispatchers.IO) {
                            publish(file) { temp -> temp.bufferedWriter().use { writer ->
                                Csv.write(writer, sequenceOf(listOf("exam_id", "section", "effective_final", "provisional",
                                    "mean", "median", "low", "high", "passed", "failed", "pass_rate")) + sequenceOf(listOf(
                                    exam.id, section, summary.final.size.toString(), summary.provisional.toString(),
                                    summary.mean?.toString().orEmpty(), summary.median?.toString().orEmpty(),
                                    summary.minimum?.toString().orEmpty(), summary.maximum?.toString().orEmpty(),
                                    summary.passed.toString(), summary.failed.toString(), rate?.toString().orEmpty())))
                            } }
                        }
                        model.open(file, "text/csv")
                    } }) { Text("Summary CSV") }
                }
            }
            TextButton(onClick = { showItems = !showItems }) { Text(if (showItems) "Hide item analysis" else "Show item analysis") }
            if (showItems) {
                if (summary.items.isEmpty()) Text("Item analysis requires at least one confirmed student attempt.")
                summary.items.forEach { item ->
                    fun pct(n: Int) = Statistics.format(100.0 * n / item.total)
                    Tile("Question ${item.question}", "${pct(item.correct)}% correct · ${pct(item.incorrect)}% incorrect · " +
                        "${pct(item.blank)}% blank · ${pct(item.invalid)}% invalid\n" +
                        "Difficulty ${Statistics.format(item.difficulty)} · Distractor ${item.distractor ?: "—"} · n=${item.total}\n" +
                        item.choices.entries.joinToString(" · ") { "${it.key} ${it.value}" })
                }
            }
            Field("Search results", query, { query = it })
            Picker("Sort", order, listOf("Newest", "Student ID", "Score").map { it to it }) { order = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(finalOnly, { finalOnly = it })
                Text("Final attempts only", Modifier.padding(top = 12.dp))
            }
            val visible = group.filter { matches(it, query) && (!finalOnly || it.final) }.let { found ->
                when (order) {
                    "Student ID" -> found.sortedBy { it.student?.number ?: "~" }
                    "Score" -> found.sortedByDescending { if (it.final) it.grade.score else Double.NEGATIVE_INFINITY }
                    else -> found.sortedByDescending { it.submission.scannedAt }
                }
            }
            if (visible.isEmpty()) Text("No results match these filters.")
            visible.forEach { row ->
                Tile(row.student?.let { "${it.number} · ${it.name}" } ?: "Unassigned",
                    "${Statistics.score(row.grade)} · ${if (row.final) "Final" else "Provisional"} · " +
                        if (row.submission.effective) "Statistics attempt" else "Other attempt",
                    { nav.navigate("review/${row.submission.id}") })
            }
        }
    }
}

@Composable
fun Settings(model: ClassroomModel, theme: String) {
    val context = LocalContext.current
    val resolution by model.preferences.resolution.collectAsStateWithLifecycle(3000)
    val exams by model.exams.collectAsStateWithLifecycle(emptyList())
    val scans by model.submissions.collectAsStateWithLifecycle(emptyList())
    val archives by model.archives.collectAsStateWithLifecycle(emptyList())
    val datasets by model.datasets.collectAsStateWithLifecycle(emptyList())
    val totals by loadState(emptyMap<String, Long>(), scans, archives, datasets) {
        withContext(Dispatchers.IO) {
            val batches = model.repository.records.batches().associate { it.id to it.examId }
            val documents = model.repository.records.batchDocuments()
            exams.associate { e ->
                val scanBytes = scans.filter { it.examId == e.id }.sumOf { s ->
                    listOfNotNull(s.original.ifBlank { null }, s.rectified).sumOf { model.repository.file(it).length() }
                }
                e.id to (scanBytes + archives.filter { it.examId == e.id }.sumOf { it.bytes } +
                    (datasets.find { it.examId == e.id }?.bytes ?: 0) +
                    documents.filter { batches[it.batchId] == e.id }.sumOf { it.bytes })
            }
        }
    }
    var originals by rememberSaveable { mutableStateOf(true) }
    var restoreConfirm by remember { mutableStateOf(false) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.perform {
            val included = model.backup.restore { requireNotNull(context.contentResolver.openInputStream(uri)) }
            model.message = if (included) "Backup restored with its original photos." else "Backup restored without original photos."
        }
    }
    Screen("Settings") {
        Picker("Appearance", theme, listOf("System", "Light", "Dark").map { it to it }) {
            model.perform { model.preferences.theme(it) }
        }
        Tile("Archive storage", "${storage(totals.values.sum())} referenced files") {
            exams.forEach { exam -> Text("${exam.title} · ${storage(totals[exam.id] ?: 0)}") }
            Picker("New PDF image resolution", "$resolution px",
                listOf(1600, 2200, 3000).map { it.toString() to "$it px" }) {
                model.perform { model.preferences.resolution(it.toInt()) }
            }
            Text("Delete a source photo from its submission after verifying the PDF.")
        }
        Tile("Backup & restore", "Backups contain student data. Save them to a location you control.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(originals, { originals = it })
                Text("Include original photos", Modifier.padding(top = 12.dp))
            }
            Actions {
                Button(onClick = { model.perform { model.open(model.backup.write(originals), "application/zip") } }) {
                    Text("Create backup")
                }
                OutlinedButton(onClick = { restoreConfirm = true }) { Text("Restore backup") }
            }
            Text("Restore validates the backup and is available on an empty installation.")
        }
    }
    if (restoreConfirm) Confirm("Restore backup?", "Choose an OpenScantron ZIP. The restore checks its manifest, relationships, and file checksums.",
        "Choose backup", { restoreConfirm = false }) { restore.launch(arrayOf("application/zip", "application/octet-stream")) }
}
