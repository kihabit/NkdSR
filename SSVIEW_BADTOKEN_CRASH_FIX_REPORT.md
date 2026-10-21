# SSViewService `WindowManager.BadTokenException` — Crash Fix Report

## 1. Original Root Cause

`setupToolbox()` called `windowManager.addView(...)` with **no overlay-permission check, no
duplicate-attachment guard, and no exception handling** around the call. `setupFloatingButton()`
(the screenshot button — same window class, same `addView` pattern) had the **identical** defect.
Every other window-adding helper in the same file (`ensureDeleteZoneWindow()`, `showBubbleMenu()`,
`hideToolbox()`'s own `removeView`, the original `hideFloatingButton()`'s own `removeView`) wraps
its `WindowManager` call in a `try/catch` — `setupToolbox()` and `setupFloatingButton()` were the
two outliers.

Because the call was unguarded, any failure from `WindowManager.addView()` (overlay permission
revoked after the app last checked it, an OEM "background pop-up" permission denial that some
ROMs surface as `BadTokenException` instead of `SecurityException`, or a stale/invalid window
token) propagated straight out of `onStartCommand()`. An uncaught exception thrown from a
`Service`'s main-thread callback is wrapped by the platform into exactly the observed:

```
java.lang.RuntimeException
Caused by: android.view.WindowManager$BadTokenException
```

which kills the service (and, since it runs in the main process, the visible app).

**Compounding defect found during audit:** `MainActivity`'s toolbox button
(`binding.btnVideo.setOnClickListener`) sent `ACTION_SHOW_TOOLBOX` to `SSViewService` with **no
`Settings.canDrawOverlays()` check at all** — unlike the overlay-permission dialog shown once in
`onCreate()`. If the user revoked "draw over other apps" after `MainActivity` was created (e.g.
switched to system Settings and back without recreating the activity) and then tapped the
toolbox button, the service would attempt `addView()` with no permission, hitting the unguarded
code path above. This is the most realistic real-world trigger for the reported crash.
`MainActivity`'s screenshot button (`binding.btnScreenshot.setOnClickListener`) had the same gap
for `ACTION_SHOW_SCREENSHOT_BUTTON` → `setupFloatingButton()`.

## 2. Exact Failing Code

`SSViewService.kt` (before fix), lines 900–936:

```kotlin
private fun setupToolbox() {
    toolboxBinding = LayoutRecordingFlipBinding.inflate(LayoutInflater.from(this), null, false)
    ...
    ensureDeleteZoneWindow()
    windowManager.addView(toolboxBinding!!.rootContainer, toolboxParams)   // ← line 921, unguarded
    ...
}
```

called from `onStartCommand()` (line 195):

```kotlin
ACTION_SHOW_TOOLBOX -> {
    if (!isToolboxVisible) {
        startForegroundWithType(createNotification(getString(R.string.notif_toolbox_active)))
        setupToolbox()
        isToolboxVisible = true   // ← set unconditionally even if setupToolbox() had crashed/failed
    }
}
```

Why it failed: `addView()` can throw `BadTokenException`/`SecurityException` for reasons outside
the app's control at the moment the window is actually added (permission state can change
between any prior check and this call, OEM background-overlay restrictions, token/display
edge cases). Nothing in this function or its caller caught that.

A second, related defect: `isToolboxVisible = true` was set in `onStartCommand()` regardless of
whether `setupToolbox()` actually succeeded, so even a caught failure would have left the
service believing the toolbox was visible when it was not — blocking all future retries.

## 3. Files Changed

- `app/src/main/java/com/nkd/screenrecorder/services/SSViewService.kt`
  - Added `import android.provider.Settings`.
  - `onStartCommand()`, `ACTION_SHOW_TOOLBOX` branch: removed the unconditional
    `isToolboxVisible = true`; `setupToolbox()` now owns that flag.
  - `onStartCommand()`, `ACTION_SHOW_SCREENSHOT_BUTTON` branch: removed the unconditional
    `isScreenshotButtonVisible = true`; the "button shown" broadcast is now only sent when
    `setupFloatingButton()` actually succeeded (`isScreenshotButtonVisible` true afterwards).
  - `setupToolbox()`: rewritten — attachment guard, overlay-permission check, `try/catch`
    around `addView()`, binding/params published only after a successful attach.
  - `setupFloatingButton()`: rewritten with the **identical guard pattern** — attachment guard
    (`screenshotBinding?.root?.isAttachedToWindow`), overlay-permission check, `try/catch`
    (`BadTokenException`/`SecurityException`) around `addView()`, `screenshotBinding`/
    `screenshotParams` published only after a successful attach, `isScreenshotButtonVisible` now
    owned by this function instead of by the caller.
  - `hideToolbox()`: `removeView()` now only called when `rootContainer.isAttachedToWindow` is
    true.
  - `hideFloatingButton()`: same guard — `removeView()` only called when
    `root.isAttachedToWindow` is true; also now resets `isScreenshotButtonVisible = false`
    itself (was previously only done by the caller).
- `app/src/main/java/com/nkd/screenrecorder/activities/MainActivity.kt`
  - Added `ensureOverlayPermission()` helper (reuses the existing
    `ACTION_MANAGE_OVERLAY_PERMISSION` flow and `overlay_permission_message` string).
  - `binding.btnVideo.setOnClickListener`: calls `ensureOverlayPermission()` before sending
    `ACTION_SHOW_TOOLBOX`; aborts the click (no service call) if permission is missing.
  - `binding.btnScreenshot.setOnClickListener`: same `ensureOverlayPermission()` guard before
    sending `ACTION_SHOW_SCREENSHOT_BUTTON`.

No other files were changed. Manifest already declares `SYSTEM_ALERT_WINDOW` and
`foregroundServiceType="specialUse"` for `SSViewService` correctly — not part of the defect.

## 4. Explanation of the Fix

**Duplicate-call guard:** `setupToolbox()` now checks
`toolboxBinding?.rootContainer?.isAttachedToWindow == true` at entry and returns immediately if
the bubble is already live, instead of inflating and attaching a second window on top of the
first (orphaned-window leak if `ACTION_SHOW_TOOLBOX`/`ACTION_OPEN_TOOLS` race).

**Permission check before attach:** on API 26+ (`TYPE_APPLICATION_OVERLAY` requires it),
`Settings.canDrawOverlays(this)` is checked before touching `WindowManager`. Missing permission
now logs and returns cleanly — `toolboxBinding`/`toolboxParams` are nulled, `isToolboxVisible`
is `false`, and `checkShouldStopService()` is called so a foreground notification isn't left
dangling for a toolbox that never actually appeared. No `Activity` is launched from the service
— the existing permission-request UX (in `MainActivity`/`ToolsLauncherActivity`) is untouched.

**Exception handling scoped narrowly:** only `WindowManager.BadTokenException` and
`SecurityException` around the single `addView()` call are caught — exactly the two failure
modes that are permission/token related and recoverable. Any other exception (e.g. a bug in
`setupToolboxDragging()`) still propagates, per the instruction not to hide unrelated defects.

**State consistency:** `toolboxBinding`/`toolboxParams` are now assigned only *after*
`addView()` returns successfully, so the field is non-null **iff** the window is actually
attached — eliminating the previous window where a failed/partial `setupToolbox()` could leave
a stale binding referencing a view that was never added. `isToolboxVisible` is now set solely by
`setupToolbox()` itself (true only on success, false on any guarded failure), removing the
redundant/incorrect unconditional assignment that used to live in `onStartCommand()`.

**Safe removal:** `hideToolbox()` now checks `isAttachedToWindow` before calling
`removeView()`, so a toolbox window force-removed by the OS at runtime (e.g. the system revokes
`SYSTEM_ALERT_WINDOW` while the overlay is already shown) no longer throws
`IllegalArgumentException` on the next hide/stop/restart.

**Defense-in-depth at the call site:** `MainActivity`'s toolbox *and* screenshot buttons now
re-check `Settings.canDrawOverlays()` immediately before starting the service, closing the gap
that made this crash reachable from normal UI in the first place, and reusing the app's existing
grant-permission flow (`ACTION_MANAGE_OVERLAY_PERMISSION` + toast) rather than inventing a new
one.

**Screenshot button parity:** `setupFloatingButton()`/`hideFloatingButton()` received the exact
same treatment as `setupToolbox()`/`hideToolbox()` — same attachment guard, same permission
check, same narrowly-scoped `try/catch`, same "publish fields only after a successful attach"
ordering, same `isAttachedToWindow` guard on removal. The two bubbles share the same
`WindowManager`, the same `TYPE_APPLICATION_OVERLAY`/`TYPE_PHONE` fallback, and the same
`ensureDeleteZoneWindow()` dependency, so they were fixed identically for consistency and to
close off the same crash via the screenshot-button entry point.

## 5. Window Configuration Review

- `TYPE_APPLICATION_OVERLAY` (API 26+) / `TYPE_PHONE` (below 26) is correct for this bubble: it's
  an ordinary always-on-top application overlay, not a sub-window needing an activity token, and
  not a system-alert-level window needing special system privilege. Left unchanged — the type
  itself was never the problem (it's identical to the type already used successfully by the
  delete-zone, screenshot button, and bubble-menu windows in this same file).
- `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_NO_LIMITS` and gravity/position are unchanged — purely
  presentation, no token implications.

## 6. Android Compatibility

- API 24–25: falls back to `TYPE_PHONE` (unchanged behavior); `Settings.canDrawOverlays()` check
  is gated to API 26+ to match where `TYPE_APPLICATION_OVERLAY`/the modern overlay-permission
  model applies, consistent with the rest of the file's `Build.VERSION.SDK_INT >= O` checks.
- API 26–33, 34+ (`startForegroundWithType`): untouched.
- No new permissions, manifest changes, or API-level-gated code paths were introduced beyond the
  existing `SDK_INT >= O` pattern already used throughout this file.

## 7. Tests

No Robolectric/Mockito test harness exists in this project (`app/build.gradle.kts` only has
plain JUnit + Espresso/AndroidX JUnit), and the affected code is pure `WindowManager`/`View`
lifecycle logic with no extractable unit-testable core — adding a new test framework was judged
out of scope for a targeted crash fix. Testing performed:

| # | Scenario | Result |
|---|----------|--------|
| — | `./gradlew compileDebugKotlin` | **PASS** — compiles clean (only pre-existing `TYPE_PHONE` deprecation warnings, unrelated to this change) |
| — | `./gradlew assembleRelease` | **PASS** — see §8 |
| 1–10 (regression list in the task) | Start/stop recording, rapid toggling, toolbox open/close, permission revoke + reopen, backgrounding, lock/unlock while recording, service restart with toolbox visible, duplicate-view/leak check, recording/screenshot still functional | **Not executed — require a physical device or emulator**, which is not available in this execution environment. The code changes specifically target scenario 5 (revoke overlay permission, reopen toolbox) and scenario 8/9 (restart while visible / no duplicate windows) by construction (see §4), but this has not been confirmed by on-device runtime observation. The same applies to the screenshot button now that it carries the identical guards. |

**Recommendation:** before release, run scenarios 1–10 manually on at least one API 26+ device
(for the `TYPE_APPLICATION_OVERLAY` path) and one API 24–25 device or emulator (for the legacy
`TYPE_PHONE` path), paying particular attention to #5 (toggle "draw over other apps" off while
the toolbox is open, confirm no crash on next hide/reopen) and #3/#8 (rapid start/stop, restart
while visible — confirm no duplicate bubble and no `IllegalArgumentException` in logcat).

## 8. Release Build Result

```
./gradlew assembleRelease     (after setupToolbox fix)
BUILD SUCCESSFUL in 4m 25s
52 actionable tasks: 15 executed, 37 up-to-date

./gradlew compileDebugKotlin  (after setupFloatingButton fix)
BUILD SUCCESSFUL in 34s
19 actionable tasks: 1 executed, 18 up-to-date

./gradlew assembleRelease     (after setupFloatingButton fix)
BUILD SUCCESSFUL in 3m 5s
52 actionable tasks: 8 executed, 44 up-to-date
```

Full pipeline including `minifyReleaseWithR8`, `lintVitalRelease`, and `compileReleaseArtProfile`
succeeded both times. No errors introduced; the only compiler output is the pre-existing
`TYPE_PHONE` deprecation warning present at multiple lines before this change as well.

## 9. Remaining Risks / Device-Specific Verification Needed

- The `BadTokenException`/`SecurityException` catch cannot be exercised by a compiler or a
  logic-only test — it is only provably correct by reproducing a permission revoke (or an OEM
  "background pop-up window" denial) on a real device while the toolbox or the screenshot button is being opened.
- OEM-specific overlay restrictions (MIUI, ColorOS, FuntouchOS "background pop-up" permissions)
  are known in the wild to throw `BadTokenException` for reasons the stock Android APIs can't
  query (`Settings.canDrawOverlays()` can return `true` while such an OEM toggle is still off).
  The new `try/catch` makes this non-fatal, but the toolbox will simply fail to appear with no
  user-facing explanation on those ROMs — a future improvement could surface a toast, but that
  was out of scope for a crash fix per the task's "smallest correct change" instruction.
- No device/emulator was available in this environment to execute the 10 manual regression
  scenarios; only static compilation and the release build were verified.

---

## Summary

The toolbox overlay crashed with `WindowManager.BadTokenException` because
`SSViewService.setupToolbox()` called `windowManager.addView()` with no overlay-permission
check, no duplicate-attachment guard, and no exception handling — unlike every sibling
window-adding helper in the same file. `setupFloatingButton()` (the screenshot button) had the
identical defect and was fixed the same way on request. `MainActivity`'s toolbox and screenshot
buttons also never re-checked `Settings.canDrawOverlays()` before starting the service, making
the crash reachable from normal UI once overlay permission was revoked after launch. Fixed by
adding an attachment guard and an overlay-permission check before each `addView()`, scoping a
`try/catch` around each call to `BadTokenException`/`SecurityException`, publishing the
binding/params fields only after a successful attach, guarding `removeView()` with
`isAttachedToWindow`, and adding a permission pre-check in `MainActivity` before either overlay
is requested. Release build passes end to end after both fixes; on-device regression testing is
still required and was not possible in this environment.

**Modified files:**
- `app/src/main/java/com/nkd/screenrecorder/services/SSViewService.kt`
- `app/src/main/java/com/nkd/screenrecorder/activities/MainActivity.kt`
