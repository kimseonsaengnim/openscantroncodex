# OpenScantron

An offline Android tool for printing, recognizing, reviewing, and grading multiple-choice answer sheets. The app stores exams, roster entries, scans, correction history, and derived documents on the device. Android 8.0 (API 26) or later is required.

## Build and test

Install a full JDK 17 or 21, Android SDK Platform 35 and Build-Tools 35.0.0. For device tests install Android Platform-Tools. Open this directory in Android Studio, or set `ANDROID_HOME` to the Android SDK root. The first build downloads the pinned Gradle distribution and Maven dependencies.

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest :app:lintDebug
adb devices -l
./gradlew :app:connectedDebugAndroidTest
```

The build matrix and results are in [TEST_REPORT.md](TEST_REPORT.md). The application APK works without an Internet connection, including the first launch and first scan. QR and OpenCV are packaged in the APK.

## Grade a sheet

1. Open **Roster → Add student** and add fictional student number `0017`. The leading zeros are significant.
2. Open **Examinations → Create examination**. Choose 10 questions, A–D, four number columns, and key `A B C D A B C D A B`. Review and activate.
3. In **Templates**, generate the blank PDF. Print at actual size (100%) on the selected paper. Use black ink on matte white paper. Use the PDF preview's **Save as** for a reusable copy.
4. Fill ID `0017` and the bubbles. In **Scan answer sheets**, start a batch with the corresponding key and template. Use **Import photo** for a saved image or **Open camera → Capture sheet**. Frame all four black squares with a little background around the paper.
5. In the saved sheet, open **Original and archive → Open PDF**. Resolve any flagged items, assign a roster student if needed, and tap **Confirm grade**. Open **CSV datasets** for the automatically synchronized wide and audit files.

To reproduce a controlled test without a printer, save the blank PDF from step 3 and generate a marked image from that same file:

```sh
python3 -m venv .fixture-venv
.fixture-venv/bin/pip install PyMuPDF==1.24.14
.fixture-venv/bin/python tools/mark_fixture.py SHEET.pdf 0017.png
adb push 0017.png /sdcard/Download/0017.png
```

Import `0017.png`. Q008 should be blank, Q009 should be multiple, and Q010 should be ambiguous. Save Q008 as **BLANK**, Q009 as **INVALID**, and Q010 as **B**. After confirmation the expected result is **7/10 (70%)**. Version 2 changes Q004 to A. The grade remains 7/10 until **Preview regrade → Regrade**, after which it becomes **8/10 (80%)**.

To retain the instrumentation fixture and independently verify its outputs, run:

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.retainFixture=true
find app/build/outputs/connected_android_test_additional_output -name answers.csv
.fixture-venv/bin/python tools/verify_fixture.py /path/to/the/device-output-directory
```

The report artifacts use fictional data. A real exam requires a photo of its own form or a fixture generated from its own PDF.

## File handling

- **Save as** uses Android's document picker. **Share** grants temporary read access to the selected file through FileProvider.
- In Excel or LibreOffice, import CSV as UTF-8 and set `student_id`, `submission_id`, and other identifiers to Text. Do this before Excel interprets `0017` as a number. For R, pandas, SPSS, or SAS, specify character types for IDs and answers.
- **Settings → Backup & restore** saves a ZIP. Keep it in a location you control. To test restore, install on a separate empty test device/emulator or use a spare Android profile and select that ZIP. Original photos are optional. Archives and datasets are included.
- Use the individual submission to delete a verified PDF's source photo. The app can still display the PDF and saved answers afterward. Keep originals if you need to rebuild the PDF or make future batch documents.

## Release build

Run `./gradlew :app:assembleRelease` to produce the unsigned release APK. In Android Studio, use **Build → Generate Signed App Bundle / APK**, or sign the aligned release APK with Android Build-Tools `apksigner` and your own release keystore.

```sh
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -P 16 -f 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk app-release-aligned.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" sign \
  --ks /path/to/your-release.jks --out app-release.apk app-release-aligned.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose app-release.apk
```

Keep signing keys outside this repository. The debug signature is for testing. A release signed with a different key cannot update the debug APK in place. Export a backup before replacing an installation.
