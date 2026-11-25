package org.openscantron

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openscantron.data.ScanDatabase
import org.openscantron.data.archiveAttemptMigration
import org.openscantron.data.effectiveAnswersMigration
import org.openscantron.data.encode
import org.openscantron.domain.Classification
import org.openscantron.domain.Observation

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        ScanDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun preservesArchiveMetadata() {
        val database = "migration-test.db"
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(database)
        migration.createDatabase(database, 1).apply {
            execSQL("INSERT INTO DatasetArchive (examId,revision,generation,bytes,records) VALUES ('test',4,3,17,2)")
            close()
        }
        migration.runMigrationsAndValidate(database, 2, true, archiveAttemptMigration).use { upgraded ->
            upgraded.query("SELECT revision,generation,attemptedAt FROM DatasetArchive WHERE examId='test'").use {
                it.moveToFirst()
                assertEquals(4, it.getInt(0))
                assertEquals(3, it.getInt(1))
                assertEquals(true, it.isNull(2))
            }
        }
    }
    @Test fun materializesEffectiveAnswersWithoutChangingEvidence() {
        val name = "effective-migration-test.db"
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name)
        val evidence = Observation(Classification.MULTIPLE_MARKS, listOf("A", "D")).encode()
        migration.createDatabase(name, 2).use { db ->
            db.execSQL("""INSERT INTO Submission
                (id,examId,batchId,position,templateId,keyId,gradedKeyId,scannedAt,original,sourceHash,
                rotation,recognition,confirmed,inclusion,effective)
                VALUES ('s','e','batch',1,'t','key','key','time','file','hash',0,'READY',0,'INCLUDED',0)""")
            db.execSQL("INSERT INTO QuestionRecognition VALUES ('s',1,?)", arrayOf(evidence))
            db.execSQL("""INSERT INTO AnswerCorrection (id,submissionId,question,examId,answer,note,time)
                VALUES ('a','s',1,'e','INVALID','','2026-11-25T00:00:00Z')""")
        }
        migration.runMigrationsAndValidate(name, 3, true, effectiveAnswersMigration).use { db ->
            db.query("SELECT effectiveAnswers FROM Submission WHERE id='s'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(listOf("INVALID").encode(), cursor.getString(0))
            }
            db.query("SELECT evidence FROM QuestionRecognition WHERE submissionId='s'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(evidence, cursor.getString(0))
            }
        }
    }

}

@RunWith(AndroidJUnit4::class)
class ClassroomUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun launchAndOpenExamEditor() {
        compose.onNodeWithText("Classroom").assertIsDisplayed()
        compose.onNodeWithText("Examinations").performClick()
        compose.onNodeWithText("Create examination").performClick()
        compose.onNodeWithText("New examination").assertIsDisplayed()
        compose.onNodeWithText("Exam title").assertIsDisplayed()
    }
}
