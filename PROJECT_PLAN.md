# Implementation plan

The repository started empty. Build a single-activity Kotlin Android app for API 26 and up. Use Java 17 or 21, Android SDK 35, Gradle 8.9, and a device with a rear camera for physical verification.

| Milestone | Acceptance IDs | Verification |
|---|---|---|
| Foundation | 01–05, 24 | Build and launch. Create a ten-question exam and inspect the blank PDF and header-only CSV. |
| Graded sheet | 06–11, 13, 16, 18, 20–22, 25–27 | Import the documented 0017 fixture, review two questions, reopen the PDF and parse both CSVs. Repeat in airplane mode using CameraX. |
| Classroom workflow | 12, 14–15, 19, 23, 28 | Resume a batch, decide duplicates, regrade, verify archive and dataset recovery. |
| Reporting and recovery | 17, 29–30 | Export results and verify backup/restore on a second installation. |
| Delivery | 01–30 | Run assembleDebug, testDebugUnitTest, lintDebug and connectedDebugAndroidTest. Measure a held-out printed-sheet set on documented hardware. |

Assumptions: user-authorized project creation in the existing checkout. Layouts use A4 and US Letter. English labels and instructions ship first. Use fictional identifiers only in automated fixtures.

## Implementation record

| Milestone | Main files | Validation and open work |
|---|---|---|
| Foundation | Gradle wrapper/configuration, manifest, `data/Records.kt`, `ui/Classroom.kt`, `ui/Preparation.kt`, Room schemas | Ran `./gradlew :app:assembleDebug`, installed to an API-30 Android emulator, and ran Compose launch/navigation and Room migration checks. The header-only CSV is covered by the integration test. |
| First graded sheet | `domain/Layout.kt`, `domain/Grading.kt`, `omr/Recognizer.kt`, `archive/Documents.kt`, `archive/ImagePdf.kt`, `export/Csv.kt`, `export/Datasets.kt`, `camera/Capture.kt`, `ui/Scanning.kt` | Host fixture and scoring suites passed. Added synthetic PDF-to-captured-image validation in the Android suite and a CameraX preview/capture smoke test. Physical CameraX capture of a printed sheet remains the MVP acceptance gate. |
| Classroom workflow | `data/Repository.kt`, `ui/Preparation.kt`, `ui/Scanning.kt`, `ui/Reports.kt` | Added durable batches, assignment/duplicates, versioned regrading, archive and dataset tools. The first connected run caught a canonical-path alias bug. Fixed the vault root and repeated the connected suite. |
| Reporting and recovery | `export/Statistics.kt`, `data/Backup.kt`, `data/Preferences.kt`, backup rules, schema 1→2→3 migrations, `ui/Reports.kt` | Added scoped exports and ZIP restore to an empty installation. The connected suite passed both Room migrations, staged backup/restore, path obstruction recovery, and corrupt-output retry. An actual low-storage device, process interruption at each publish boundary, and separate installation restore are open. |
| Delivery | `README.md`, `TECHNICAL_DOCUMENTATION.md`, `TEST_REPORT.md`, `tools/mark_fixture.py`, host and Android test sources | Ran assemble, unit tests, lint, and connected tests. The final run had 9 passing host tests, 1 intentionally skipped PNG utility, 7 passing Android tests, and zero lint errors. `TEST_REPORT.md` contains the final outcomes and hardware reproduction steps. |

The cloud runtime supplied a JRE. For this run the full Temurin JDK was installed under `/workspace/scratch/jdk21` and the SDK under `/workspace/scratch/android-sdk`. Verified the Gradle distribution and SDK archives against upstream checksums. Use `source /workspace/scratch/use-android.sh` and pass `-Dorg.gradle.java.home=/workspace/scratch/jdk21` to the wrapper on this instance. A typical local Android Studio installation uses its configured JDK and SDK.

Remaining validation starts with the fixture-backed scan and a printed camera scan on a supported Android phone. Then run the held-out physical accuracy and 50-sheet performance checks in `TEST_REPORT.md`, followed by the unverified UI walkthroughs and hardware recovery cases in the acceptance matrix.
