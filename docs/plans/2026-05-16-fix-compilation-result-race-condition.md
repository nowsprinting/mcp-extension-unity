# Fix get_unity_compilation_result race condition during Unity compilation

## Context

The `get_unity_compilation_result` MCP tool fails with `"GetCompilationResult failed: The operation was canceled."` when called while Unity is mid-compilation / mid-domain-reload, even though the tool is supposed to handle this case via `AssetDatabase.Refresh()` that should wait for compilation to complete.

### Evidence from production logs (2026-05-16)

C# backend log (`Rider2026.1/backend.2026-05-04_0140.49406.log`):
```
00:44:25.516 | UnityCompilationMcpHandler: GetCompilationResult handler invoked
00:44:25.516 | RefreshAndCheckCompilation: starting Refresh
00:44:31.429 | Refresh threw (likely domain reload): The operation was canceled.
00:44:31.429 | waiting for Unity model reconnection
00:44:31.429 | Unity model available, calling GetCompilationResult  ← same millisecond
```

The second `WaitForUnityModel` resolves in the same millisecond as the Refresh cancellation. This indicates a race condition: `BackendUnityModel` is still pointing at the stale (about-to-be-torn-down) instance because Rider has not yet detected Unity's disconnection. `GetCompilationResult.Start()` on this stale model is immediately cancelled by the Rd layer.

A follow-up call 4 seconds later then hits `"Unity Editor did not connect within 30 seconds."` because the second domain reload (triggered by the first call's `Refresh.Start(ForceDomainReload)`) is still in progress.

### E2E test coverage gap

`docs/e2e-tests.md` test 2-2 ("Compilation error") instructs the test runner to "Wait for Unity to detect the compilation error" before calling the tool — i.e., compilation is always finished before the tool is exercised. The realistic scenario (calling the tool while compilation is in progress) is implicit only in the "Retry Rules" footnote, never actually validated.

### Intended outcome

- The tool returns the compilation result correctly even when invoked during an active compilation / domain reload (race fixed in the C# handler).
- If a residual cancellation still occurs, the error message tells the calling agent to wait and retry instead of leaving it ambiguous.
- E2E test 2-2 exercises the realistic timing (no pre-wait).

## Approach

### 1. Race condition fix (C# handler)

After `Refresh.Start(...)` throws (the documented "likely domain reload" path), the handler currently calls `RdConnectionHelper.WaitForUnityModel(...)` which uses `host.BackendUnityModel.Advise(...)` — `Advise` fires immediately with the *current* value, so it returns the stale instance.

Fix: introduce a new helper `WaitForModelReconnect(previousModel, ...)` that resolves only when `BackendUnityModel` becomes non-null **and is a different instance** from the captured pre-Refresh model. Use it from the `catch` branch (where we know a reconnect is in progress); keep the existing `WaitForUnityModel` for the no-exception path (no reconnect expected).

### 2. Error message improvement (C# handler)

In the `catch` around `GetCompilationResult.Start(...)`, when the exception is `OperationCanceledException` (Rd cancellation surfaces as this), return a message that tells the agent to retry:

> "Unity is currently compiling or reloading assemblies (the request was cancelled mid-flight). Wait a few seconds and retry."

For other exception types, keep the current `"GetCompilationResult failed: {message}"` format.

### 3. E2E test 2-2 modification

Remove the "Wait for Unity to detect the compilation error" step so the tool is called immediately after the code is modified. With the race condition fix, the tool's internal `Refresh.Start()` is responsible for waiting for compilation to complete and the test should pass on the first call.

## Critical files

- `src/dotnet/McpExtensionUnity/RdConnectionHelper.cs` — add `WaitForModelReconnect` helper. Reuse the existing `Advise` pattern from `WaitForUnityModel` (lines 17-37); the only difference is the condition `m != null && !ReferenceEquals(m, previousModel)`.
- `src/dotnet/McpExtensionUnity/UnityCompilationMcpHandler.cs` — capture model reference before Refresh (around line 66). Branch the second model-wait based on whether Refresh threw (lines 86-95). Differentiate the catch around `GetCompilationResult.Start(...)` (lines 112-115) by exception type.
- `docs/e2e-tests.md` — remove step 2 ("Wait for Unity to detect the compilation error") from test 2-2 (line 222).

## Test design

### Test Cases of dotnet tests

This repo currently has **no C# test project** (only `src/test/kotlin/...`). The race condition is a timing-dependent Rd interaction that cannot be exercised meaningfully without an integration harness — setting one up is out of scope for this fix. Verification therefore relies on the E2E test below. The new helper `WaitForModelReconnect` is small and structurally identical to `WaitForUnityModel`, making code review the practical assurance.

### E2E Tests

| # | Item | Verification Method |
|---|------|---------------------|
| 1 | `get_unity_compilation_result` called immediately after introducing a compilation error returns `success=false` with the error in `logs` (no manual pre-wait) | E2E test 2-2 in `docs/e2e-tests.md` (modified) |
| 2 | `get_unity_compilation_result` called immediately after introducing valid code returns `success=true` | Run test 2-1 again with a small `.cs` edit just before invocation (validates the no-exception path is unaffected) |
| 3 | Two rapid successive `get_unity_compilation_result` calls during compilation both eventually succeed (or return the helpful retry message) without hanging | Manually trigger two calls back-to-back after a `.cs` edit; observe the second call no longer times out at 30s |

## Development workflow

### Step 1: Skeleton (Compilable)

Add `WaitForModelReconnect` method signature with `throw new NotImplementedException()` body in `RdConnectionHelper.cs`. Build with `./gradlew --no-configuration-cache buildPlugin` to confirm it compiles.

### Step 2: Test First

Skipped — no C# test infrastructure. (See "Test Cases of dotnet tests" rationale above.) Commit the skeleton with a note in the commit message that verification is E2E-only for this change.

### Step 3: Implementation

1. Implement `WaitForModelReconnect` body in `RdConnectionHelper.cs` (mirror of `WaitForUnityModel` with the `!ReferenceEquals` guard).
2. In `UnityCompilationMcpHandler.cs` `RefreshAndCheckCompilation`:
   - Capture `var modelBeforeRefresh = unityModel;` after the first `WaitForUnityModel` succeeds.
   - Track whether the Refresh `catch` fired (e.g., a local `bool refreshThrew = false;` set in the catch).
   - When `refreshThrew`, call the new `WaitForModelReconnect(_host, _rdQueue, lt, modelBeforeRefresh, TimeSpan.FromMinutes(2))` instead of `WaitForUnityModel`.
   - When `!refreshThrew`, keep the existing `WaitForUnityModel` call (no domain reload happened).
   - Update the `catch` block at lines 112-115 to branch on `OperationCanceledException` and return the friendly retry message.
3. Resolve diagnostics with `mcp__jetbrains__get_file_problems` on the two modified `.cs` files.
4. Build the plugin and install in Rider for manual verification.
5. Commit.

### Step 4: Refactoring

1. Re-review DRY/KISS: the two model-wait helpers (`WaitForUnityModel` and `WaitForModelReconnect`) duplicate the `Advise` + `Task.WhenAny` skeleton. If duplication feels heavy, consider a single internal method parameterised by a `Func<BackendUnityModel, bool>` predicate (only refactor if it stays clearer than two named methods).
2. Resolve `suggestion` severity diagnostics.
3. Reformat the modified `.cs` files via `mcp__jetbrains__reformat_file`.
4. Commit.

### Step 5: E2E Tests

1. Edit `docs/e2e-tests.md`:
   - Delete step 2 of test 2-2: `"Wait for Unity to detect the compilation error"`.
   - Renumber the subsequent steps in 2-2.
   - Consider tightening the Retry Rules note for `get_unity_compilation_result` — after this fix, the first call should normally succeed, so the retry rule becomes a safety net rather than the expected path.
2. Create `docs/plans/{plan-file-name}-e2e-tests.md` with the new/changed test scenarios for this fix (entries from the E2E table above).
3. Execute the E2E suite manually with a Unity project to validate.

## Verification

End-to-end validation:

1. Build & install the plugin in Rider (`./gradlew --no-configuration-cache buildPlugin`, install via Settings → Plugins → Install Plugin from Disk).
2. Open a Unity project and Rider against the same project.
3. Run the modified E2E test 2-2 (introduce a compile error, immediately invoke `get_unity_compilation_result`). Expect `success=false` with the error in `logs` on the first call.
4. Tail `~/Library/Logs/JetBrains/Rider2026.1/backend.2026-05-04_0140.49406.log` and confirm:
   - `RefreshAndCheckCompilation: Refresh threw (likely domain reload): ...` is followed by a measurable delay (not the same millisecond) before `Unity model available, calling GetCompilationResult`.
   - No `GetCompilationResult failed: The operation was canceled.` in normal flow.
5. Trigger two back-to-back tool calls (modify a `.cs` file, then call the tool twice quickly). Confirm neither hits the 30-second connect timeout.
6. Run the full E2E suite in `docs/e2e-tests.md` to verify no regression elsewhere.
