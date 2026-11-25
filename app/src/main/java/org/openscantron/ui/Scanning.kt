package org.openscantron.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openscantron.archive.Photos
import org.openscantron.camera.Capture
import org.openscantron.data.*
import org.openscantron.domain.Grade
import org.openscantron.domain.SheetLayout
import org.openscantron.export.Statistics
import java.io.File
import kotlin.math.roundToInt

@Composable
fun ScanList(nav: NavHostController, model: ClassroomModel, exams: List<Examination>,
             keys: List<AnswerKeyVersion>, batches: List<ScanBatch>, scans: List<Submission>) {
    var chosen by rememberSaveable { mutableStateOf("") }
    var selectedKey by rememberSaveable { mutableStateOf("") }
    val active = exams.filterNot { it.archived }
    val exam = active.find { it.id == chosen } ?: active.firstOrNull()
    Screen("Scan batches") {
        if (exam == null) Text("Activate an examination and answer key to start scanning.")
        else {
            Picker("Exam", exam.title, active.map { it.id to it.title }) { chosen = it; selectedKey = "" }
            val history = keys.filter { it.examId == exam.id }
            val key = history.find { it.id == selectedKey } ?: history.find { it.id == exam.activeKeyId }
            if (key != null) {
                Picker("Key and template", "v${key.version} · ${key.templateId.take(8)}", history.map {
                    it.id to "v${it.version} · ${it.templateId.take(8)}" +
                        if (it.id == exam.activeKeyId) " · Active" else ""
                }) { selectedKey = it }
                Text("${key.answers.decode<List<String>>().size} questions. Match the template code on the printed PDF.")
                Button(onClick = { model.perform {
                    val id = model.repository.startBatch(exam.id, key.id)
                    nav.navigate("batch/$id")
                } }) { Text("Start batch") }
            }
        }
        batches.forEach { batch ->
            val name = exams.find { it.id == batch.examId }?.title ?: "Examination"
            val count = scans.count { it.batchId == batch.id }
            Tile(name, "$count scans · ${if (batch.closedAt == null) "Open" else "Closed"} · ${batch.startedAt.take(10)}",
                { nav.navigate("batch/${batch.id}") })
        }
    }
}

@Composable
fun BatchScreen(nav: NavHostController, model: ClassroomModel, batch: ScanBatch, exam: Examination?,
                key: AnswerKeyVersion?, scans: List<Submission>, archives: List<AnswerSheetArchive>,
                datasets: List<DatasetArchive>) {
    val context = LocalContext.current
    var camera by rememberSaveable { mutableStateOf(false) }
    var continuous by rememberSaveable { mutableStateOf(false) }
    val sheetLayout by loadState<SheetLayout?>(null, batch.templateId) {
        model.repository.layout(batch.templateId)
    }
    var closing by remember { mutableStateOf(false) }
    val rows = scans.filter { it.batchId == batch.id }.sortedBy { it.position }
    val included = rows.filter { it.inclusion == "INCLUDED" }
    val archiveCount = archives.count { a -> a.status == "READY" && included.any { it.id == a.submissionId } }
    val synchronized = if (datasets.find { it.examId == batch.examId }?.revision == exam?.revision)
        included.count { it.recognition == "READY" } else 0
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.perform {
            val id = model.repository.capture(batch.id) { requireNotNull(context.contentResolver.openInputStream(uri)) }
            if (!continuous) nav.navigate("review/$id")
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        camera = it
        if (!it) model.message = "Camera access was declined. You can still import a photo."
    }
    Screen(exam?.title ?: "Scan batch") {
        Text("Template ${batch.templateId.take(8)} · Key v${key?.version ?: "—"} · ${if (batch.closedAt == null) "Open batch" else "Closed batch"}")
        Tile("Batch progress", "${rows.size} scanned · ${rows.count { it.studentId != null }} identified · " +
            "${rows.count { it.studentId == null }} unassigned\n${rows.count { it.recognition == "ERROR" }} failed · " +
            "${included.count { !it.confirmed }} awaiting confirmation · ${included.count { it.confirmed }} confirmed\n" +
            "$archiveCount PDF-ready · $synchronized CSV-synchronized")
        if (batch.closedAt == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(continuous, { continuous = it })
                Text("Continuous scanning", Modifier.padding(top = 13.dp))
            }
            Actions {
                Button(onClick = {
                    if (camera) camera = false
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera = true
                    else permission.launch(Manifest.permission.CAMERA)
                }) { Text(if (camera) "Close camera" else "Open camera") }
                OutlinedButton(enabled = !model.busy, onClick = { import.launch(arrayOf("image/*")) }) { Text("Import photo") }
            }
            if (camera) {
                sheetLayout?.let { selectedLayout ->
                    CameraPanel(model, continuous, selectedLayout) { original ->
                        model.perform(acceptWhileBusy = true) {
                            try {
                                val id = model.repository.capture(batch.id, original::inputStream)
                                if (!continuous && nav.currentBackStackEntry?.arguments?.getString("id") == batch.id) {
                                    nav.navigate("review/$id")
                                }
                            } finally { original.delete() }
                        }
                    }
                }
            }
        }
        Actions {
            if (batch.closedAt == null) TextButton(onClick = { closing = true }) { Text("Close batch") }
            TextButton(enabled = included.isNotEmpty(), onClick = { model.perform {
                model.open(model.repository.batchPdf(batch.id, false), "application/pdf")
            } }) { Text("Batch PDF · scan order") }
            TextButton(enabled = included.isNotEmpty(), onClick = { model.perform {
                model.open(model.repository.batchPdf(batch.id, true), "application/pdf")
            } }) { Text("Batch PDF · student order") }
        }
        rows.forEach { s ->
            Tile("Sheet ${s.position}", status(s), { nav.navigate("review/${s.id}") })
        }
    }
    if (closing) Confirm("Close this batch?", "The batch will stop accepting scans. Existing sheets can still be reviewed and confirmed.",
        "Close batch", { closing = false }) { model.perform { model.repository.closeBatch(batch.id) } }
}

@Composable
private fun CameraPanel(model: ClassroomModel, continuous: Boolean, layout: SheetLayout, onPhoto: (File) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val preview = remember { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
    } }
    var quality by remember { mutableStateOf("Starting camera…") }
    var ready by remember { mutableStateOf(false) }
    var shutter by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(false) }
    var latched by remember { mutableStateOf(false) }
    var stable by remember { mutableIntStateOf(0) }
    val latestPhoto by rememberUpdatedState(onPhoto)
    var absent by remember { mutableIntStateOf(0) }
    val controller = remember(owner, preview, layout) { Capture(context, owner, preview, layout, { message, ok ->
        quality = message
        ready = ok
        if (ok) {
            stable = (stable + 1).coerceAtMost(4)
            absent = 0
        } else {
            stable = 0
            absent = (absent + 1).coerceAtMost(3)
            if (absent == 3) latched = false
        }
    }, { model.message = it }) }
    DisposableEffect(controller) {
        controller.start()
        onDispose(controller::close)
    }
    fun photo() {
        if (shutter || model.busy) return
        shutter = true
        latched = true
        controller.take({ latestPhoto(it) }, { shutter = false })
    }
    LaunchedEffect(auto, continuous, stable, latched, model.busy) {
        if (auto && continuous && stable >= 4 && !latched && !model.busy) photo()
    }
    Box(Modifier.fillMaxWidth().height(350.dp)) {
        AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val left = size.width * .16f
            val right = size.width * .84f
            val top = size.height * .11f
            val bottom = size.height * .89f
            val length = 25.dp.toPx()
            val tint = if (ready) Color(0xff9ce0ba) else Color.White
            listOf(Triple(Offset(left, top), 1, 1), Triple(Offset(right, top), -1, 1),
                Triple(Offset(right, bottom), -1, -1), Triple(Offset(left, bottom), 1, -1)).forEach { (p, x, y) ->
                drawLine(tint, p, p + Offset(x * length, 0f), 3.dp.toPx())
                drawLine(tint, p, p + Offset(0f, y * length), 3.dp.toPx())
            }
        }
    }
    Text(quality)
    if (continuous) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(auto, { auto = it; stable = 0; latched = false })
            Text(if (!auto) "Auto-capture off" else if (latched) "Remove the current sheet" else "Auto-capture armed",
                Modifier.padding(top = 12.dp))
        }
    }
    Button(enabled = !shutter && !model.busy, onClick = ::photo,
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text(if (shutter) "Capturing…" else "Capture sheet") }
    Actions {
        TextButton(onClick = controller::torch) { Text("Torch") }
        TextButton(onClick = controller::switch) { Text("Switch camera") }
    }
}

@Composable
fun ReviewScreen(model: ClassroomModel, s: Submission, students: List<Student>, archive: AnswerSheetArchive?) {
    val review by loadState<Review?>(null, s) { model.repository.review(s.id) }
    val data = review
    var question by rememberSaveable(s.id) { mutableIntStateOf(1) }
    var all by rememberSaveable(s.id) { mutableStateOf(false) }
    var originalShown by rememberSaveable(s.id) { mutableStateOf(false) }
    var archiveDelete by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var assignment by remember { mutableStateOf<Pair<String?, List<Submission>>?>(null) }
    val student = students.find { it.id == s.studentId }
    val grade = s.grade?.decode<Grade>()
    val photo by loadState<Bitmap?>(null, s.original, s.rotation, originalShown) {
        if (originalShown && s.original.isNotBlank()) withContext(Dispatchers.IO) {
            runCatching { Photos.oriented(model.repository.file(s.original), 1600, s.rotation) }.getOrNull()
        } else null
    }
    val correction = data?.corrections?.lastOrNull { it.question == question }
    val mark = data?.answers?.getOrNull(question - 1)
    var note by remember(s.id, question, correction) { mutableStateOf("") }
    val crop by loadState<Bitmap?>(null, s.rectified, question, data?.layout) {
        val path = s.rectified
        if (path != null && data != null) withContext(Dispatchers.IO) {
            val bitmap = BitmapFactory.decodeFile(model.repository.file(path).path) ?: return@withContext null
            val row = data.layout.questions.getOrNull(question - 1) ?: return@withContext null
            val x = (row.bubbles.first().center.x * bitmap.width - 30).roundToInt().coerceAtLeast(0)
            val end = (row.bubbles.last().center.x * bitmap.width + 20).roundToInt().coerceAtMost(bitmap.width)
            val y = (row.bubbles.first().center.y * bitmap.height - 15).roundToInt().coerceAtLeast(0)
            val raw = Bitmap.createBitmap(bitmap, x, y, end - x, minOf(30, bitmap.height - y))
            val result = Bitmap.createScaledBitmap(raw, raw.width * 3, raw.height * 3, true)
            if (raw !== result) raw.recycle()
            if (bitmap !== result && bitmap !== raw) bitmap.recycle()
            result
        } else null
    }
    Screen("Sheet ${s.position}") {
        Tile(student?.let { "${it.number} · ${it.name}" } ?: "Unassigned sheet", status(s)) {
            Text("Submission ${s.id.take(8)} · ${s.scannedAt.take(10)} · Key v${data?.keyVersion ?: "—"}")
            if (s.inclusion == "INCLUDED") TextButton(onClick = { discard = true }) { Text("Exclude attempt") }
            if (s.inclusion in setOf("INCLUDED", "DECISION_REQUIRED")) {
                Picker("Assign student", student?.name ?: "Select roster entry",
                    listOf("" to "Unassigned") + students.map { it.id to "${it.number} · ${it.name} · ${it.section}" }) { id ->
                    model.perform {
                        val selected = id.ifBlank { null }
                        val matches = model.repository.conflicts(s.id, selected)
                        if (matches.isEmpty()) model.repository.assign(s.id, selected)
                        else assignment = selected to matches
                    }
                }
            }
        }
        Tile("Original and archive", "PDF ${archive?.status?.lowercase() ?: "queued"}") {
            archive?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Actions {
                TextButton(enabled = s.original.isNotBlank(), onClick = { originalShown = !originalShown }) {
                    Text(if (originalShown) "Hide original" else "View original")
                }
                TextButton(enabled = archive?.file != null, onClick = { model.perform {
                    model.open(model.repository.verifiedArchive(s.id), "application/pdf")
                } }) { Text("Open PDF") }
                TextButton(enabled = s.original.isNotBlank(), onClick = { model.perform {
                    model.repository.retryArchive(s.id)
                } }) { Text("Retry PDF") }
                if (archive?.status == "READY" && s.original.isNotBlank() && s.recognition == "READY") {
                    TextButton(onClick = { archiveDelete = true }) { Text("Delete original") }
                }
            }
            photo?.let { ZoomImage(it, "Original captured answer sheet") }
            if (originalShown && photo == null) Text("Opening the original…")
        }
        if (s.recognition == "ERROR") Tile("Recognition needs attention", s.recognitionError.orEmpty()) {
            Actions {
                TextButton(onClick = { model.perform { model.repository.retryRecognition(s.id, false) } }) { Text("Retry recognition") }
                TextButton(onClick = { model.perform { model.repository.retryRecognition(s.id, true) } }) { Text("Rotate 90° and retry") }
            }
            Text("To retake, return to the batch and capture a new sheet.")
        }
        data?.identity?.let {
            Tile("Recognition quality", "Bubbled ID ${it.number ?: "unresolved"} · Sharpness ${Statistics.format(it.sharpness)}") {
                it.warnings.forEach { warning -> Text(warning) }
            }
        }
        if (grade != null) {
            Tile(if (s.confirmed) "Saved grade" else "Provisional grade", Statistics.score(grade)) {
                Text("${grade.correct} correct · ${grade.incorrect} incorrect · ${grade.blank} blank · " +
                    "${grade.invalid} invalid · ${grade.pending} pending")
                if (s.confirmed) Text(grade.passed?.let { if (it) "Pass" else "Fail" } ?: "Passing status unavailable")
                Button(enabled = s.inclusion == "INCLUDED" && s.studentId != null && grade.pending == 0 && !s.confirmed,
                    onClick = { model.perform { model.repository.confirm(s.id) } }) { Text("Confirm grade") }
            }
        }
        if (data != null && mark != null) {
            val finalAnswer = correction?.answer ?: mark.token()
            val alternatives = mark.candidates.joinToString(", ").ifBlank { "—" }
            Tile("Question ${question.toString().padStart(3, '0')}", "Machine: ${mark.token()} · Effective: $finalAnswer · Key: ${data.key[question - 1]}") {
                crop?.let { ZoomImage(it, "Rectified crop of question $question") }
                Text("${mark.classification.name.replace('_', ' ')} · Candidates $alternatives · Measured margin ${Statistics.format(mark.margin)}")
                Text(data.layout.labels.zip(mark.evidence).joinToString(" · ") { (label, value) -> "$label ${Statistics.format(value)}" })
                Picker("Question", question.toString(), data.answers.indices.map { (it + 1).toString() to "Q${it + 1}" }) { question = it.toInt() }
                if (s.inclusion == "INCLUDED") {
                    Field("Correction note (optional)", note, { note = it }, single = false)
                    Actions {
                        (data.layout.labels + listOf("BLANK", "INVALID")).forEach { answer ->
                            OutlinedButton(onClick = { model.perform {
                                model.repository.correct(s.id, question, answer, note)
                            } }) { Text(answer) }
                        }
                    }
                }
                val corrections = data.corrections.filter { it.question == question }
                corrections.forEach { event ->
                    Text("${event.time} · ${event.answer}${if (event.note.isNotBlank()) " · ${event.note}" else ""}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = { all = !all }) { Text(if (all) "Hide answer comparison" else "Show answer comparison") }
            if (all) data.answers.forEachIndexed { i, observation ->
                val effective = data.corrections.lastOrNull { it.question == i + 1 }?.answer ?: observation.token()
                Tile("Q${(i + 1).toString().padStart(3, '0')}", "$effective · Key ${data.key[i]}", { question = i + 1 })
            }
            if (data.grades.isNotEmpty()) Tile("Grade history", data.grades.joinToString("\n") {
                "${it.time.take(19)}Z · ${Statistics.score(it.grade.decode())}"
            })
            if (s.confirmed) OutlinedButton(onClick = { model.perform {
                val out = model.repository.exportFile("pdf", "RESULT_${s.id.take(8)}")
                withContext(Dispatchers.IO) {
                    org.openscantron.archive.Documents.report(out, "Individual result", listOf(
                        "${student?.number.orEmpty()}  ${student?.name.orEmpty()}",
                        "Submission ${s.id}", "Key version ${data.keyVersion}", Statistics.score(requireNotNull(grade))
                    ) + data.answers.mapIndexed { i, item ->
                        val answer = data.corrections.lastOrNull { it.question == i + 1 }?.answer ?: item.token()
                        "Q${i + 1}    Answer $answer    Key ${data.key[i]}"
                    })
                }
                model.open(out, "application/pdf")
            } }) { Text("Individual report PDF") }
        } else if (s.recognition == "QUEUED") LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (discard) Confirm("Exclude this attempt?", "Remove the attempt from grades and CSV records. Its original and correction history stay available in the batch and archive.",
        "Exclude attempt", { discard = false }) { model.perform { model.repository.assign(s.id, s.studentId, DuplicateChoice.DISCARD) } }
    if (archiveDelete) Confirm("Delete original photo?", "The verified PDF remains. The original photo is needed for recognition retry and new batch PDF generation.",
        "Delete original", { archiveDelete = false }) { model.perform { model.repository.deleteSource(s.id) } }
    assignment?.let { (person, matches) ->
        AlertDialog(onDismissRequest = { assignment = null }, title = { Text("Possible duplicate") },
            text = { Text("${matches.size} existing included sheet(s) match the student or original image. Replace keeps the predecessor in history. Retain keeps a separate attempt.") },
            confirmButton = {
                Column {
                    listOf("Replace predecessor" to DuplicateChoice.REPLACE, "Retain both attempts" to DuplicateChoice.RETAIN,
                        "Discard new attempt" to DuplicateChoice.DISCARD).forEach { (label, decision) ->
                        TextButton(onClick = { model.perform {
                            model.repository.assign(s.id, person, decision)
                            assignment = null
                        } }) { Text(label) }
                    }
                }
            }, dismissButton = { TextButton(onClick = { assignment = null }) { Text("Cancel") } })
    }
}
