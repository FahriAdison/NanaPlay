# Retry Queue Race Fix (1.0.105) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the "queue looks stuck" retry wait for the abandoned cloud session stop before it re-creates a session, so the retry cannot bounce back into the active-session conflict dialog.

**Architecture:** `retryQueue()` stops using a fire-and-forget `viewModelScope.launch` for `stopSession()`. It owns one dedicated single-flight job (`retryQueueJob`, deliberately **not** `launchJob`, which `play()` refuses to run under) that awaits the DELETE with a timeout, tolerates failure, rethrows cancellation, calls `play()` with the just-stopped session excluded from the launch conflict check, and clears the guard in `finally`.

**Tech Stack:** Kotlin, Android ViewModel + `viewModelScope`, kotlinx-coroutines, JUnit4 unit tests (`android/app/src/test`).

**Spec:** `NanaPlay_retry_queue_review_13_6php.md` (adjudikasi Nanally 8 Nov 2026), transcribed into the 1.0.105 task prompt.

## Global Constraints

- Base: `main` @ `2c3ae39` (1.0.104). Work only on `fix/retry-race-1.0.105`. No merge, no release.
- Scope: only the retry flow. No changes to `retryWithAnotherServer()`, strings, colour tokens, or other behavior.
- `retryQueueJob` must never be stored in `launchJob`: `play()` returns early while `launchJob.isActive` (`OpenNowViewModel.kt:1817-1819`).
- Stop failure must never cancel the retry; `CancellationException` must be rethrown.
- No new dependencies, no secrets in the tree.

## Decisions (three refinements)

| # | Decision | Value / policy | Why |
|---|----------|----------------|-----|
| 1 | Stop-wait timeout | `RETRY_QUEUE_STOP_TIMEOUT_MS = 5_000L` | The OkHttp client sets no `callTimeout` (only 15 s connect + 30 s read), so a hung DELETE could hold the retry ~45 s. 5 s comfortably covers a normal DELETE on mobile data and keeps the retry button responsive. On timeout the retry still proceeds (tolerant by design); the id is **not** excluded in that case, because the old session may genuinely be alive and the actionable "Active session found" dialog is the honest outcome. |
| 2 | Single-flight | Ignore a new tap while `retryQueueJob?.isActive == true` (log a debug event, return before any state reset) | The guard window is only the stop wait. Cancelling the older job would abort an in-flight DELETE and leave the abandoned session alive server-side — the exact state this fix removes. |
| 3 | `lastStoppedSessionId` | **Not** used for the conflict check. A scoped `excludeActiveSessionId` parameter is passed from the retry call into `play()` → `activeSessionLaunchConflict()`, set **only when the DELETE returned success** | `lastStoppedSessionId` is a global set by `stopStream()` too and is consumed by `refreshActiveSession()` (`OpenNowViewModel.kt:3923`); widening it would change stop-then-play behavior outside this fix. The retry-scoped value only affects the retry path, and it is `null` when the stop threw, so a session that is genuinely still alive still raises the conflict dialog. |

## Review Focus

- A retry whose stop throws or times out: retry must still start the launch; the stale session still hits the conflict dialog (correct — it is really alive, and the dialog is actionable).
- Two rapid retry taps: exactly one launch is started, no duplicated state resets.
- ViewModel cleared mid-stop: `CancellationException` propagates, guard cleared, nothing plays.
- Stop that succeeds but the server still lists the session briefly: no conflict dialog.
- Stop that times out at 5 s: retry continues after 5 s instead of waiting on a hung DELETE; if the old session survived, the conflict dialog appears rather than a silent wedge.

---

### Task 1: Exclusion parameter on the pure conflict check

**Files:**
- Modify: `android/app/src/main/java/com/opencloudgaming/opennow/Models.kt:1584-1597`
- Test: `android/app/src/test/java/com/opencloudgaming/opennow/StreamResolutionTest.kt`

**Interfaces:**
- Produces: `internal fun activeSessionLaunchConflict(sessions: List<ActiveSessionInfo>, launchAppId: Int?, settings: StreamSettings, excludeSessionId: String? = null): ActiveSessionInfo?`

- [ ] **Step 1: Write the failing tests** — `activeSessionConflictSkipsSessionJustStoppedByRetry` (single stopped session + `excludeSessionId` → `assertNull`) and `activeSessionConflictFallsBackToOtherSessionWhenStoppedOneIsExcluded` (two active sessions, exclude one → the other's id).
- [ ] **Step 2: Run them** — `./gradlew testDebugUnitTest --tests "*StreamResolutionTest*"`, expect a compile error (`excludeSessionId` unknown), i.e. RED.
- [ ] **Step 3: Add the parameter** — filter additionally on `excludeSessionId == null || it.sessionId != excludeSessionId`.
- [ ] **Step 4: Re-run** — same command, expect PASS.
- [ ] **Step 5: Commit** — `git commit -m "fix(android): allow launch conflict check to skip a just-stopped session"`.

### Task 2: Single-flight retry job that awaits the stop

**Files:**
- Modify: `android/app/src/main/java/com/opencloudgaming/opennow/OpenNowViewModel.kt` (constants ~66, fields ~311, `retryQueue()` ~4402-4467, `play()` 1804-1819 + conflict call ~1959)

**Interfaces:**
- Consumes: `activeSessionLaunchConflict(..., excludeSessionId)` from Task 1.
- Produces: `retryQueueJob: Job?`, `RETRY_QUEUE_STOP_TIMEOUT_MS: Long`, `play(..., excludeActiveSessionId: String? = null)`.

- [ ] **Step 1:** Add `RETRY_QUEUE_STOP_TIMEOUT_MS = 5_000L` and `private var retryQueueJob: Job? = null`.
- [ ] **Step 2:** Guard `retryQueue()` at the top (ignore + debug event while a retry is active).
- [ ] **Step 2b:** On timeout, log and continue with `stoppedSessionId = null`, so a genuinely alive session still raises the conflict dialog.
- [ ] **Step 3:** Move the stop into `retryQueueJob` with `withTimeoutOrNull`, `catch (e: CancellationException) { throw e }`, plain-failure tolerance, `finally { retryQueueJob = null }`, then `play(..., excludeActiveSessionId = stoppedSessionId)`.
- [ ] **Step 4:** Thread `excludeActiveSessionId` through `play()` (signature, default-variant recursive call, conflict check).
- [ ] **Step 5: Verify** — `./gradlew testDebugUnitTest` and `./gradlew :app:assembleDebug`, both exit 0.
- [ ] **Step 6: Commit + push** — plan, source, test in one commit set; push `fix/retry-race-1.0.105` to origin.

**Testing note:** the repo has no `kotlinx-coroutines-test`/Robolectric, so `retryQueue()`'s coroutine behavior cannot be unit-tested without new dependencies. Task 1's pure-function tests are the runnable coverage; the job behavior is verified by the build plus a reasoning walk-through reported with the change.
