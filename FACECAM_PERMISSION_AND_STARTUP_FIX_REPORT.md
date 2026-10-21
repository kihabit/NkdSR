# Facecam Permission Flow and Smooth-Opening Fix Report

## Expected Files to Change (stated before implementation)

Before editing, the prior audit (`AUDIT ONLY` pass, not repeated here) identified exactly two
confirmed defects, both fixable in two files, plus any new string resources those fixes needed:

- `app/src/main/java/com/nkd/screenrecorder/activities/MainActivity.kt`
- `app/src/main/java/com/nkd/screenrecorder/services/CameraService.kt`
- `app/src/main/res/values/strings.xml` (new strings only, if the permission-explanation UX
  required wording not already present)

**Actual result: exactly these 3 files were changed — no more, no fewer.**
`SSViewService.kt` and `layout_floating_camera_view.xml` were inspected and confirmed to need no
change for this task (no confirmed defect lived there), consistent with the prior audit.

## 1. Exact Root Causes Found (recap + what changed)

**RC1 — Premature active-state update.**
`MainActivity.kt` (previously line 303) called `changeFaceCameBackground(true, binding.faceCameIcon)`
immediately after dispatching `startForegroundService(intent)` — before `CameraService` had
inflated its view, checked any permission, attached its window, or opened the camera. The button
reported success before anything existed.

**RC2 — Redundant window remove/re-add.**
`CameraService.kt`'s `showCameraView()` (previously lines 488-493) called
`mWindowManager.removeViewImmediate(root)` followed immediately by `mWindowManager.addView(root, params)`
right after the window had *already* been added and `cameraView.open()` had just been requested —
tied to a dead `isCameraViewHidden` flag that was written once and never read. This forced an
avoidable `TextureView` detach/reattach at the exact moment the camera was starting to render.

**RC3 (new, surfaced during this task) — the "success" signal was never tied to the camera
actually opening.** Even after fixing RC1/RC2, `CameraService.onStartCommand()` set
`isRunning = true` and sent the `PREFS_TOOLS_CAMERA` broadcast immediately after
`cameraView.open()` was *called* — not after the camera had *actually opened*. The CameraView
library (`com.otaliastudios:cameraview:2.7.2`) exposes `CameraListener.onCameraOpened(CameraOptions)`
specifically for this purpose (verified by disassembling the library's `.aar` — see §9), but
nothing in this codebase used it. This is the concrete mechanism behind "button active/overlay
window attached, but no real preview yet" described in the task.

**RC4 (new, surfaced during this task) — no proactive overlay-permission check before starting
the service.** `MainActivity`'s facecam button only ever checked `Manifest.permission.CAMERA`.
`Settings.canDrawOverlays()` was checked only reactively, inside `CameraService` itself (added in
an earlier session, after a live `BadTokenException` repro) — the service would start, fail, and
tear itself down, rather than the user ever being told *why* before a wasted service start.

**RC5 (new) — no "resume after permission granted" flow for overlay permission.** Camera
permission already resumed correctly via its `ActivityResultLauncher` callback. Overlay
permission had no equivalent — a user sent to Settings had to tap the facecam button again after
returning.

## 2. Permission Flow — Before and After

**Before:**
```
tap → CameraService.isRunning? no →
  CAMERA granted? no → request CAMERA (launcher callback starts service directly on grant,
                        no overlay check) → [icon never touched here]
                   yes → start service, changeFaceCameBackground(true, ...)  ← RC1
```
Overlay permission was never checked by `MainActivity`; a user without it would get a silent
service start → immediate internal failure (handled gracefully since the earlier session's fix,
but with zero explanation to the user before that point).

**After** (`MainActivity.kt`, new `startFacecamIfPermitted()` as the single entry point, used by
the button tap and by all three permission-resumption callbacks):
```
tap → isFacecamStartPending? yes → ignore (debounce rapid taps / already in flight)
                              no  → startFacecamIfPermitted()

startFacecamIfPermitted():
  CameraService.isRunning? yes → clear pending flag, do nothing
  CAMERA granted? no → pending=true, cameraPermissionsLauncher.launch(CAMERA)
                        ├─ granted → startFacecamIfPermitted()        [resume, no 2nd tap]
                        └─ denied  → pending=false
                               ├─ shouldShowRequestPermissionRationale → toast, stop
                               └─ else (permanently denied) → dialog → app Settings
                                        (facecamAppSettingsLauncher) → on return, re-check
                                        CAMERA → resume or drop silently
  Settings.canDrawOverlays()? no → pending=true, explain-then-Settings dialog
                                    (facecamOverlayPermissionLauncher) → on return, re-check
                                    canDrawOverlays() → resume or drop silently
  both granted → pending=false, startForegroundService(CameraService, FACING_FRONT)
                 [icon is NOT touched here]
```
The facecam icon is now driven **solely** by the `PREFS_TOOLS_CAMERA` broadcast
(`MainActivity.kt` `closeReceiver`, unchanged), which `CameraService` now only sends with
`is_running=true` from `CameraListener.onCameraOpened()` — i.e., once the camera has actually
opened (see RC3 fix below). This preserves the existing broadcast *contract* (action name, extra
name/type, who listens) while fixing *when* it fires with `true`.

## 3. Files Changed — Exact Lines

### `app/src/main/java/com/nkd/screenrecorder/activities/MainActivity.kt`

| Region | Change |
|---|---|
| New field (after `projectionManager`) | `isFacecamStartPending: Boolean` — debounce/bookkeeping flag; not relied on for correctness of any resumption decision (those re-check real permission state), only for ignoring a second tap while a request is in flight. |
| `cameraPermissionsLauncher` (rewritten) | On grant: calls `startFacecamIfPermitted()` instead of starting the service directly. On denial: `shouldShowRequestPermissionRationale` distinguishes a regular denial (toast) from a permanent one (routes to `showFacecamCameraPermissionSettingsDialog()`). |
| `facecamOverlayPermissionLauncher` (new) | `StartActivityForResult` wrapping `ACTION_MANAGE_OVERLAY_PERMISSION`; re-checks `Settings.canDrawOverlays()` on return (result code is unreliable across OEMs) and resumes via `startFacecamIfPermitted()` if granted. |
| `facecamAppSettingsLauncher` (new) | `StartActivityForResult` wrapping `ACTION_APPLICATION_DETAILS_SETTINGS`; re-checks CAMERA permission on return and resumes if granted. |
| `btnFacecam.setOnClickListener` (rewritten) | Stop path unchanged (`stopService` + immediate icon update — not RC1, see §4 note). Start path now calls `startFacecamIfPermitted()` only if `!isFacecamStartPending`; **the eager `changeFaceCameBackground(true, ...)` call is removed** (fixes RC1). |
| `startFacecamIfPermitted()` (new, private) | Single idempotent entry point implementing the flow in §2; re-checks both permissions every time it runs. |
| `showFacecamOverlayPermissionDialog()` (new, private) | Explains why overlay permission is needed (reuses existing `overlay_permission_title` string + new `facecam_overlay_permission_required` string from an earlier session); Grant → launches `facecamOverlayPermissionLauncher`; Deny → clears the pending flag. |
| `showFacecamCameraPermissionSettingsDialog()` (new, private) | Explains permanent CAMERA denial (reuses existing `permission_required_title`, `settings`, `cancel` strings + new `facecam_camera_permission_permanently_denied` string); routes to `facecamAppSettingsLauncher`. |

### `app/src/main/java/com/nkd/screenrecorder/services/CameraService.kt`

| Region | Change |
|---|---|
| New import | `com.otaliastudios.cameraview.CameraOptions` |
| New field | `private enum class FacecamState { IDLE, OPENING_CAMERA, PREVIEW_READY, FAILED }` + `facecamState` var — the state flow requested by the task, scoped internally (not exposed as new UI). |
| `isCameraViewHidden` field | **Removed** — dead (written once in `showCameraView()`, never read anywhere). |
| `CameraListener.onCameraOpened()` (new override) | The real PREVIEW_READY signal. Guarded by `if (facecamState != FacecamState.OPENING_CAMERA) return` so a late/stale callback arriving after `hideCamera`/`onDestroy()` (state `IDLE`) or after `onCameraError` (state `FAILED`) is ignored rather than incorrectly reporting success — this is the fix for the audit's flagged race ("camera callbacks arriving after facecam was disabled"). On success: sets `isRunning = true` and sends `PREFS_TOOLS_CAMERA` with `is_running=true` — **moved here from the window-add success branch** (fixes RC3). |
| `CameraListener.onCameraError()` | Unchanged logic, plus sets `facecamState = FacecamState.FAILED` for the new guard above. |
| `hideCamera.setOnClickListener` | Added `facecamState = FacecamState.IDLE` before the existing close/stop logic. |
| Window-add success branch (`onStartCommand`) | No longer sets `isRunning`/sends the success broadcast (moved to `onCameraOpened`, see above). Sets `facecamState = FacecamState.OPENING_CAMERA`. Guards `cameraView.open()` with `if (!cameraView.isOpened())` (verified real API via the library's disassembled `.aar`, see §9) to avoid a duplicate open request. Calls `setupDragListener()` directly instead of via `showCameraView()`. |
| `showCameraView()` | **Removed entirely** (its only remaining job, `setupDragListener()`, is now called directly; the remove/re-add — RC2 — is gone). |
| `onDestroy()` | Added `facecamState = FacecamState.IDLE` alongside the existing `isRunning = false`. |

### `app/src/main/res/values/strings.xml`

One new string added: `facecam_camera_permission_permanently_denied` = "Camera permission was
denied. Enable it in Settings to use Face Cam." (Reused existing strings for everything else —
`overlay_permission_title`, `facecam_overlay_permission_required`, `facecam_camera_permission_required`,
`permission_required_title`, `grant`, `deny`, `settings`, `cancel` — no other new strings needed.)

## 4. Camera/Overlay State Transitions (actual, as implemented)

```
IDLE → OPENING_CAMERA → PREVIEW_READY         (happy path)
IDLE → OPENING_CAMERA → FAILED                (onCameraError)
IDLE → OPENING_CAMERA → IDLE                  (user taps hideCamera while opening)
PREVIEW_READY → IDLE                          (hideCamera / onDestroy)
```
`WINDOW_ATTACHED` (the step between `addView()` succeeding and `cameraView.open()` being called)
is implicit — it is the instant between entering `OPENING_CAMERA` and the `onCameraOpened`
callback — the task asked that this be distinguishable from `PREVIEW_READY`, and it now is: the
icon/broadcast fire only at `PREVIEW_READY`, never at `OPENING_CAMERA`.

**Note on the stop path:** tapping the facecam button while already running still updates the
icon immediately in `MainActivity.kt` (unchanged). This was deliberately left as-is — it is not
RC1 (RC1 was specifically the *start* path reporting success before success existed); the *stop*
path's `stopService()` → `CameraService.onDestroy()` is a synchronous teardown with no camera-
hardware-dependent latency, so there is no equivalent "lying" window to close.

## 5. API 24–36 Compatibility

- No new `Build.VERSION.SDK_INT`-gated code was needed. `Settings.canDrawOverlays()`,
  `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`, `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`,
  `shouldShowRequestPermissionRationale()`, and the Activity Result API are all available since
  well below `minSdk 24`.
- `CameraListener.onCameraOpened(CameraOptions)` and `CameraView.isOpened()` are library-level
  APIs (`com.otaliastudios:cameraview:2.7.2`), not platform APIs — confirmed present via
  `javap` on the library's own disassembled classes (§9), not assumed. The library itself
  internally bridges to Camera2 on API 21+ (confirmed by `app:cameraEngine="camera2"` in
  `layout_floating_camera_view.xml`, unchanged), so this is already within the existing
  minSdk 24 floor.
- Foreground-service-type (`FOREGROUND_SERVICE_TYPE_CAMERA`, gated `>= S`/API 31) and the
  overlay-permission check before `addView()` were already correctly handled before this task
  (from an earlier session) and were not touched — this task only changed *when* the resulting
  success/failure is reported, not the checks themselves.
- `minSdk 24` and `targetSdk`/`compileSdk 36` are unchanged.

## 6. Tests Executed and Actual Results

```
./gradlew compileDebugKotlin  → BUILD SUCCESSFUL (only pre-existing deprecation warnings —
                                 TYPE_PHONE, Notification.Builder(Context) constructor — none
                                 introduced by this change)
./gradlew test                 → BUILD SUCCESSFUL (testDebugUnitTest + testReleaseUnitTest;
                                  project has only the pre-existing boilerplate ExampleUnitTest —
                                  CameraService/MainActivity's permission flow is not unit-
                                  testable without Robolectric/Mockito, which this project does
                                  not use and which was not added, per "avoid unnecessary
                                  dependencies")
./gradlew assembleDebug        → BUILD SUCCESSFUL
./gradlew assembleRelease       → BUILD SUCCESSFUL (R8/minify, lintVitalRelease, ART profile
                                   compilation all passed)
```

**Instrumentation test attempt (not completed — environment, not code):**
An existing instrumented test (`app/src/androidTest/java/com/nkd/screenrecorder/services/CameraServiceTest.kt`,
written in an earlier session) starts `CameraService` and polls `CameraService.isRunning`. With
this task's changes, that `isRunning` transition now only happens via the new
`onCameraOpened()`-gated path — so re-running it exercises RC3's fix end-to-end. Two attempts
were made on the connected emulator (`Pixel_8_Pro_API_36(AVD)`, API 36) with both `CAMERA` and
overlay permission granted via `adb`:
- First attempt: `Test run failed to complete. Instrumentation run failed due to Process crashed.`
  Logcat for that window shows an **ANR in `com.android.vending`** (Google Play Store, an
  unrelated system app) and host-level CPU pressure (`/proc/pressure/cpu: some avg10=79.40`,
  96% total CPU, 84% kernel time) at the time of failure — this is host/emulator resource
  exhaustion, not a crash in this app's code. No `FATAL EXCEPTION` or stack trace naming
  `com.nkd.screenrecorder` appears anywhere in that logcat window.
- A follow-up `uptime` check showed load average oscillating between ~2 and ~26 within minutes,
  confirming an unstable, contended host environment rather than a stable, reproducible failure.
- **This was not retried further.** Per the task's explicit instruction not to claim verification
  that wasn't executed, this is reported as **not completed**, not as passed or failed on the
  merits of the code change.

No device changes were made to the physical device (`7395278d`) that was connected alongside the
emulator during this session — all `adb`/Gradle commands were explicitly pinned to
`emulator-5554` (`-s emulator-5554` / `ANDROID_SERIAL`), consistent with not touching hardware
that wasn't authorized for this work.

## 7. Build Results

| Command | Result |
|---|---|
| `./gradlew compileDebugKotlin` | ✅ BUILD SUCCESSFUL |
| `./gradlew test` | ✅ BUILD SUCCESSFUL |
| `./gradlew assembleDebug` | ✅ BUILD SUCCESSFUL |
| `./gradlew assembleRelease` | ✅ BUILD SUCCESSFUL |

## 8. Remaining Physical-Device Verification Requirements

This task's build/compile verification is complete and clean. The following are **not** yet
verified on a real or emulated device and should be done before considering this closed:

1. **Timing measurement** (explicitly requested by the task): actual elapsed time from the
   facecam button tap to `onCameraOpened` firing, on at least one real device. No timing
   measurement was taken or is reported here — none was performed.
2. **Manual tap-through of all three permission paths** (CAMERA request → grant, overlay
   Settings round-trip → grant, permanent-denial → app Settings round-trip → grant), confirming
   each resumes automatically without a second tap, exactly as designed in §2.
3. **Visual confirmation** that the icon never turns orange before the preview is actually
   visible (the core user-facing fix), and that RC2's removed remove/re-add measurably reduces
   or eliminates any visible flicker — not measured in this session.
4. **Rapid-tap regression** (test case #8): confirm the `isFacecamStartPending` debounce actually
   prevents a double `ActivityResultLauncher.launch()` under real fast double-taps, not just by
   code inspection.
5. **Camera-busy / camera-unavailable** (test case #9): triggering `onCameraError` on a real
   device (e.g., another app holding the camera) to confirm the existing cleanup path (unchanged
   by this task, already present from an earlier session) still behaves correctly now that
   `onCameraOpened` also exists on the same listener.
6. API-level spread (24, 28, 29, 32, 33, 34, 35, 36): only API 36 hardware/emulator was available
   in this environment, and instrumentation on it did not complete this session (§6). No other
   API level was tested at all.

## 9. Library API Verification (how RC3's fix was confirmed real, not assumed)

The task warned against assuming CameraView's API surface. Before using `onCameraOpened` and
`isOpened()`, the actual library was inspected directly:
```
unzip cameraview-2.7.2.aar → classes.jar → javap CameraListener.class
  → onCameraOpened(CameraOptions), onCameraClosed(), onCameraError(CameraException), ...
javap CameraView.class
  → isOpened(): boolean, open(), close(), setFacing/getFacing/toggleFacing(Facing), ...
```
Both methods used in this fix (`onCameraOpened`, `isOpened()`) are confirmed present in the
exact version (`2.7.2`) this project depends on (`gradle/libs.versions.toml`), not inferred from
documentation or memory.

---

## Summary

Three confirmed defects were fixed in exactly the two files the prior audit predicted, plus one
new string resource: (1) `MainActivity.kt` no longer marks the facecam button active before the
service even starts; (2) `CameraService.kt` no longer redundantly tears down and re-adds its own
overlay window right as the camera begins opening; (3) `CameraService.kt` now reports success
only from the camera library's real `onCameraOpened()` callback, with a state guard against a
late/stale callback incorrectly reporting success after the user already closed facecam.
`MainActivity.kt` additionally gained a proactive overlay-permission check (with an explanatory
dialog) and automatic resumption of the facecam request after either a CAMERA permission grant
or a return from the overlay/app-details Settings screen, with no second tap required and no
duplicate request possible while one is already in flight. All four Gradle build/compile/test
commands succeed. Live on-device confirmation of this session's specific changes was attempted
but did not complete, due to host/emulator resource contention unrelated to this app (confirmed
by an ANR in Google Play Store during the same window) — this is reported honestly as unverified
rather than claimed as passing.

**Files changed (3):**
- `app/src/main/java/com/nkd/screenrecorder/activities/MainActivity.kt`
- `app/src/main/java/com/nkd/screenrecorder/services/CameraService.kt`
- `app/src/main/res/values/strings.xml`
