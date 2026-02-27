# Plan: Remove compilation check from RunTests handler

## Context

The `RunTests` handler in `UnityTestMcpHandler.cs` currently calls `RefreshAndCheckCompilation` before dispatching tests to Unity Editor. This automatic pre-flight check duplicates what the standalone `get_unity_compilation_result` tool provides. Removing it:
- Reduces test execution latency (no forced `AssetDatabase.Refresh()` + compilation wait)
- Gives the agent explicit control over when to check compilation
- Simplifies the `RunTests` handler's threading flow

## Changes

### `src/dotnet/McpExtensionUnity/UnityTestMcpHandler.cs`

**Remove lines 72–78** (the compilation check call and surrounding comments/logs):

```csharp
// DELETE these lines:
// Refresh assets and check compilation before running tests.
// After this await, execution continues on a .NET ThreadPool thread.
ourLogger.Info("UnityTestMcpHandler: RunTests - starting compilation check");
var compilationResult = await RefreshAndCheckCompilation(lt, backendUnityHost, rdQueue).ConfigureAwait(false);
if (!compilationResult.Success)
    return ErrorResponse(compilationResult.ErrorMessage);
ourLogger.Info("UnityTestMcpHandler: RunTests - compilation check passed");
```

**Update comment at lines 110–112** — after removal, no `await` has occurred yet, so the handler is still on the Rd scheduler thread (not a TP worker):

```csharp
// BEFORE:
// All Rd operations (Advise, property set, RPC start) must run on the Rd scheduler thread.
// We are currently on a TP worker thread (continuation after the compilation check await),
// so marshal back to the scheduler before touching any Rd objects.

// AFTER:
// All Rd operations (Advise, property set, RPC start) must run on the Rd scheduler thread.
// Although we are still on the Rd scheduler thread here (no prior await),
// we use ScheduleOnRd to keep the pattern consistent and future-proof.
```

No methods become unused — `RefreshAndCheckCompilation`, `WaitForUnityModel`, `CompilationErrorResponse`, `AwaitRdTask<T>` are all still reachable via the `GetCompilationResult` handler.

## No test code changes needed

This change removes behavior only. No new logic is introduced, so no new test cases are required. The existing Kotlin unit tests in `RunUnityTestsToolsetTest.kt` test input validation on the Kotlin side, which is unaffected.

### Manual Tests

| # | Item | Verification Method |
|---|------|---------------------|
| 1 | `run_unity_tests` succeeds without prior compilation check when code is already compiled | Run tests from Claude Code on a clean project — verify tests execute |
| 2 | `run_unity_tests` with compilation errors produces a clear failure | Introduce a syntax error, skip `get_unity_compilation_result`, run tests — verify error message from Unity |
| 3 | `get_unity_compilation_result` still works standalone | Call the tool directly — verify it returns compilation status |

## Development Workflow

### Step 1: Implementation

1. Edit `UnityTestMcpHandler.cs` — remove the compilation check call and update the threading comment.
2. Resolve diagnostics using `mcp__jetbrains__get_file_problems`.
3. Build: `JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew --no-configuration-cache buildPlugin`

### Step 2: Refactoring

1. Reformat modified file using `mcp__jetbrains__reformat_file`.
2. Commit to git.
