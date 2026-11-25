# OpenScantron verification

## Build and test runs

| Variant and command | Result |
|---|---|
| Debug · `./gradlew :app:assembleDebug` | Pass. APK built with Gradle 8.9, Temurin JDK 21.0.9, SDK Platform 35, and Build-Tools 35.0.0. Android Build-Tools `apksigner verify --verbose` verified one debug signer, v2 signature. |
| Debug host · `./gradlew :app:testDebugUnitTest` | 9 passed, 0 failed, 1 skipped. The skipped test is the optional PNG writer and runs only with `-PfixtureOutput=/absolute/path.png`. Gradle XML is under `app/build/test-results/testDebugUnitTest/`. |
| Debug lint · `./gradlew :app:lintDebug` | 0 errors and 1 warning for target API 34. Report at `app/build/reports/lint-results-debug.html`. |
| Debug Android · `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.retainFixture=true` | 7 passed, 0 failed, 0 skipped. The combined wrapper run completed successfully. API 30 (Android 11), SDK Phone x86_64, legacy Android Emulator 30.7.5 with software CPU translation and SwiftShader. Airplane mode enabled. Reports under `app/build/reports/androidTests/connected/debug/`. |

Both Room migrations and all seven final Android tests completed on the emulator. The first Android run exposed an actual file-cleanup defect. Android resolved the same directory through `/data/data` and `/data/user/0`, and cleanup removed a published CSV. The vault now consistently uses its canonical root. The initial run also passed the launch, PDF page-order, and Room migration tests. The next run reached the duplicate batch and exposed a fixture assertion that counted the CSV header as a response. The assertion now checks the exact included submission IDs and response count. Host lint identified a Java math method introduced at API 33, which was replaced with API-26-compatible arithmetic. The initial synthetic set exposed a faint-mark overaccept, and the strong-mark threshold was raised from .29 to .34.

The merged APK declares CameraX and WorkManager permissions. The manifest inspection found no Internet permission.

## Recognition measurements

The reproducible host harness uses desktop OpenCV 4.9.0-0 bindings on an AMD EPYC 9V74 workspace and runs the recognizer's geometric and sampling code. Android packages OpenCV 4.10.0. The main set uses the same fictional A4 sheet rendered four ways. Variants are clean raster, 90° rotation, perspective warp, and uneven light with shadow and mild Gaussian blur. Ground truth is in `app/src/test/resources/marks-0017.csv`.

| Metric | Synthetic main set | Physical target | Physical result |
|---|---:|---:|---|
| Registered and aligned | 4/4 | 98% | Unverified |
| Correct auto-accepted single answers | 28/28 | 99% | Unverified |
| Correct blank responses | 4/4 | 99% | Unverified |
| Multiple selections detected | 4/4 | 98% | Unverified |
| Exact student ID | 4/4 | 99% | Unverified |
| Intended ambiguous items sent to review | 4/4 | — | Unverified |

The main set had zero rejected sheets, zero incorrect auto-accepts and 8 of 40 items routed to review by the default policy. Denominators include every eligible item across all four input images, so these results equal the successful-registration-conditioned rates for this set. All four variants share one underlying sheet. Classroom accuracy requires the independent physical sample described below.

Separate synthetic checks accepted the Letter form, rejected a known conflicting QR and a marker-free image, and accepted a selected template without a QR while routing a partial fill to review and treating a faint erasure as blank. The deliberately invalid images are outside the main set.

The last host timings were 201 ms clean, 158 ms rotated, 167 ms perspective and 130 ms shaded/soft. Each timing is one run of the recognizer's grayscale geometry and mark pipeline. They exclude Android capture, JPEG decode, PDF, CSV, and Room. The API-30 software emulator took 33.6 s for one queued scan to reconcile through Android OpenCV, individual PDF publication, and dataset publication in the final run. Camera preview and JPEG smoke took 66.2 s including emulator activity launch and permission setup. These emulator durations include software translation and startup. Physical latency, memory peak, battery impact, capture rate, and batch-scale performance are unverified.

## Acceptance matrix

Evidence abbreviations used below

- **Domain** and **OMR** refer to `DomainTest` and `RecognitionTest` in `app/src/test/java/org/openscantron/`.
- **Classroom** means `ClassroomIntegrationTest.printedPdfToDurableGradeAuditArchiveAndRestore`.
- **Page order** means `ClassroomIntegrationTest.rendersLetterAndVerifiesOrderedImagePdf`.
- **Retry** means `ClassroomIntegrationTest.publicationFailurePreservesRowsAndAllowsRetry`.
- **UI**, **Camera**, and **Migration** refer to `ClassroomUiTest`, `CameraSmokeTest`, and `MigrationTest`, respectively. The device cases are under `app/src/androidTest/java/org/openscantron/`.

| ID | Status | Evidence or remaining check |
|---|---|---|
| AC-01 | Pass | Debug assemble and APK signature verification. |
| AC-02 | Pass | UI launch and navigation to exam creation on API 30. |
| AC-03 | Unverified | Classroom exercises exam creation and key activation. Full UI edit, archive, duplicate, and confirmed deletion still need a walkthrough. |
| AC-04 | Unverified | Domain and Classroom exercise key validation and version changes. Sequential/grid input and activation confirmation still need a UI walkthrough. |
| AC-05 | Pass | Page order exercises the 100-item Letter renderer and opens its PDF with Android PdfRenderer. |
| AC-06 | Pass | Camera streamed a CameraX preview and produced a decodable JPEG with airplane mode enabled. The printed-sheet grading path still needs a phone and printer. |
| AC-07 | Pass | Classroom passed the PDF-derived PNG through the production import stream and recognized it. Direct interaction with the Android picker remains unverified. |
| AC-08 | Pass | OMR aligns clean, rotated, perspective and shaded fixtures. |
| AC-09 | Pass | OMR executes native OpenCV and checks expected marks. |
| AC-10 | Pass | OMR decodes leading-zero IDs. The Classroom case also exercises explicit assignment. |
| AC-11 | Pass | Domain verifies provisional, final, partial credit, deductions, thresholds and regrade calculations. |
| AC-12 | Unverified | Classroom tests batch persistence and scan order. Continuous camera batches and resumption on hardware remain unverified. |
| AC-13 | Pass | Classroom saved three overrides and verified the untouched machine observations, effective tokens, and audit statuses. |
| AC-14 | Pass | Classroom closed and reopened the file-backed database and recovered the same submission, three corrections, and readable PDF. |
| AC-15 | Pass | Classroom identified duplicate submissions and exercised retaining, superseding, and discarding attempts. |
| AC-16 | Pass | Domain verifies Unicode, leading zeros, quoted fields, embedded newlines and stable response headers. |
| AC-17 | Pass | Independently opened the exported performance PDF with MuPDF and verified readable score text. |
| AC-18 | Pass | The API-30 emulator ran Camera and the first recognized 0017 scan with airplane mode on, Wi-Fi off, and no Internet permission. |
| AC-19 | Pass | Retry forced both CSV and PDF publication failures, checked that the prior data remained, detected a corrupted PDF, and regenerated both file types. |
| AC-20 | Pass | Host tests and the initial connected suite executed, including the Room migration and PDF order test. |
| AC-21 | Pass | Classroom opened the individual archive while two marks were pending. MuPDF independently found all four registration squares in its image. |
| AC-22 | Unverified | PDF preview exercised by Page order. The Android share recipient and re-export via the picker still need manual verification. |
| AC-23 | Pass | Page order verifies rendered colors by page and the white margin. Classroom also checks the persisted page-to-submission map. |
| AC-24 | Pass | Classroom and Retry verified a valid header-only CSV at exam creation and an updated revision after scanning. |
| AC-25 | Pass | Independent parsing found one response row with ten question columns. Classroom verified the row count through repeated generation and regrade. |
| AC-26 | Pass | Independent parsing found ten audit rows with the original multiple and ambiguous classifications and the teacher-resolved values. |
| AC-27 | Pass | Classroom verified correction, new-key regrade, and student reassignment synchronization without changing the submission ID. |
| AC-28 | Pass | Classroom reopened the private PDF after closing and reopening Room and verified its integrity metadata. |
| AC-29 | Pass | Classroom verified invalid ZIP rollback and restored grades, corrections, archive hashes, page maps, CSVs and originals into a second empty Room database and vault. |
| AC-30 | Unverified | Classroom verified deleting the source while preserving the readable PDF. Visible storage totals and the confirmation dialog still need a walkthrough. |

## Independently inspected evidence

The fictional `0017` evidence package contains the blank PDF, marked PNG, individual archive PDF, performance report, wide CSV, audit CSV, and emulator timing. It was checked with Android PdfRenderer, `pdfinfo`, MuPDF, and the Python standard CSV reader. To repeat the independent check, run `python3 tools/verify_fixture.py /path/to/exported-fixture-folder` after installing the PyMuPDF version in the README.

## Hardware checks to reproduce

Install the debug APK on an Android device with a rear camera. Enable airplane mode before first launch. Follow the **Grade a sheet** procedure in `README.md`. For the scripted sheet, expect the 0017 recognition and 7/10 grade before regrade, 8/10 after regrade, one response row, ten audit rows, and the same archive checksum through regrade. Then photograph a printed copy in CameraX and verify that the individual PDF shows the complete original and all corner squares.

For a physical evaluation, make independently marked copies on A4 and Letter. Use an evenly lit, flat surface, print at actual size, and keep all four registration squares visible. Record phone and Android version, printer, stock, pencil or pen, lighting, form version and rejection details. Separate calibration and hold-out sets by physical sheet or session. Record successes over all eligible cases, including review or rejected cases, and separately report rates conditioned on successful alignment. Capture camera-to-durable-save and save-to-recognition/archive/CSV timings for a documented 50-sheet batch and record device memory and battery before and after. A hardware team can inspect memory with `adb shell dumpsys meminfo org.openscantron` and collect a Perfetto trace during that batch.

Still unverified on hardware are physical-sheet accuracy, 50-sheet performance, repeated auto-capture against changing paper sheets, real device storage exhaustion and interruption at each file/Room publication boundary. Backup restore is scoped to an empty installation. Grade confirmation is per submission, and closing a batch ends capture. Template duplication is available through exam duplication. App-wide throughput for very large rosters and SDK 35/36 devices with 16 KB memory pages need separate validation.
