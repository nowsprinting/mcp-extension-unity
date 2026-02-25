# Fix Rd Scheduler Threading Bug in UnityTestMcpHandler

## Context

After running `run_unity_tests` (success), calling `get_unity_compilation_result` causes Unity Editor disconnection.
Rider logs show `Illegal scheduler for current action, must be: Shell Rd Dispatcher on :1, current thread: .NET TP Worker` errors cascading across multiple Rd operations (`getCompilationResult`, `unitTestLaunch`, `runStarted`, `testResult`, `runResult`, `abort`, `runUnitTestLaunch`).

**Root cause**: In `UnityTestMcpHandler.cs`, `RefreshAndCheckCompilation` uses `await ... .ConfigureAwait(false)`, which moves async continuations to .NET ThreadPool worker threads. Subsequent Rd protocol operations (which require the Rd Shell Dispatcher thread) are called from the wrong thread.

**Why it intermittently works**: When `AssetDatabase.Refresh()` is a no-op (no domain reload), awaits complete synchronously and the thread stays on the Rd scheduler. When domain reload occurs, awaits complete asynchronously and continuations move to TP workers.

## Files to Modify

- `src/dotnet/McpExtensionUnity/UnityTestMcpHandler.cs` — the only file that needs changes

## Reference

- resharper-unity uses `protocol.Scheduler.Queue()` / `mySolution.Locks.ExecuteOrQueueEx()` to marshal back to the Rd thread after async operations. They avoid `async/await` + `ConfigureAwait(false)` for Rd operations entirely.
- `IScheduler.Queue(Action)` — queues an action on the Rd protocol dispatcher thread
- `IProtocol.Scheduler` — access to the Rd scheduler (already injected via constructor `IProtocol protocol`)

## Implementation Plan

### Fix Approach: `protocol.Scheduler.Queue()` for Rd operations

Add a helper method to schedule Rd calls from any thread and return a `Task<T>`:

```csharp
private static Task<T> ScheduleOnRd<T>(IScheduler scheduler, Func<T> func)
{
    var tcs = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
    scheduler.Queue(() =>
    {
        try { tcs.SetResult(func()); }
        catch (Exception e) { tcs.SetException(e); }
    });
    return tcs.Task;
}

// Void overload for fire-and-forget Rd operations
private static Task ScheduleOnRd(IScheduler scheduler, Action action)
{
    var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
    scheduler.Queue(() =>
    {
        try { action(); tcs.SetResult(true); }
        catch (Exception e) { tcs.SetException(e); }
    });
    return tcs.Task;
}
```

### Fix 1: `RefreshAndCheckCompilation` — add `IScheduler` parameter

Change signature: `private static async Task<McpCompilationResponse> RefreshAndCheckCompilation(Lifetime lt, BackendUnityHost host, IScheduler scheduler)`

Wrap Rd calls at:
- **Line 208**: `model.Refresh.Start(lt, RefreshType.Normal)` — schedule on Rd thread
- **Line 236**: `reconnectedModel.GetCompilationResult.Start(lt, Unit.Instance)` — schedule on Rd thread

```csharp
// Line 208: before
var refreshTask = AwaitRdTask(lt, model.Refresh.Start(lt, RefreshType.Normal));
// Line 208: after
var rdRefreshTask = await ScheduleOnRd(scheduler, () => model.Refresh.Start(lt, RefreshType.Normal));
var refreshTask = AwaitRdTask(lt, rdRefreshTask);

// Line 236: before
var compileTask = AwaitRdTask(lt, reconnectedModel.GetCompilationResult.Start(lt, Unit.Instance));
// Line 236: after
var rdCompileTask = await ScheduleOnRd(scheduler, () => reconnectedModel.GetCompilationResult.Start(lt, Unit.Instance));
var compileTask = AwaitRdTask(lt, rdCompileTask);
```

### Fix 2: `RunTests` handler — wrap post-compilation Rd operations

After `await RefreshAndCheckCompilation(...)` returns (on TP worker), lines 75–145 contain Rd operations. Wrap them in a single `ScheduleOnRd` call:

```csharp
// After RefreshAndCheckCompilation (on TP worker thread now)
// Create TCS outside scheduler (thread-safe)
var tcs = new TaskCompletionSource<RunResult>(TaskCreationOptions.RunContinuationsAsynchronously);

// Marshal all Rd operations to the scheduler thread
await ScheduleOnRd(protocol.Scheduler, () =>
{
    backendUnityModel = backendUnityHost.BackendUnityModel.Value;
    if (backendUnityModel == null) { tcs.TrySetException(new Exception("...")); return; }

    lt.OnTermination(() => tcs.TrySetCanceled());
    backendUnityHost.BackendUnityModel.Advise(lt, ...);
    launch.TestResult.Advise(lt, ...);
    launch.RunResult.Advise(lt, ...);
    backendUnityModel.UnitTestLaunch.Value = launch;
    backendUnityModel.RunUnitTestLaunch.Start(lt, Unit.Instance);
});

// Await TCS with timeout (on TP worker — TCS is thread-safe)
```

### Fix 3: `TryAbortLaunch` — wrap in scheduler

Change signature: `private static void TryAbortLaunch(Lifetime lt, BackendUnityHost host, UnitTestLaunch launch, IScheduler scheduler)`

Wrap Rd operations (`host.BackendUnityModel.Value`, `model.UnitTestLaunch.Value`, `launch.Abort.Start()`) inside `scheduler.Queue()`.

### Fix 4: Update call sites

- `RunTests` handler line 69: pass `protocol.Scheduler` to `RefreshAndCheckCompilation`
- `RunTests` handler lines 164, 170: pass `protocol.Scheduler` to `TryAbortLaunch`
- `GetCompilationResult` handler line 185: pass `protocol.Scheduler` to `RefreshAndCheckCompilation`

## Development Workflow

### Step 1: Skeleton (Compilable)

Add `ScheduleOnRd` helper methods. Update method signatures to accept `IScheduler`. Compile to verify.

### Step 2: Test First

Not applicable — no unit-testable logic change. The fix is thread scheduling, which requires Rd infrastructure that cannot be mocked in the current test setup.

### Step 3: Implementation

1. Implement all 4 fixes in `UnityTestMcpHandler.cs`
2. Resolve diagnostics using `mcp__jetbrains__get_file_problems`
3. Build: `JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew --no-configuration-cache buildPlugin`
4. Commit to git

### Step 4: Refactoring

1. Review for DRY/KISS — ensure `ScheduleOnRd` helpers are minimal
2. Resolve diagnostics at suggestion level
3. Reformat with `mcp__jetbrains__reformat_file`
4. Commit to git

### Manual Tests

| # | Item | Verification Method |
|---|------|---------------------|
| 1 | `get_unity_compilation_result` works standalone | Call via MCP, check success response, verify no `Illegal scheduler` in Rider log |
| 2 | `run_unity_tests` works standalone | Call via MCP with a valid test, check success response |
| 3 | `run_unity_tests` then `get_unity_compilation_result` sequentially | Call both in sequence, verify both succeed, no `Illegal scheduler` errors |
| 4 | `run_unity_tests` triggers domain reload scenario | Modify a script, then run tests — verify compilation check and test execution succeed |
| 5 | Unity Editor disconnected during operation | Close Unity while tool is running — verify graceful error message, no crash |
