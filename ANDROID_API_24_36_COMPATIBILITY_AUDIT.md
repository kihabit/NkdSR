# Projection Permission Crash Fix & API 24–36 Compatibility Audit

## 1. Root Cause of the Original Crash

```
java.lang.RuntimeException
Caused by: java.lang.NullPointerException
at com.nkd.screenrecorder.activities.ProjectionPermissionActivity
    .requestPermissionLauncher$lambda$0(ProjectionPermissionActivity.java:60)
```

`ProjectionPermissionActivity.kt` line 60 (pre-fix) was:

```kotlin
"GIF" -> {
    // Create a new file in the selected directory
    val outputUri = createFileInDirectory(getSavedDirectoryUri()!!)
    ...
```

`getSavedDirectoryUri()` returns `Uri?` — it is `null` whenever no save folder has ever been
chosen (`preference.getString(KEY_DIRECTORY_URI, null)` returns `null`). The `!!` forced a
non-null unwrap and threw `NullPointerException` the instant that assumption was wrong.

**Why it was reachable:** GIF capture is started from `GifCropService` (the on-screen crop
overlay) → `ProjectionPermissionActivity` with `REQUEST_TYPE="GIF"`
(`GifCropService.kt:109-117`). Unlike the `"RECORDING"` request type, which already had an
`isRecordingSetupReady()` guard in `onCreate()` that redirects to `MainActivity` when no save
folder is configured, the `"GIF"` request type had **no equivalent guard**. A user who reached
the GIF crop tool without ever finishing in-app setup (no save folder chosen yet) would:
1. Draw a crop region and tap accept,
2. See the real Android screen-capture consent dialog (the one-time permission grant),
3. Tap "Start now" → `requestPermissionLauncher`'s callback fires with `RESULT_OK`,
4. Hit `getSavedDirectoryUri()!!` with a null value → crash.

The identical `!!` existed a second time at the original line 42 (`"RECORDING"` branch), but that
branch was unreachable with a missing directory because of the existing `onCreate()` guard — so
in practice only the `"GIF"` branch could actually throw this specific NPE, exactly matching the
reported line 60.

## 2. Files Changed

### `app/src/main/java/com/nkd/screenrecorder/activities/ProjectionPermissionActivity.kt`

| Region | Change |
|---|---|
| `requestPermissionLauncher` callback (~lines 36–135) | Replaced both `getSavedDirectoryUri()!!` call sites (`"RECORDING"` and `"GIF"` branches) with a null-checked local `directoryUri`. If it (or the resulting `outputUri`) is null, the service is notified of a cancellation instead of starting capture with invalid data. |
| `onCreate()` (~lines 167–181, new) | Added a `"GIF"` + no-saved-directory guard, mirroring the existing `"RECORDING"` guard: redirects to `MainActivity` and finishes instead of ever requesting consent. |
| `onCreate()` (~lines 183–201) | Wrapped the `projectionManager.createScreenCaptureIntent()` / `requestPermissionLauncher.launch(...)` call in `if (savedInstanceState == null)` to prevent a second consent request / duplicate service start if this Activity is recreated while a request is already pending. |
| New helper (~lines 236–243) | `notifyServicePermissionDenied()` — extracted from the previously duplicated "tell the service, no capture" `Intent` construction; now shared by the callback's denial path, the new null-data bail-outs, and `onBackPressed()`. |

### `app/src/main/java/com/nkd/screenrecorder/activities/MainActivity.kt`

| Region | Change |
|---|---|
| `permissionLauncher` callback (~lines 151–165) | `POST_NOTIFICATIONS` is excluded from the "all granted" gate before `checkDirectoryAndProceed()` is called — a denied notification permission no longer blocks recording (API 33 requirement, §4). |

### New test file

- `app/src/androidTest/java/com/nkd/screenrecorder/activities/ProjectionPermissionActivityTest.kt`
  — two instrumented regression tests exercising the exact crash precondition (see §6).

No other files were modified. `AndroidManifest.xml`, `build.gradle.kts`,
`compileSdk`/`targetSdk`/`minSdk`, and `RecordScreenService.kt` were reviewed (see §4–5) and are
unchanged — none of them were implicated in the root cause.

## 3. Explanation of the Fix

**Primary fix — remove the unsafe unwrap.** In both the `"RECORDING"` and `"GIF"` branches, the
directory lookup is now:

```kotlin
val directoryUri = getSavedDirectoryUri()
val outputUri = directoryUri?.let { createFileInDirectory(it) }

if (directoryUri == null || outputUri == null) {
    Toast.makeText(this, getString(R.string.failed_to_create_file), Toast.LENGTH_SHORT).show()
    notifyServicePermissionDenied()
} else {
    // build and send the ACTION_START_RECORDING / ACTION_START_GIF intent
}
```

This also closes a second, closely-related gap: the pre-fix code already null-checked
`outputUri` (file creation can fail independently of the directory being valid) but still called
`startService(...)` with a null `EXTRA_OUTPUT_URI` regardless — only a `Toast` was shown. Per the
task's explicit "do not start screen capture with invalid permission data" requirement, that path
now also skips `startService` and notifies the service of the cancellation instead. (This
specific defect caused no crash — `RecordScreenService.onStartCommand()` already independently
validates `resultCode`/`data`/`outputUri` before starting capture, see §5 — but it was wasting the
one-time MediaProjection consent grant on a guaranteed-to-fail attempt.)

**Defense in depth — prevention, not just a safety net.** The new `onCreate()` guard for `"GIF"`
means the normal flow never even reaches the callback with a missing directory — it redirects to
`MainActivity` before `createScreenCaptureIntent()` is ever called, exactly mirroring the existing
`"RECORDING"` behavior. The callback-level null check (above) remains as the actual fix for the
reported crash and as a safety net for the narrow race where the directory's persisted URI
permission is revoked *while* the system consent dialog is on screen.

**Activity-recreation guard.** `ProjectionPermissionActivity` is a short-lived trampoline
(`launchMode="singleInstance"`, `noHistory="true"`, `screenOrientation="portrait"`) whose
`onCreate()` unconditionally called `requestPermissionLauncher.launch(captureIntent)`. If the
hosting process is killed while the *system's* screen-capture consent dialog (a separate
activity/process) is in front — a well-documented Android Activity Result API scenario, not
prevented by locking orientation — the platform recreates this Activity to deliver the pending
result. `registerForActivityResult()` always re-registers the launcher on recreation and the
pending result is still delivered correctly; the bug was that the unconditional `launch()` call
would fire a **second** consent dialog on top of the first, and could lead to a duplicate
`RecordScreenService` start if the user responded to both. Wrapping the request in
`if (savedInstanceState == null)` is the standard, documented pattern for exactly this situation
and was verified not to affect the normal (non-recreated) path, since `savedInstanceState` is
`null` on first creation.

**No broad exception handling added.** Per the task's explicit instruction, no
`try/catch (Exception e)` was wrapped around the callback or `onCreate()` to "hide" the crash —
the fix removes the unsafe expression at its source and validates data before use, exactly as
required.

## 4. API 24–36 Compatibility Review

| API range | Finding |
|---|---|
| **24–25 (Android 7.0–7.1)** | No code in the modified paths calls anything introduced after API 24. `createScreenCaptureIntent()` (no-arg), `startService`/`startForegroundService`, and `SharedPreferences` are all available since API 21+. The `savedInstanceState == null` guard and the null-safety fix are plain Kotlin/Activity-lifecycle constructs with no SDK-version dependency. **No regression.** |
| **26–28 (Android 8.0–9)** | `RecordScreenService` already branches `startForeground(... )` with/without a `ServiceInfo` type by `Build.VERSION.SDK_INT` (`>= Q` for `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`, plain `startForeground` below that) — unchanged by this fix. No new overlay windows were added or changed in this task (that was the separate, previously-completed `SSViewService` fix); `TYPE_APPLICATION_OVERLAY`/`TYPE_PHONE` selection in that file is untouched here. |
| **29–32 (Android 10–12L)** | `projectionManager.createScreenCaptureIntent()` (no-arg, pre-API 34 call) is preserved unchanged for `SDK_INT < UPSIDE_DOWN_CAKE`. The consent flow (one `StartActivityForResult` launcher, one `MediaProjectionManager.getMediaProjection(resultCode, data)` call per session in `RecordScreenService`) is unaffected by the fix — only the *validation* of the data reaching that call changed. |
| **33 (Android 13)** | **Real, in-scope defect found and fixed** (see §4 note below): `MainActivity.checkPermissionsAndStart()` bundled `POST_NOTIFICATIONS` into the same `RequestMultiplePermissions()` call as `RECORD_AUDIO`/storage, and `permissionLauncher`'s callback required *all* of them granted before calling `checkDirectoryAndProceed()`. A user who denied the notification permission (legitimately optional — the foreground service and recording work regardless) was incorrectly blocked from recording entirely, with only a generic "permission required" toast. Fixed by excluding `POST_NOTIFICATIONS` from the blocking check while still requesting it in the same single system dialog (no new dialog sequencing, no new permission added). |
| **34–35 (Android 14–15)** | Reviewed `RecordScreenService`: `startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or FOREGROUND_SERVICE_TYPE_MICROPHONE)` is called **before** `MediaProjectionManager.getMediaProjection(resultCode, data)` in both `ACTION_START_RECORDING` and `ACTION_START_GIF` (lines 215–233 / 270–288) — satisfies the Android 14+ requirement that the foreground-service type be declared before the projection token is requested. `projectionManager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())` (full-screen only, Android 14's removal of "single app" capture) is already used for `SDK_INT >= UPSIDE_DOWN_CAKE` in both `ProjectionPermissionActivity` and `MainActivity.requestScreenCapture()` — unchanged, already correct. **No MediaProjection/VirtualDisplay reuse violation found**: `RecordScreenService` keeps fully separate `recordingMediaProjection`/`screenshotMediaProjection` instances, each obtained via its own fresh `getMediaProjection(resultCode, data)` call per session, each registered with its own `MediaProjection.Callback`, and each torn down via `unregisterCallback()` + `stop()` in `cleanupRecordingMediaProjection()`/`cleanupScreenshotMediaProjection()` — this already matches the "new consent per session, no reuse" requirement and needed no change. |
| **36 (Android 16)** | Manually verified on a connected **API 36 (Android 16) emulator** — see §6/§7. No API-36-specific behavior change was found that affects this code path beyond what's already handled by the existing `SDK_INT` branches; `compileSdk`/`targetSdk` remain `36`, unchanged. |

Manifest review: `SSViewService`/`RecordScreenService` manifest entries (`foregroundServiceType`,
`FOREGROUND_SERVICE_MEDIA_PROJECTION`/`FOREGROUND_SERVICE_MICROPHONE`/`POST_NOTIFICATIONS`
permissions) were already correctly declared and required no change. No permission was added and
`targetSdk`/`compileSdk` were not touched, per the task's explicit constraint.

## 5. Audit of the Rest of the Recording Flow

Searched for the specific risk categories called out in the task (`!!` in permission callbacks,
nullable `Intent`/result-data access, duplicate service starts, stale references, foreground-
service type declarations, missing version checks, MediaProjection lifecycle). Findings **fixed**
are in §2–3 above. The following were found but are **not part of this fix** (different file,
different trigger, not implicated in the reported crash) — documented separately per the task's
instruction, not modified:

| File:Line | Pattern | Why it's out of scope here |
|---|---|---|
| `RecordScreenService.kt:309` | `convertVideoToGIF(outputUri, rect!!, width, height)` | `rect` comes from the GIF crop overlay's `"rect"` extra; its only producer (`GifCropService.kt:113`) always sets it, so this is not currently reachable, but it is the same unsafe-unwrap *pattern* as the fixed bug, in a different file. |
| `RecordScreenService.kt:476` | `setOutputFile(fileDescriptor!!.fileDescriptor)` | Unrelated to the permission/result flow; part of `MediaRecorder` setup. |
| `RecordScreenService.kt:627,703` | `imageReader!!.surface` | Guarded by surrounding null-checks earlier in the same functions; unrelated to this crash. |
| `RecordScreenService.kt:798` | `finalBitmap.config!!` | `Bitmap.config` is rarely null; unrelated to the permission/result flow. |

`MainActivity`'s **separate** in-app recording-start path (`checkDirectoryAndProceed()` →
`requestScreenCapture()` → `screenCaptureLauncher` → `startRecordingWithSelectedDirectory()`,
lines 456–544) was also reviewed: it already null-checks `directoryUri`, validates
`pendingResultCode == RESULT_OK`, null-checks `outputUri`, and wraps the service start in
`try/catch` — it does **not** share the bug and was used as the reference for how
`ProjectionPermissionActivity` should (and now does) behave. It has no equivalent
Activity-recreation risk because `requestScreenCapture()` is only invoked from a user-initiated
click handler, not unconditionally from `onCreate()`.

## 6. Regression Testing

| # | Scenario | Result |
|---|---|---|
| 1 | Permission granted | Code path unchanged for the valid-data case; verified by compilation + manual code trace. Full tap-through of the real system consent dialog was **not automated** (see §9). |
| 2 | Permission denied or canceled | `notifyServicePermissionDenied()` path unchanged in behavior, just de-duplicated into a shared helper. Verified by compilation + code trace. |
| 3 | Null or invalid result intent | **Fixed and verified on-device** — see the two new instrumented tests below, which exercise the exact null-directory precondition of the reported crash. |
| 4 | Activity recreation | Fixed via the `savedInstanceState == null` guard (§3). **Verified by code review only** — reliably simulating "process killed while the system consent activity is in front" inside an automated test was judged impractical in the time available; flagged as a residual manual-QA item in §9. |
| 5 | Rapid repeated start/cancel | Not separately automated; the duplicate-launch fix (#4) is the direct mitigation. Not exercised on-device. |
| 6 | Recording start, stop, restart | Not exercised on-device in this session (requires driving the real MediaProjection consent UI). Reviewed by code trace only — `RecordScreenService` logic is unchanged by this fix. |
| 7 | Audio capture and screenshot functionality | Unchanged code paths (not touched by this fix); not exercised on-device. |
| 8 | Floating toolbox behavior | Unchanged (not touched by this fix; was addressed in a separate, earlier `SSViewService` crash fix). Not exercised on-device in this session. |
| 9 | Service shutdown and cleanup | `RecordScreenService` cleanup logic is unchanged by this fix. Not exercised on-device in this session. |
| 10 | API 24, 26, 29, 32, 33, 34, 35, 36 | **Compiled/built successfully once** (single toolchain build covers all of them, since there is only one compiled artifact). **Actually run on-device only for API 36** (see below) — API 24/26/29/32/33/34/35 were **not** tested on real/emulated devices in this session; see §9. |

**On-device verification performed (API 36 / Android 16, "Pixel 8 Pro API 36" AVD,
`emulator-5554`, `arm64-v8a` debug build installed via ADB binary-translation):**

Added `app/src/androidTest/java/com/nkd/screenrecorder/activities/ProjectionPermissionActivityTest.kt`
with two tests that clear the saved-directory preference (`ScreenRecorderPrefs` / `directory_uri`)
before each run, reproducing the exact precondition of the reported crash, then launch
`ProjectionPermissionActivity` with `REQUEST_TYPE=GIF` and `REQUEST_TYPE=RECORDING`:

```
./gradlew connectedDebugAndroidTest
Starting 3 tests on Pixel_8_Pro_API_36(AVD) - 16
... Tests 3/3 completed. (0 skipped) (0 failed)
BUILD SUCCESSFUL
```

- `gifRequest_withNoSavedDirectory_doesNotCrash_andFinishes` — **PASSED**. This is the direct
  regression test for the reported NPE: before the fix, this exact precondition (no saved
  directory, `REQUEST_TYPE=GIF`) combined with a granted consent would throw
  `NullPointerException` in the callback. The `onCreate()` guard now redirects to `MainActivity`
  and finishes before the callback can even run.
- `recordingRequest_withNoSavedDirectory_doesNotCrash_andFinishes` — **PASSED**. Regression check
  that the pre-existing `"RECORDING"` guard still works unchanged.
- `com.nkd.screenrecorder.ExampleUnitTest`/`ExampleInstrumentedTest` (pre-existing boilerplate) —
  **PASSED**, unaffected.
- `adb logcat -d` during the full test run was checked for `FATAL EXCEPTION`/`AndroidRuntime`
  crash entries for `com.nkd.screenrecorder` — **none found**.

## 7. Build and Test Results

```
./gradlew compileDebugKotlin     → BUILD SUCCESSFUL (only pre-existing deprecation warnings:
                                    getParcelableExtra(String) on the SDK_INT < 33 branch,
                                    and the deprecated onBackPressed() override — both present
                                    before this change, neither introduced by it)
./gradlew assembleDebug           → BUILD SUCCESSFUL
./gradlew assembleRelease         → BUILD SUCCESSFUL (R8/minify, lintVitalRelease, ART profile
                                     compilation all passed)
./gradlew test                    → BUILD SUCCESSFUL (testDebugUnitTest + testReleaseUnitTest;
                                     only the pre-existing ExampleUnitTest exists in this project)
./gradlew connectedDebugAndroidTest → BUILD SUCCESSFUL on Pixel_8_Pro_API_36(AVD) — 3/3 tests
                                       passed (see §6)
```

No build or test command failed. No errors were introduced by this change; the warnings shown
above are pre-existing and unrelated to the modified lines.

## 8. Devices / API Versions Actually Tested

- **API 36 (Android 16)** — real connected emulator (`Pixel_8_Pro_API_36(AVD)`, `emulator-5554`,
  x86_64 host running an arm64-v8a app build via binary translation). `connectedDebugAndroidTest`
  executed the two new regression tests plus the pre-existing boilerplate test; all passed, and
  logcat showed no crash during the run.
- **API 24–35** — **not tested on a device or emulator** in this session; only build/compile
  verification was performed for the single compiled artifact that targets this whole range. No
  API 24–35-specific device or emulator was available. This is explicitly flagged rather than
  implied as "tested."

## 9. Remaining Issues and Risks

- **Activity-recreation-while-pending-consent** (task item #4/#5) is fixed by code construction
  (`savedInstanceState == null` guard, a documented, standard pattern) but was **not** exercised
  by an automated test or manual device reproduction — doing so reliably requires killing the
  app's process while a separate system activity (the consent dialog) is in the foreground, which
  was judged impractical to automate in the time available. Recommend manual QA: start a
  recording request, then while the system "Start recording?" dialog is visible, force-stop the
  app process from Developer Options and confirm only one dialog/one `RecordScreenService` start
  occurs.
- **Full consent-flow tap-through** (accepting/denying the real system screen-capture dialog) was
  not automated in this session — `ActivityScenario` tests only covered the pre-consent
  early-return guards, which is where the specific reported crash lived. The "permission granted"
  and "permission denied" happy paths (items #1–#2) were verified by static code trace only.
- **API 24–35 device coverage** is outstanding — recommend running the new
  `ProjectionPermissionActivityTest` (and a manual pass through the real recording/GIF/screenshot
  flows) on at least one emulator each for API 26 (background-execution-restriction boundary), 29
  (scoped storage), 33 (POST_NOTIFICATIONS, now directly affected by this fix), and 34
  (MediaProjection foreground-service-type ordering requirement) before release.
- **Documented-but-unfixed `!!` risks** in `RecordScreenService.kt` (lines 309, 476, 627, 703,
  798 — see §5) are a different, pre-existing pattern in a different file and were intentionally
  left untouched per the task's "fix only defects directly relevant to the crash... document
  unrelated issues separately" instruction. They should be tracked and addressed in a follow-up
  pass if the team wants the same null-safety treatment applied there.
- The `permission_required_recording` toast string is now only shown when a *truly required*
  permission (not `POST_NOTIFICATIONS`) is denied; no string resource changes were needed since
  the message's wording ("permission required for recording") remains accurate for that case.

---

## Summary

The reported `NullPointerException` came from `getSavedDirectoryUri()!!` in
`ProjectionPermissionActivity`'s permission-result callback, reachable specifically through the
`"GIF"` request type (`GifCropService` → `ProjectionPermissionActivity`), which — unlike
`"RECORDING"` — had no pre-check that a save folder was configured. Fixed by: removing the unsafe
`!!` in both the `"GIF"` and `"RECORDING"` branches in favor of a null-checked local that skips
starting the service on invalid data; adding an `onCreate()`-time guard for `"GIF"` mirroring the
existing `"RECORDING"` guard; and adding a `savedInstanceState == null` guard around the one-time
consent request to prevent a duplicate consent dialog / duplicate service start if the Activity is
recreated while a request is pending. A genuine API 33 compliance gap was also found and fixed in
`MainActivity`: a denied `POST_NOTIFICATIONS` permission was incorrectly blocking recording
entirely. `minSdk 24`/`targetSdk 36`/`compileSdk 36` and all other Gradle/manifest configuration
were preserved unchanged. `assembleDebug`, `assembleRelease`, and `test` all build successfully;
two new instrumented regression tests were written and **actually executed on a connected API 36
emulator**, passing with no crash observed in logcat. API 24–35 were verified only by compilation,
not by device/emulator execution, and are flagged as outstanding in §8–9.

**Modified files:**
- `app/src/main/java/com/nkd/screenrecorder/activities/ProjectionPermissionActivity.kt`
- `app/src/main/java/com/nkd/screenrecorder/activities/MainActivity.kt`
- `app/src/androidTest/java/com/nkd/screenrecorder/activities/ProjectionPermissionActivityTest.kt` (new)
