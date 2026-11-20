# Video-Offer Batch Kecil 1.0.106 — Implementation Plan

**Goal:** implement exactly three adjudicated fixes: one-per-session launch-identity diagnostics, selected-profile retry on the first video-offer timeout, and consistent `appLaunchMode` forwarding at the eight in-scope `cloudMatchHeaders` call sites.

**Base:** local `main` fast-forwarded to `origin/main` @ `0ad368e8e758f056b1815480455b5babf8b6e9a5`; work branch `fix/video-offer-batch-kecil-1.0.106`.

**Tech Stack:** Kotlin, Android, JUnit4 unit tests, Gradle.

## Global constraints

- Do not change `OFFER_TIMEOUT_MS`, `MAX_TRANSPORT_RECONNECT_ATTEMPTS`, the server-not-responding dialog, persistent-reconnect circuit breaking, or `requestSafeVideoFallback` behavior.
- Keep the four-timeout / approximately 48-second arithmetic unchanged.
- Do not touch follow-up 1.0.102 items, timeout/backoff values, recovery-before-dialog, or upstream native-touch identity.
- Do not merge, push, release, or dispatch workflows. Commit the plan and implementation together only on the work branch.
- Write or adjust runnable tests before each implementation where a seam exists; record RED and GREEN exit codes. If no unit seam exists, state the untested behavior and reason in `NOTES-video-offer-batch-kecil.md`.
- No new logging framework, dependencies, secrets, or speculative game-context lookups.

## Task 1 — Session identity diagnostics

**Files:**
- Modify: `android/app/src/main/java/com/opencloudgaming/opennow/OpenNowViewModel.kt`
- Modify only if the smallest test seam requires it: `android/app/src/main/java/com/opencloudgaming/opennow/Models.kt`, `NativeTouchGames.kt`, or `GfnApi.kt`
- Test: existing relevant tests under `android/app/src/test/java/com/opencloudgaming/opennow/`

**Steps:**
- [ ] Add failing unit cases proving HSR + Auto maps to desktop / `GAMEPAD_FRIENDLY`, NTE + Auto maps to browser / `TOUCH_FRIENDLY`, Always maps to browser for any game, and Off maps to desktop.
- [ ] Run the narrow test and capture RED where relevant.
- [ ] Add the smallest internal pure seam if `appLaunchModeFor` cannot otherwise be tested; otherwise use `shouldUseNativeTouch` and `shouldUseBrowserIdentity` directly.
- [ ] At the three existing `appLaunchModeFor(game)` session-claim paths, emit exactly one diagnostics line per session through the existing session diagnostics mechanism. Include game title and id, `nativeTouchMode`, native-touch decision, numeric launch mode, and browser/desktop identity.
- [ ] Re-run the narrow test and capture GREEN.

## Task 2 — Retry selected video profile on offer timeout

**Files:**
- Modify: `android/app/src/main/java/com/opencloudgaming/opennow/Streaming.kt`
- Test: existing relevant unit tests if a seam exists; otherwise compile plus the full unit suite.

**Steps:**
- [ ] Inspect the existing fallback/restart helpers and add a failing test only where a current unit seam can exercise the new behavior.
- [ ] Port `requestSelectedVideoProfileRetry` with unchanged selected `settings`, independent `selectedProfileRetryApplied` guarding, and the same reconnect-attempt bump semantics as `bumpReconnectAttemptsForFallback`.
- [ ] Replace only the offer-timeout job's first `requestSafeVideoFallback` call with the new retry, using message `Timed out waiting for video offer; retrying the selected profile` and `diagnosticReason = "offer timeout"`; retain `restartTransport(...)` when the new retry returns false.
- [ ] Leave `requestSafeVideoFallback` unchanged for all other callers.
- [ ] Run the relevant tests or document why automated behavior coverage is unavailable, then compile.

## Task 3 — Forward launch mode at eight CloudMatch call sites

**Files:**
- Modify: `android/app/src/main/java/com/opencloudgaming/opennow/GfnApi.kt`
- Modify wrappers/callers only where needed to supply the existing game launch mode.
- Test: `android/app/src/test/java/com/opencloudgaming/opennow/GfnApiTest.kt` and related existing tests.

**Steps:**
- [ ] Add failing browser-versus-desktop header tests for the affected session operations before implementation.
- [ ] Run the narrow tests and capture RED.
- [ ] Forward the wrapper's `appLaunchMode` at `GfnApi.kt:2522, 2545, 2567, 2594, 2652, 2665, 2726, 2806`; add wrapper parameters only following the existing `:2467` / `:2637` pattern.
- [ ] Preserve `cloudMatchHeaders`' default `GAMEPAD_FRIENDLY`; leave genuinely context-free operations at the default and identify them in NOTES rather than inventing lookups.
- [ ] Preserve `cloudMatchUsesAndroidTouchIdentityForTouchFriendly`; adjust it only if behavior changes and report that adjustment.
- [ ] Re-run narrow tests and capture GREEN.

## Final verification and handoff

- [ ] Review the complete diff against the three-item scope and verify protected constants/control flow are unchanged.
- [ ] In `D:\NanaPlay\android`, run `./gradlew :app:testDebugUnitTest :app:assembleDebug`; record exit code, test totals, failures, and APK result from task output/artifacts.
- [ ] Write `D:\NanaPlay\NOTES-video-offer-batch-kecil.md` with per-item before/after behavior, changed files, RED/GREEN evidence, automatic-test gaps, unavailable mandatory skills, and any intentionally default CloudMatch call sites.
- [ ] Generate one branch `.diff`/`.patch`, review staged files, exclude unrelated untracked bug-hunt artifacts, and commit plan + code + tests + NOTES + patch together on the work branch.
- [ ] Report exact branch, commit, commands, exit codes, and any remaining unverified behavior without claiming device or release acceptance.
