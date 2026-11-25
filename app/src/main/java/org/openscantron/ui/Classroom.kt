package org.openscantron.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openscantron.archive.Documents
import org.openscantron.data.*
import org.openscantron.domain.Grade
import java.io.File
import javax.inject.Inject

private val Light = lightColorScheme(primary = Color(0xFF205E50), secondary = Color(0xFF47635C),
    tertiary = Color(0xFF81541C), surface = Color(0xFFF6F8F5), background = Color(0xFFF6F8F5))
private val Dark = darkColorScheme(primary = Color(0xFF9CD9C6), secondary = Color(0xFFB0CCC2),
    tertiary = Color(0xFFF5BE7C))

@HiltViewModel
class ClassroomModel @Inject constructor(val repository: Repository, val preferences: Preferences,
                                         val backup: Backup) : ViewModel() {
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set
    var document by mutableStateOf<Pair<File, String>?>(null)

    private val records = repository.records
    val exams = observe(records.examsFlow())
    val students = observe(records.studentsFlow())
    val submissions = observe(records.submissionsFlow())
    val archives = observe(records.archivesFlow())
    val datasets = observe(records.datasetsFlow())
    val batches = observe(records.batchesFlow())
    val keys = observe(records.keysFlow())

    private fun <T> observe(flow: Flow<T>): Flow<T> = flow.catch {
        message = "The local database could not be opened. Keep the installation and check free storage before restarting."
    }

    private val operations = Mutex()
    private var pendingOperations = 0

    fun perform(acceptWhileBusy: Boolean = false, operation: suspend () -> Unit) {
        if (busy && !acceptWhileBusy) return
        pendingOperations++
        busy = true
        viewModelScope.launch {
            try { operations.withLock { operation() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                message = when (e) {
                    is IllegalArgumentException -> e.message ?: "The operation could not be completed."
                    is android.database.sqlite.SQLiteException -> "The local database is unavailable. Restart and check free storage."
                    else -> "Could not complete the operation. Check storage and permissions, then retry."
                }
            } finally {
                pendingOperations--
                busy = pendingOperations > 0
            }
        }
    }

    fun open(file: File, mime: String) { document = file to mime }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Classroom(model: ClassroomModel) {
    val theme by model.preferences.theme.collectAsStateWithLifecycle("System")
    val isDark = theme == "Dark" || theme == "System" && isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (isDark) Dark else Light) {
        val nav = rememberNavController()
        val exams by model.exams.collectAsStateWithLifecycle(emptyList())
        val students by model.students.collectAsStateWithLifecycle(emptyList())
        val scans by model.submissions.collectAsStateWithLifecycle(emptyList())
        val archives by model.archives.collectAsStateWithLifecycle(emptyList())
        val datasets by model.datasets.collectAsStateWithLifecycle(emptyList())
        val batches by model.batches.collectAsStateWithLifecycle(emptyList())
        val keys by model.keys.collectAsStateWithLifecycle(emptyList())
        val notice = remember { SnackbarHostState() }
        LaunchedEffect(model.message) {
            model.message?.let {
                notice.showSnackbar(it, duration = SnackbarDuration.Short)
                if (model.message == it) model.message = null
            }
        }
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("OpenScantron") }, navigationIcon = {
                    val entry by nav.currentBackStackEntryFlow.collectAsState(initial = null)
                    if (entry?.destination?.route != "home") TextButton(onClick = { nav.popBackStack() }) { Text("Back") }
                })
            },
            snackbarHost = { SnackbarHost(notice) }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                NavHost(nav, "home", Modifier.weight(1f)) {
                    composable("home") { Dashboard(nav, exams, students, scans, archives, datasets) }
                    composable("exams") { Exams(nav, model, exams, keys, scans) }
                    composable("edit/{id}") {
                        ExamEditor(nav, model, it.argument("id").takeUnless { id -> id == "new" })
                    }
                    composable("roster") { Roster(model, students) }
                    composable("templates") { Templates(model, exams, students) }
                    composable("scan") { ScanList(nav, model, exams, keys, batches, scans) }
                    composable("batch/{id}") { entry ->
                        val batch = batches.find { it.id == entry.argument("id") }
                        if (batch != null) BatchScreen(nav, model, batch, exams.find { it.id == batch.examId },
                            keys.find { it.id == batch.keyId }, scans, archives, datasets)
                    }
                    composable("review/{id}") { entry ->
                        val s = scans.find { it.id == entry.argument("id") }
                        if (s != null) ReviewScreen(model, s, students, archives.find { it.submissionId == s.id })
                        else Screen("Submission") { Text("No saved submission is available.") }
                    }
                    composable("archive") { Archives(nav, model, exams, students, scans, archives) }
                    composable("datasets") { DatasetScreen(nav, model, exams, scans, students, datasets) }
                    composable("results") { Results(nav, model, exams, scans, students, archives, datasets, keys) }
                    composable("settings") { Settings(model, theme) }
                }
            }
        }
        model.document?.let { (file, mime) ->
            DocumentDialog(model, file, mime) { model.document = null }
        }
    }
}

private fun androidx.navigation.NavBackStackEntry.argument(key: String) = requireNotNull(arguments?.getString(key))

@Composable
fun Screen(title: String, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 36.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium)
                    content()
                }
            }
        }
    }
}

@Composable
fun Tile(title: String, detail: String, click: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit = {}) {
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium)
            content()
        }
    }
    if (click == null) Card(Modifier.fillMaxWidth(), content = { body() })
    else Card(onClick = click, modifier = Modifier.fillMaxWidth(), content = { body() })
}

@Composable
fun Confirm(title: String, prompt: String, action: String, close: () -> Unit, accept: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Text(prompt) },
        confirmButton = { TextButton(onClick = { close(); accept() }) { Text(action) } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Actions(content: @Composable () -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }

@Composable
fun Field(label: String, value: String, update: (String) -> Unit, single: Boolean = true) {
    OutlinedTextField(value, update, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = single)
}

@Composable
fun Picker(label: String, value: String, items: List<Pair<String, String>>, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label · $value") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { (key, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; select(key) })
            }
        }
    }
}

@Composable
private fun Dashboard(nav: NavHostController, exams: List<Examination>, students: List<Student>,
                      scans: List<Submission>, archives: List<AnswerSheetArchive>, datasets: List<DatasetArchive>) {
    Screen("Classroom") {
        val graded = scans.filter { it.inclusion == "INCLUDED" && it.confirmed && it.studentId != null }
            .mapNotNull { it.studentId }.distinct().size
        Tile("At a glance", "${exams.count { !it.archived }} active exams  ·  ${scans.size} scans  ·  $graded students graded\n" +
            "${archives.count { it.status == "READY" }} PDFs  ·  ${datasets.sumOf { it.records }} answer records")
        Button(onClick = { nav.navigate("scan") }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
            Text("Scan answer sheets")
        }
        Tile("Examinations", "Prepare exams and activate answer keys", { nav.navigate("exams") })
        Tile("Roster", "${students.size} students · Add, search, or import CSV", { nav.navigate("roster") })
        Tile("Templates", "Preview, print, or share answer sheets", { nav.navigate("templates") })
        Tile("Results & reports", "Grades, attempts, and item analysis", { nav.navigate("results") })
        Tile("PDF archive", "Find saved student sheets", { nav.navigate("archive") })
        Tile("CSV datasets", "Export answers and recognition evidence", { nav.navigate("datasets") })
        Tile("Settings", "Appearance, storage, and backup", { nav.navigate("settings") })
        if (scans.isNotEmpty()) {
            Text("Recent scans", style = MaterialTheme.typography.titleMedium)
            scans.take(3).forEach { s ->
                Tile(exams.find { it.id == s.examId }?.title ?: "Exam", status(s),
                    { nav.navigate("review/${s.id}") })
            }
        }
    }
}

fun status(s: Submission): String = when {
    s.inclusion == "DECISION_REQUIRED" -> "Duplicate decision needed"
    s.inclusion == "SUPERSEDED" -> "Superseded"
    s.inclusion == "DISCARDED" -> "Discarded"
    s.recognition == "QUEUED" -> "Recognition queued"
    s.recognition == "ERROR" -> "Recognition failed"
    s.studentId == null -> "Student unassigned"
    s.confirmed -> "Confirmed"
    else -> "${s.grade?.decode<Grade>()?.pending ?: 0} marks need review · awaiting confirmation"
}

@Composable
fun ZoomImage(bitmap: Bitmap, description: String) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var dx by remember(bitmap) { mutableFloatStateOf(0f) }
    var dy by remember(bitmap) { mutableFloatStateOf(0f) }
    Image(bitmap.asImageBitmap(), description, Modifier.fillMaxWidth().heightIn(max = 380.dp)
        .pointerInput(bitmap) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                dx = if (scale == 1f) 0f else (dx + pan.x).coerceIn(-size.width.toFloat(), size.width.toFloat())
                dy = if (scale == 1f) 0f else (dy + pan.y).coerceIn(-size.height.toFloat(), size.height.toFloat())
            }
        }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = dx; translationY = dy; clip = true })
}

private class SheetPrintAdapter(private val file: File) : PrintDocumentAdapter() {
    override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
        if (cancellationSignal.isCanceled) { callback.onLayoutCancelled(); return }
        val info = PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(pages: Array<out android.print.PageRange>, destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal, callback: WriteResultCallback) {
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
                if (cancellationSignal.isCanceled) callback.onWriteCancelled()
                else callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
            } catch (_: Exception) { callback.onWriteFailed("The PDF is no longer available.") }
        }.start()
    }
}

@Composable
fun DocumentDialog(model: ClassroomModel, file: File, mime: String, close: () -> Unit) {
    val context = LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri != null) model.perform {
            model.repository.exportTo(file, uri)
            model.message = "Saved to the selected location."
        }
    }
    var page by remember(file) { mutableIntStateOf(0) }
    val preview by loadState<Pair<Bitmap, Int>?>(null, file, page) {
        if (mime == "application/pdf") withContext(Dispatchers.IO) {
            runCatching { Documents.preview(file, page) to Documents.pageCount(file) }.getOrNull()
        } else null
    }
    AlertDialog(onDismissRequest = close, title = { Text(file.name, maxLines = 2) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            preview?.let { (image, count) ->
                ZoomImage(image, "PDF page ${page + 1}")
                if (count > 1) Actions {
                    TextButton(enabled = page > 0, onClick = { page-- }) { Text("Previous") }
                    Text("Page ${page + 1} of $count", Modifier.padding(top = 12.dp))
                    TextButton(enabled = page + 1 < count, onClick = { page++ }) { Text("Next") }
                }
            } ?: Text(if (mime == "application/pdf") "Opening PDF preview…" else "Save or share this file.")
        }
    }, confirmButton = {
        Actions {
            TextButton(onClick = { export.launch(file.name) }) { Text("Save as") }
            TextButton(onClick = {
                runCatching {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "Share document"))
                }.onFailure { model.message = "The document cannot be shared. Regenerate it." }
            }) { Text("Share") }
            if (mime == "application/pdf") TextButton(onClick = {
                val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                manager.print(file.name, SheetPrintAdapter(file), null)
            }) { Text("Print") }
            TextButton(onClick = close) { Text("Close") }
        }
    })
}

@Composable
fun <T> loadState(initial: T, vararg keys: Any?, load: suspend () -> T): State<T> {
    val result = remember(*keys) { mutableStateOf(initial) }
    LaunchedEffect(*keys) { result.value = load() }
    return result
}
