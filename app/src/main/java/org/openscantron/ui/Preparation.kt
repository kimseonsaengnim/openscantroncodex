package org.openscantron.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openscantron.archive.publish
import org.openscantron.data.*
import org.openscantron.domain.*
import org.openscantron.export.Csv

@Composable
fun Exams(nav: NavHostController, model: ClassroomModel, exams: List<Examination>,
          keys: List<AnswerKeyVersion>, scans: List<Submission>) {
    var deletion by remember { mutableStateOf<Examination?>(null) }
    var regrade by remember { mutableStateOf<Pair<Examination, Int>?>(null) }
    Screen("Examinations") {
        Button(onClick = { nav.navigate("edit/new") }) { Text("Create examination") }
        if (exams.isEmpty()) Text("Create an exam and activate its first answer key to print a sheet.")
        exams.forEach { exam ->
            val key = keys.find { it.id == exam.activeKeyId }
            val count = key?.answers?.decode<List<String>>()?.size ?: 0
            val maximum = key?.policy?.decode<Policy>()?.let { p ->
                p.correct * if (p.weights.isEmpty()) count.toDouble() else p.weights.sum()
            }
            val total = scans.count { it.examId == exam.id }
            Tile(exam.title, listOf(exam.subject, exam.date, if (exam.archived) "Archived" else "Active",
                "${count} questions", "${maximum ?: "—"} maximum points", "Key v${key?.version ?: "—"}",
                "$total scans").filter(String::isNotBlank).joinToString(" · ")) {
                if (exam.description.isNotBlank()) Text(exam.description)
                Actions {
                    TextButton(onClick = { nav.navigate("edit/${exam.id}") }) { Text("Edit exam & key") }
                    TextButton(onClick = { model.perform {
                        val draft = model.repository.examDraft(exam.id)
                        model.repository.saveExam(null, draft.copy(title = "${draft.title} (copy)"))
                    } }) { Text("Duplicate") }
                    TextButton(onClick = { model.perform { model.repository.archiveExam(exam.id, !exam.archived) } }) {
                        Text(if (exam.archived) "Restore" else "Archive")
                    }
                    TextButton(onClick = { model.perform {
                        regrade = exam to model.repository.regradeCandidates(exam.id).size
                    } }) { Text("Preview regrade") }
                    TextButton(onClick = { deletion = exam }) { Text("Delete") }
                }
            }
        }
    }
    deletion?.let { exam ->
        Confirm("Delete examination?", "Permanently remove ${exam.title}, its answer keys, submissions, corrections, PDFs, and datasets. External exports remain at their chosen locations.",
            "Delete exam", { deletion = null }) { model.perform { model.repository.deleteExam(exam.id) } }
    }
    regrade?.let { (exam, count) ->
        Confirm("Apply active key?", "$count included submissions using this template will be recalculated. Grades and corrections remain in history. Batches keep their pinned key for new captures.",
            if (count == 0) "Done" else "Regrade $count", { regrade = null }) {
            if (count > 0) model.perform { model.repository.regrade(exam.id, exam.activeKeyId) }
        }
    }
}

private fun tokens(value: String) = value.uppercase(java.util.Locale.ROOT).trim()
    .split(Regex("[,\\s]+")).filter(String::isNotEmpty)

@Composable
fun ExamEditor(nav: NavHostController, model: ClassroomModel, id: String?) {
    val draft by loadState<ExamDraft?>(null, id) {
        if (id != null) model.repository.examDraft(id)
            else ExamDraft("", "", "", "", 10, listOf("A", "B", "C", "D"), Paper.A4, 4, emptyList(), Policy())
    }
    val initial = draft
    if (initial == null) { Screen("Examination") { LinearProgressIndicator() }; return }
    key(id, initial) {
        var title by rememberSaveable { mutableStateOf(initial.title) }
        var subject by rememberSaveable { mutableStateOf(initial.subject) }
        var description by rememberSaveable { mutableStateOf(initial.description) }
        var date by rememberSaveable { mutableStateOf(initial.date) }
        var count by rememberSaveable { mutableStateOf(initial.count.toString()) }
        var labels by rememberSaveable { mutableStateOf(initial.labels.joinToString(" ")) }
        var paper by rememberSaveable { mutableStateOf(initial.paper.name) }
        var digits by rememberSaveable { mutableStateOf(initial.digits.toString()) }
        var answers by rememberSaveable { mutableStateOf(initial.answers.joinToString(" ")) }
        var keyMode by rememberSaveable { mutableStateOf("Sequential") }
        var right by rememberSaveable { mutableStateOf(initial.policy.correct.toString()) }
        var wrong by rememberSaveable { mutableStateOf(initial.policy.wrong.toString()) }
        var blank by rememberSaveable { mutableStateOf(initial.policy.blank.toString()) }
        var invalid by rememberSaveable { mutableStateOf(initial.policy.invalid.toString()) }
        var multiple by rememberSaveable { mutableStateOf(initial.policy.multiple.name) }
        var partial by rememberSaveable { mutableStateOf(initial.policy.partialFraction.toString()) }
        var weights by rememberSaveable { mutableStateOf(initial.policy.weights.joinToString(" ")) }
        var floor by rememberSaveable { mutableStateOf(initial.policy.floorAtZero) }
        var pass by rememberSaveable { mutableStateOf(initial.policy.passAt.toString()) }
        var basis by rememberSaveable { mutableStateOf(if (initial.policy.passInPoints) "Points" else "Percent") }
        var pending by remember { mutableStateOf<ExamDraft?>(null) }
        val size = count.toIntOrNull()?.takeIf { it in 1..100 }
        val configured = tokens(labels)
        val entries = tokens(answers)
        val missing = (1..(size ?: 0)).filter { entries.getOrNull(it - 1) !in configured }
        Screen(if (id == null) "New examination" else "Edit examination") {
            Field("Exam title", title, { title = it })
            Field("Subject", subject, { subject = it })
            Field("Description", description, { description = it }, single = false)
            Field("Exam date (YYYY-MM-DD, optional)", date, { date = it })
            Actions {
                Picker("Questions", count, listOf(10, 20, 30, 40, 50, 60, 75, 100).map {
                    it.toString() to "$it questions"
                }) { count = it }
                Picker("Paper", if (paper == "A4") "A4" else "US Letter",
                    listOf("A4" to "A4", "LETTER" to "US Letter")) { paper = it }
                Picker("Labels", configured.joinToString(" "), listOf(4, 5, 6).map {
                    val choices = ('A' until ('A' + it)).joinToString(" ")
                    choices to choices
                }) { labels = it }
            }
            Field("Question count (1–100)", count, { count = it })
            Field("Answer labels (2–6, space separated)", labels, { labels = it })
            Field("Student number columns (1–8)", digits, { digits = it })
            Tile("Answer key", "One configured label per question. Key revisions apply to new batches.") {
                Picker("Entry mode", keyMode, listOf("Sequential" to "Sequential", "Numbered" to "Numbered grid")) { keyMode = it }
                if (keyMode == "Sequential") Field("Answers in order", answers, { answers = it }, single = false)
                else if (size != null) {
                    (0 until size).forEach { index ->
                        Actions {
                            Text("Q${(index + 1).toString().padStart(3, '0')}", Modifier.padding(top = 12.dp))
                            configured.take(6).forEach { option ->
                                FilterChip(entries.getOrNull(index) == option, {
                                    val updated = MutableList(size) { entries.getOrNull(it) ?: "_" }
                                    updated[index] = option
                                    answers = updated.joinToString(" ")
                                }, label = { Text(option) })
                            }
                        }
                    }
                }
                if (missing.isNotEmpty()) Text("Missing or invalid items: ${missing.take(12).joinToString()}" +
                    if (missing.size > 12) " (+${missing.size - 12})" else "")
            }
            Tile("Scoring", "Wrong, blank, and invalid points may be negative. Optional weights multiply item points.") {
                Field("Correct points", right, { right = it })
                Field("Wrong points", wrong, { wrong = it })
                Field("Blank points", blank, { blank = it })
                Field("Invalid points", invalid, { invalid = it })
                Picker("Multiple marks", multiple, MultiplePolicy.entries.map { it.name to it.name.lowercase().replaceFirstChar(Char::uppercase) }) {
                    multiple = it
                }
                if (multiple == "PARTIAL") Field("Partial fraction (0–1)", partial, { partial = it })
                Field("Question weights (optional)", weights, { weights = it }, single = false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(floor, { floor = it })
                    Text("Floor total score at zero", Modifier.padding(top = 13.dp))
                }
                Picker("Passing threshold", basis, listOf("Percent" to "Percent", "Points" to "Points")) { basis = it }
                Field("Minimum $basis", pass, { pass = it })
            }
            Button(enabled = title.isNotBlank() && size != null && missing.isEmpty() && entries.size == size, onClick = {
                val result = runCatching {
                    val n = requireNotNull(size)
                    val digitCount = digits.toInt()
                    val selectedPaper = Paper.valueOf(paper)
                    SheetLayout("preview", "preview", n, configured, selectedPaper, digitCount)
                    val policy = Policy(right.toDouble(), wrong.toDouble(), blank.toDouble(), invalid.toDouble(),
                        MultiplePolicy.valueOf(multiple), partial.toDouble(), tokens(weights).map(String::toDouble),
                        floor, pass.toDouble(), basis == "Points")
                    policy.validate(n)
                    ExamDraft(title, subject, description, date, n, configured, selectedPaper, digitCount, entries, policy)
                }
                pending = result.getOrNull()
                if (pending == null) model.message = "Check labels, ID columns, points, and weights."
            }) { Text("Review activation") }
        }
        pending?.let { value ->
            val p = value.policy
            val max = p.correct * if (p.weights.isEmpty()) value.count.toDouble() else p.weights.sum()
            Confirm("Activate examination", "${value.count} questions · ${value.labels.joinToString("/")} · ${value.paper}\n" +
                "Maximum $max points · Passing at ${p.passAt} ${if (p.passInPoints) "points" else "%"}\n" +
                "Key: ${value.answers.joinToString(" ")}", "Activate", { pending = null }) {
                model.perform { model.repository.saveExam(id, value); nav.popBackStack() }
            }
        }
    }
}

@Composable
fun Roster(model: ClassroomModel, students: List<Student>) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Student?>(null) }
    var deletion by remember { mutableStateOf<Student?>(null) }
    var imported by remember { mutableStateOf<List<List<String>>?>(null) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.perform {
            imported = withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use {
                    val data = CharArray(2 * 1024 * 1024 + 1)
                    var used = 0
                    while (used < data.size) {
                        val n = it.read(data, used, data.size - used)
                        if (n < 0) break
                        used += n
                    }
                    require(used < data.size) { "Roster import exceeds two million characters." }
                    Csv.read(String(data, 0, used).reader()).also { rows ->
                        require(rows.size >= 2 && rows.first().isNotEmpty()) { "Provide a header and at least one student record." }
                    }
                }
            }
        }
    }
    Screen("Student roster") {
        Actions {
            Button(onClick = { editing = Student(newId(), "", "", "") }) { Text("Add student") }
            OutlinedButton(onClick = { import.launch(arrayOf("text/*", "application/csv", "application/octet-stream")) }) {
                Text("Import CSV")
            }
            OutlinedButton(onClick = { model.perform {
                val file = model.repository.exportFile("csv", "ROSTER")
                withContext(Dispatchers.IO) {
                    publish(file) { tmp -> tmp.bufferedWriter().use {
                        Csv.write(it, sequenceOf(listOf("student_id", "student_name", "section")) +
                            students.asSequence().map { s -> listOf(s.number, s.name, s.section) })
                    } }
                }
                model.open(file, "text/csv")
            } }) { Text("Export roster") }
        }
        Field("Search name, number, or section", query, { query = it })
        val visible = students.filter { s -> listOf(s.name, s.number, s.section).any { it.contains(query, true) } }
        if (visible.isEmpty()) Text("No matching students.")
        visible.forEach { student ->
            Tile(student.name, listOf(student.number, student.section).filter(String::isNotBlank).joinToString(" · ")) {
                Actions {
                    TextButton(onClick = { editing = student }) { Text("Edit") }
                    TextButton(onClick = { deletion = student }) { Text("Delete") }
                }
            }
        }
    }
    editing?.let { student ->
        var number by remember(student.id) { mutableStateOf(student.number) }
        var name by remember(student.id) { mutableStateOf(student.name) }
        var section by remember(student.id) { mutableStateOf(student.section) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Student") }, text = {
            Column {
                Field("Student number", number, { number = it })
                Field("Name", name, { name = it })
                Field("Section", section, { section = it })
            }
        }, confirmButton = {
            TextButton(onClick = { model.perform {
                model.repository.saveStudents(listOf(student.copy(number = number.trim(), name = name.trim(), section = section.trim())))
                editing = null
            } }) { Text("Save") }
        }, dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
    deletion?.let { student ->
        Confirm("Delete student?", "Remove ${student.name} from the roster. Their original sheets and corrections remain, and the submissions return to unassigned.",
            "Delete student", { deletion = null }) { model.perform { model.repository.deleteStudent(student.id) } }
    }
    imported?.let { rows -> RosterImport(model, rows, students, { imported = null }) }
}

@Composable
private fun RosterImport(model: ClassroomModel, rows: List<List<String>>, students: List<Student>, close: () -> Unit) {
    val columns = rows.first().mapIndexed { i, label -> i.toString() to "${i + 1}. $label" }
    var number by remember { mutableStateOf("0") }
    var name by remember { mutableStateOf(if (columns.size > 1) "1" else "0") }
    var section by remember { mutableStateOf(if (columns.size > 2) "2" else "-1") }
    val preview = remember(rows, number, name, section, students) {
        val known = students.mapTo(mutableSetOf()) { it.number }
        rows.drop(1).mapIndexed { index, row ->
            val id = row.getOrNull(number.toInt()).orEmpty().trim()
            val studentName = row.getOrNull(name.toInt()).orEmpty().trim()
            val sectionName = row.getOrNull(section.toInt()).orEmpty().trim()
            val issue = when {
                row.size != columns.size -> "Wrong column count"
                number == name -> "Map number and name to different columns"
                id.isBlank() || studentName.isBlank() -> "Missing number or name"
                !known.add(id) -> "Duplicate student number $id"
                else -> null
            }
            if (issue != null) null to "Record ${index + 2}: $issue"
            else Student(newId(), id, studentName, sectionName) to "$id · $studentName · $sectionName"
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text("Map roster columns") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            Picker("Number", columns.find { it.first == number }?.second.orEmpty(), columns) { number = it }
            Picker("Name", columns.find { it.first == name }?.second.orEmpty(), columns) { name = it }
            Picker("Section", columns.find { it.first == section }?.second ?: "None", listOf("-1" to "None") + columns) { section = it }
            Text("${preview.count { it.first != null }} valid · ${preview.count { it.first == null }} errors")
            (preview.filter { it.first == null } + preview.filter { it.first != null }.take(10)).forEach { (record, text) ->
                Text(text, color = if (record == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
    }, confirmButton = {
        TextButton(enabled = preview.isNotEmpty() && preview.all { it.first != null }, onClick = {
            model.perform { model.repository.saveStudents(preview.mapNotNull { it.first }); close() }
        }) { Text("Import roster") }
    }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
fun Templates(model: ClassroomModel, exams: List<Examination>, students: List<Student>) {
    var examId by rememberSaveable { mutableStateOf("") }
    var person by rememberSaveable { mutableStateOf("") }
    var copies by rememberSaveable { mutableStateOf("1") }
    val exam = exams.find { it.id == examId } ?: exams.firstOrNull()
    Screen("Printable templates") {
        if (exam == null) Text("Create an examination before generating an answer sheet.")
        else {
            Picker("Exam", exam.title, exams.map { it.id to it.title }) { examId = it }
            Picker("Personalized QR", students.find { it.id == person }?.name ?: "Blank",
                listOf("" to "Blank") + students.map { it.id to "${it.number} · ${it.name}" }) { person = it }
            Field("Number of copies (1–50)", copies, { copies = it })
            Text("Print on the selected paper at actual size (100%). Keep all four black squares on the page.")
            Button(onClick = { model.perform {
                val count = requireNotNull(copies.toIntOrNull()) { "Enter a whole number of copies." }
                model.open(model.repository.templatePdf(exam.id, count, person.ifBlank { null }), "application/pdf")
            } }) { Text("Generate and preview PDF") }
        }
    }
}
