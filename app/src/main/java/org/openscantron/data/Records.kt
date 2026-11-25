package org.openscantron.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(indices = [Index("archived")])
data class Examination(
    @PrimaryKey val id: String, val title: String, val subject: String, val description: String,
    val date: String, val templateId: String, val activeKeyId: String, val revision: Long = 1,
    val changedAt: String, val archived: Boolean = false
)

@Serializable
@Entity(indices = [Index(value = ["number"], unique = true), Index("section")])
data class Student(@PrimaryKey val id: String, val number: String, val name: String, val section: String)

@Serializable
@Entity(indices = [Index("examId")])
data class AnswerSheetTemplate(@PrimaryKey val id: String, val examId: String, val definition: String)

@Serializable
@Entity(indices = [Index(value = ["examId", "version"], unique = true)])
data class AnswerKeyVersion(
    @PrimaryKey val id: String, val examId: String, val templateId: String,
    val version: Int, val answers: String, val policy: String, val activatedAt: String
)

@Serializable
@Entity(indices = [Index("examId"), Index("keyId")])
data class ScanBatch(
    @PrimaryKey val id: String, val examId: String, val templateId: String, val keyId: String,
    val startedAt: String, val closedAt: String? = null
)

@Serializable
@Entity(indices = [Index(value = ["batchId", "position"], unique = true), Index(value = ["examId", "studentId"]), Index("sourceHash")])
data class Submission(
    @PrimaryKey val id: String, val examId: String, val batchId: String, val position: Int,
    val templateId: String, val keyId: String, val gradedKeyId: String, val scannedAt: String,
    val original: String, val sourceHash: String, val rotation: Int = 0, val rectified: String? = null,
    val recognition: String = "QUEUED", val recognitionError: String? = null,
    val identityEvidence: String? = null, val studentId: String? = null,
    val grade: String? = null, val confirmed: Boolean = false,
    val inclusion: String = "INCLUDED", val effective: Boolean = false,
    val effectiveAnswers: String? = null
)

@Serializable
@Entity(primaryKeys = ["submissionId", "question"])
data class QuestionRecognition(val submissionId: String, val question: Int, val evidence: String)

@Serializable
@Entity(indices = [Index(value = ["submissionId", "question"])])
data class AnswerCorrection(
    @PrimaryKey val id: String, val submissionId: String, val question: Int, val examId: String,
    val studentId: String?, val answer: String, val note: String, val time: String
)

@Serializable
@Entity(indices = [Index("submissionId")])
data class GradeHistory(
    @PrimaryKey val id: String, val submissionId: String, val keyId: String, val grade: String, val time: String
)

@Serializable
@Entity(indices = [Index("examId"), Index("studentId")])
data class AnswerSheetArchive(
    @PrimaryKey val submissionId: String, val examId: String, val studentId: String?,
    val source: String, val file: String? = null, val bytes: Long = 0, val checksum: String? = null,
    val generatedAt: String? = null, val status: String = "QUEUED", val error: String? = null,
    val attemptedAt: String? = null
)

@Serializable
@Entity
data class DatasetArchive(
    @PrimaryKey val examId: String, val revision: Long = 0, val generation: Long = 0,
    val wideFile: String? = null, val auditFile: String? = null, val bytes: Long = 0,
    val wideHash: String? = null, val auditHash: String? = null,
    val records: Int = 0, val generatedAt: String? = null, val error: String? = null,
    val attemptedAt: String? = null
)

@Serializable
@Entity(indices = [Index("batchId")])
data class BatchDocument(
    @PrimaryKey val id: String, val batchId: String, val file: String,
    val submissionIds: String, val bytes: Long, val checksum: String, val generatedAt: String
)

@Dao
interface Records {
    @Query("SELECT * FROM Examination ORDER BY changedAt DESC") fun examsFlow(): Flow<List<Examination>>
    @Query("SELECT * FROM Student ORDER BY name, number") fun studentsFlow(): Flow<List<Student>>
    @Query("SELECT * FROM Submission ORDER BY scannedAt DESC") fun submissionsFlow(): Flow<List<Submission>>
    @Query("SELECT * FROM AnswerSheetArchive") fun archivesFlow(): Flow<List<AnswerSheetArchive>>
    @Query("SELECT * FROM DatasetArchive") fun datasetsFlow(): Flow<List<DatasetArchive>>
    @Query("SELECT * FROM ScanBatch ORDER BY startedAt DESC") fun batchesFlow(): Flow<List<ScanBatch>>
    @Query("SELECT * FROM AnswerKeyVersion ORDER BY version DESC") fun keysFlow(): Flow<List<AnswerKeyVersion>>
    @Query("SELECT * FROM AnswerSheetTemplate") fun templatesFlow(): Flow<List<AnswerSheetTemplate>>
    @Query("SELECT * FROM Examination") suspend fun exams(): List<Examination>
    @Query("SELECT * FROM Examination WHERE id=:id") suspend fun exam(id: String): Examination?
    @Query("SELECT * FROM Student") suspend fun students(): List<Student>
    @Query("SELECT * FROM Student WHERE id=:id") suspend fun student(id: String): Student?
    @Query("SELECT * FROM Student WHERE number=:number") suspend fun studentByNumber(number: String): Student?
    @Query("SELECT * FROM AnswerSheetTemplate") suspend fun templates(): List<AnswerSheetTemplate>
    @Query("SELECT * FROM AnswerSheetTemplate WHERE id=:id") suspend fun template(id: String): AnswerSheetTemplate?
    @Query("SELECT * FROM AnswerKeyVersion") suspend fun keys(): List<AnswerKeyVersion>
    @Query("SELECT * FROM AnswerKeyVersion WHERE id=:id") suspend fun key(id: String): AnswerKeyVersion?
    @Query("SELECT * FROM AnswerKeyVersion WHERE examId=:examId ORDER BY version DESC") suspend fun keysFor(examId: String): List<AnswerKeyVersion>
    @Query("SELECT * FROM ScanBatch") suspend fun batches(): List<ScanBatch>
    @Query("SELECT * FROM ScanBatch WHERE id=:id") suspend fun batch(id: String): ScanBatch?
    @Query("SELECT * FROM Submission ORDER BY scannedAt, id") suspend fun submissions(): List<Submission>
    @Query("SELECT * FROM Submission WHERE id=:id") suspend fun submission(id: String): Submission?
    @Query("SELECT * FROM Submission WHERE examId=:examId ORDER BY scannedAt, id") suspend fun submissionsFor(examId: String): List<Submission>
    @Query("SELECT * FROM Submission WHERE batchId=:batchId ORDER BY position") suspend fun inBatch(batchId: String): List<Submission>
    @Query("SELECT * FROM QuestionRecognition WHERE submissionId=:id ORDER BY question") suspend fun observations(id: String): List<QuestionRecognition>
    @Query("SELECT * FROM AnswerCorrection WHERE submissionId=:id ORDER BY time, rowid") suspend fun corrections(id: String): List<AnswerCorrection>
    @Query("SELECT * FROM GradeHistory WHERE submissionId=:id ORDER BY time, rowid") suspend fun grades(id: String): List<GradeHistory>
    @Query("SELECT * FROM AnswerSheetArchive") suspend fun archives(): List<AnswerSheetArchive>
    @Query("SELECT * FROM AnswerSheetArchive WHERE submissionId=:id") suspend fun archive(id: String): AnswerSheetArchive?
    @Query("SELECT * FROM DatasetArchive") suspend fun datasets(): List<DatasetArchive>
    @Query("SELECT * FROM DatasetArchive WHERE examId=:id") suspend fun dataset(id: String): DatasetArchive?
    @Query("SELECT * FROM BatchDocument") suspend fun batchDocuments(): List<BatchDocument>
    @Upsert suspend fun save(value: Examination)
    @Upsert suspend fun save(value: Student)
    @Insert suspend fun add(value: AnswerSheetTemplate)
    @Insert suspend fun add(value: AnswerKeyVersion)
    @Upsert suspend fun save(value: ScanBatch)
    @Upsert suspend fun save(value: Submission)
    @Insert suspend fun add(value: QuestionRecognition)
    @Insert suspend fun add(value: AnswerCorrection)
    @Insert suspend fun add(value: GradeHistory)
    @Upsert suspend fun save(value: AnswerSheetArchive)
    @Upsert suspend fun save(value: DatasetArchive)
    @Insert suspend fun add(value: BatchDocument)
    @Query("DELETE FROM Student WHERE id=:id") suspend fun deleteStudent(id: String)
    @Query("DELETE FROM Examination WHERE id=:id") suspend fun deleteExam(id: String)
    @Query("DELETE FROM AnswerSheetTemplate WHERE examId=:id") suspend fun deleteTemplates(id: String)
    @Query("DELETE FROM AnswerKeyVersion WHERE examId=:id") suspend fun deleteKeys(id: String)
    @Query("DELETE FROM ScanBatch WHERE examId=:id") suspend fun deleteBatches(id: String)
    @Query("DELETE FROM Submission WHERE examId=:id") suspend fun deleteSubmissions(id: String)
    @Query("DELETE FROM QuestionRecognition WHERE submissionId=:id") suspend fun deleteObservations(id: String)
    @Query("DELETE FROM AnswerCorrection WHERE submissionId=:id") suspend fun deleteCorrections(id: String)
    @Query("DELETE FROM GradeHistory WHERE submissionId=:id") suspend fun deleteGrades(id: String)
    @Query("DELETE FROM AnswerSheetArchive WHERE examId=:id") suspend fun deleteArchives(id: String)
    @Query("DELETE FROM DatasetArchive WHERE examId=:id") suspend fun deleteDataset(id: String)
    @Query("DELETE FROM BatchDocument WHERE batchId=:id") suspend fun deleteBatchDocuments(id: String)
}

@Database(entities = [
    Examination::class, Student::class, AnswerSheetTemplate::class, AnswerKeyVersion::class, ScanBatch::class, Submission::class, QuestionRecognition::class, AnswerCorrection::class, GradeHistory::class, AnswerSheetArchive::class, DatasetArchive::class, BatchDocument::class
], version = 3, exportSchema = true)
abstract class ScanDatabase : RoomDatabase() {
    abstract fun records(): Records
}

val archiveAttemptMigration = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE AnswerSheetArchive ADD COLUMN attemptedAt TEXT")
        db.execSQL("ALTER TABLE DatasetArchive ADD COLUMN attemptedAt TEXT")
    }
}

val effectiveAnswersMigration = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE Submission ADD COLUMN effectiveAnswers TEXT")
        val observations = db.query("SELECT submissionId, question, evidence FROM QuestionRecognition ORDER BY submissionId, question")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(QuestionRecognition(cursor.getString(0), cursor.getInt(1), cursor.getString(2)))
                }
            }.groupBy { it.submissionId }
        val corrections = db.query("SELECT submissionId, question, answer FROM AnswerCorrection ORDER BY time, rowid")
            .use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getString(0) to cursor.getInt(1), cursor.getString(2))
                }
            }
        observations.forEach { (submissionId, items) ->
            val answers = items.map { item ->
                corrections[submissionId to item.question] ?: item.evidence.decode<org.openscantron.domain.Observation>().token()
            }
            db.execSQL("UPDATE Submission SET effectiveAnswers = ? WHERE id = ?", arrayOf(answers.encode(), submissionId))
        }
    }
}
