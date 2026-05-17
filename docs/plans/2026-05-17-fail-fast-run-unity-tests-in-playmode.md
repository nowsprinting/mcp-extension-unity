# Fail-fast `run_unity_tests` when Unity Editor is already in PlayMode

## Context

When `run_unity_tests` is invoked while the Unity Editor is already in PlayMode,
Unity's Test Runner silently refuses the launch — no `TestResult` and no
`RunResult` signals come back to the C# backend. The Kotlin coroutine then
blocks on `model.runTests.startSuspending(request)` until the MCP client
(Claude Code) cancels the request at its ~3-minute timeout, surfacing a
`JobCancellationException` to the user.

Evidence from logs:

- `backend.2026-05-16_1514.71540.log` 14:07:36 — handler runs through
  `RunUnitTestLaunch.Start called`, then no further `TestResult` / `RunResult`
  entries until the next unrelated tool call at 14:15:04.
- `idea.log` 14:10:36 — `SEVERE - run_unity_tests failed` /
  `Caused by: kotlinx.coroutines.JobCancellationException`.

The C#-side `MCP_TOOL_TIMEOUT` (default 300 s) never fires because the MCP
client cancels first (~180 s). The Kotlin frontend has no upfront PlayMode
check; `UnityEditorToolset.kt:19` mentions the precondition in the
`get_unity_compilation_result` description only, and no tool enforces it.

This change adds a Kotlin-side fail-fast: if the Editor is already playing,
`run_unity_tests` returns an immediate, actionable error instead of blocking
until the MCP client times out. The fix matches the existing project rule —
"All input validation is done on the Kotlin side (fail-fast)" (CLAUDE.md §5) —
and reuses the same `frontendBackendModel.playControls.play` property that
`PlayControlTool` already reads.

## Design

### Behaviour

`run_unity_tests` performs a pre-Rd check:

1. Validate `testMode` and `assemblyNames` (existing logic, unchanged).
2. Read `frontendBackendModel.playControls.play.valueOrDefault(false)`.
   - `valueOrDefault(false)` is used intentionally: if the Editor is not yet
     connected, treat as "not playing" and let the existing C#-side
     `RdConnectionHelper.WaitForUnityModel` handle the connection wait —
     adding `awaitEditorConnection` here would double the connection wait
     and slow the common case.
3. If `isPlaying == true`, return `TestErrorResult` with a clear, actionable
   message instructing the agent to stop play mode first. No Rd call is made.
4. Otherwise, proceed to the existing Rd call.

### Why no extra connection wait

`playControls.play` is published by the Backend↔Unity Rd channel; when the
Editor is disconnected, the property has no value and `valueOrDefault(false)`
returns `false`. This is the *safe default* — the user wants tests to run, so
we let the request flow through to the C# handler, which already waits up to
30 s for `BackendUnityModel`. If the Editor is genuinely playing, the property
is non-null and reads `true`, fail-fast triggers.

### Why only `run_unity_tests`

The user's reported failure is `run_unity_tests`. The same precondition is
documented for `get_unity_compilation_result`
(`UnityEditorToolset.kt:19`), but its current symptom is different — it
relies on `Refresh.Start()` which behaves differently during PlayMode. That
tool is out of scope for this change; if a similar fail-fast is desired
there, it should be a follow-up driven by its own reproduction.

### Error message

Mirror the wording used in existing user-facing error messages
(`PlayControlTool`, `RdConnectionHelper`), and tell the agent the exact
remediation:

```
Unity Editor is in PlayMode. Stop play mode by calling `unity_play_control`
with `action='stop'` before running tests.
```

## Critical Files

- `src/main/kotlin/com/nowsprinting/mcp_extension_unity/RunUnityTestsTool.kt`
  — add the play-state check and the helper used in tests.
- `src/test/kotlin/com/nowsprinting/mcp_extension_unity/RunUnityTestsToolTest.kt`
  — add unit tests for the new pure helper.
- `CHANGELOG.md` — add an entry under `[Unreleased] / Fixed`.

Reused existing code:

- `PlayControlTool.unity_play_control` at `src/main/kotlin/.../PlayControlTool.kt:64`
  shows the exact access pattern: `solution.frontendBackendModel.playControls.play.valueOrDefault(false)`.
- Existing serialization tests in `RunUnityTestsToolTest.kt` (e.g. line 102)
  show the project's expected JSON shape for `TestErrorResult`.

## Test Cases of kotlin tests

### RunUnityTestsTool

New pure helper to make the decision testable without a live IDE/Rd:

```kotlin
internal fun playModeRejectionMessage(isPlaying: Boolean): String?
```

Returns `null` if the call should proceed, or the user-facing error message
if the Editor is already playing.

| Test Method                                            | Description                                                 |
|--------------------------------------------------------|-------------------------------------------------------------|
| `playModeRejectionMessage_isPlayingTrue_returnsErrorMessage`  | Returns a non-null message containing remediation guidance  |
| `playModeRejectionMessage_isPlayingFalse_returnsNull`         | Returns null so the existing flow runs unchanged            |

Boundary/equivalence rationale: the function is a 2-state predicate
(`true`/`false`), so two cases cover the partition exhaustively.

The `suspend fun run_unity_tests` itself remains IDE-coupled (needs
`McpToolset` / `currentCoroutineContext().project`) and is not directly unit
tested — matching the existing test design in this file.

### E2E Tests

| # | Item                                                                 | Verification Method                                                                                              |
|---|----------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| 1 | Calling `run_unity_tests` while Editor is in PlayMode returns an immediate error | In Unity, press Play. From Claude Code, invoke `run_unity_tests`. Expect `success=false` and the PlayMode error message within ~1 s. |
| 2 | Calling `run_unity_tests` while Editor is stopped runs tests normally | Stop play mode. Invoke `run_unity_tests` with a small EditMode assembly. Expect normal success result.            |
| 3 | Calling `run_unity_tests` while Editor is not connected to Rider proceeds to the existing 30 s connection wait | Quit Unity. Invoke `run_unity_tests`. Expect the existing "Unity Editor did not connect within 30 seconds" error, not the PlayMode error. |

## Development Workflow

### Step 1 — Skeleton (Compilable)

Add the `playModeRejectionMessage(isPlaying: Boolean): String?` companion
helper to `RunUnityTestsTool` returning `null` for now. Code compiles, no
behaviour change.

### Step 2 — Test First

Add the two tests above to `RunUnityTestsToolTest.kt`. Run:

```bash
JAVA_HOME=~/Library/Java/JavaVirtualMachines/jbr-25.0.2/Contents/Home \
  ./gradlew --no-configuration-cache test --tests \
  "com.nowsprinting.mcp_extension_unity.RunUnityTestsToolTest.*playModeRejectionMessage*"
```

Confirm the `isPlayingTrue` case fails (helper returns null). Commit.

### Step 3 — Implementation

1. Implement `playModeRejectionMessage` to return the user-facing message
   when `isPlaying == true`.
2. In `run_unity_tests`, after the existing `parseTestMode` validation and
   before constructing the Rd request, read
   `solution.frontendBackendModel.playControls.play.valueOrDefault(false)`
   and call the helper. If non-null, return
   `TestErrorResult(errorMessage = it)`.
3. Resolve `error`-severity diagnostics via
   `mcp__jetbrains__get_file_problems`.
4. Re-run the test suite; all tests must pass. Commit.

### Step 4 — Refactoring

Apply DRY/KISS review — in particular keep the helper a pure function so the
suspend body stays thin. Reformat both files with
`mcp__jetbrains__reformat_file`. Resolve `suggestion`-level diagnostics. Commit.

### Step 5 — E2E Tests

Copy this plan to `docs/plans/2026-05-17-fail-fast-run-unity-tests-in-playmode.md`
and create the matching E2E file at
`docs/plans/2026-05-17-fail-fast-run-unity-tests-in-playmode-e2e-tests.md`
using the table above, formatted per `docs/e2e-tests.md`.

### CHANGELOG

Under `## [Unreleased] / ### Fixed`, append:

```
- Fail-fast in `run_unity_tests` when Unity Editor is in PlayMode, instead of blocking until the MCP client times out
```

## Verification

End-to-end:

1. Build and install the plugin per `CLAUDE.md` "Build" / "Install".
2. Open a Unity project, press Play, then from Claude Code invoke
   `run_unity_tests` with any valid `testMode` / `assemblyNames`. Expect the
   new PlayMode error within a second.
3. Stop play mode and re-invoke; expect tests to run normally.
4. Quit Unity and re-invoke; expect the pre-existing 30 s connection-timeout
   error, confirming we did not regress the disconnected path.

Automated:

```bash
JAVA_HOME=~/Library/Java/JavaVirtualMachines/jbr-25.0.2/Contents/Home \
  ./gradlew --no-configuration-cache test
```

All existing tests must continue to pass; the two new tests must pass.
