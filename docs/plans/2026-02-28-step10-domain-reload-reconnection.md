# Plan: Add domain-reload reconnection handling to UnityTestMcpHandler

## Context

When running Unity tests via `UnityTestMcpHandler`, a domain reload in Unity Editor temporarily disconnects the `BackendUnityModel` (transitions: non-null → null → non-null). The current code (line 123-129) immediately fails with `TrySetException("Unity Editor disconnected...")` on the null transition, killing the test run prematurely.

Domain reload can happen as part of test startup (Unity reloads assemblies before executing tests), depending on the Unity project settings. `UnityCompilationMcpHandler` already handles this gracefully with `WaitForUnityModel` (lines 129-148). The test handler should do the same.

## Approach

Replace the immediate-fail `BackendUnityModel.Advise` pattern with a reconnection-aware pattern:

1. When `BackendUnityModel` becomes null → don't fail; spawn async reconnection wait
2. After reconnection → create a new `UnitTestLaunch`, subscribe signals, set on new model, re-launch
3. If reconnection times out (2 min) → then fail

### Key Design Decisions

- **Static method style**: Keep the existing constructor-local-capture pattern (no instance field refactoring). Add `WaitForUnityModel` as a static method taking `BackendUnityHost` and `Action<Action>` as parameters
- **New launch per reconnection**: Create a fresh `UnitTestLaunch` after reconnection (safer than reusing, avoids Rd re-binding issues). Subscribe its `TestResult`/`RunResult` to the shared `tcs`/`testResults`
- **Concurrency guard**: Use `Interlocked.CompareExchange` on a `reconnecting` flag to prevent concurrent reconnection attempts from multiple null transitions
- **Separate timeouts**: Reconnection timeout (2 min, hardcoded) is independent of `MCP_TOOL_TIMEOUT` (overall test timeout)
- **`Task.Run` for async reconnection**: The `Advise` callback is synchronous on the Rd thread; reconnection wait must be async, so `Task.Run` is used

### Files to Modify

| File | Change |
|------|--------|
| `src/dotnet/McpExtensionUnity/UnityTestMcpHandler.cs` | Add reconnection logic (primary change) |
| `CLAUDE.md` | Update constraint #6 and roadmap |

### Reference Code to Reuse

- `UnityCompilationMcpHandler.WaitForUnityModel` (lines 129-148) — adapt as static method in `UnityTestMcpHandler`

### Changes in `UnityTestMcpHandler.cs`

#### 1. Add `WaitForUnityModel` static method

```csharp
private static async Task<BackendUnityModel> WaitForUnityModel(
    BackendUnityHost host, Action<Action> rdQueue, Lifetime lt, TimeSpan timeout)
{
    var tcs = new TaskCompletionSource<BackendUnityModel>(
        TaskCreationOptions.RunContinuationsAsynchronously);
    rdQueue(() =>
    {
        host.BackendUnityModel.Advise(lt, m =>
        {
            if (m != null) tcs.TrySetResult(m);
        });
    });
    var reconnectTask = tcs.Task;
    var timeoutTask = Task.Delay(timeout);
    if (await Task.WhenAny(reconnectTask, timeoutTask).ConfigureAwait(false) != reconnectTask)
        return null;
    return await reconnectTask.ConfigureAwait(false);
}
```

#### 2. Extract `LaunchTests` helper (runs on Rd scheduler thread)

Encapsulates: create launch → subscribe signals → set on model → start. Called both initially and after reconnection.

```csharp
private static UnitTestLaunch LaunchTests(
    BackendUnityModel model, Lifetime lt,
    List<TestFilter> testFilters, TestMode testMode,
    ConcurrentDictionary<string, TestResult> testResults,
    TaskCompletionSource<RunResult> tcs)
{
    var launch = new UnitTestLaunch(
        sessionId: Guid.NewGuid(),
        testFilters: testFilters,
        testMode: testMode,
        clientControllerInfo: null);

    launch.TestResult.Advise(lt, result => { /* collect terminal */ });
    launch.RunResult.Advise(lt, runResult => { tcs.TrySetResult(runResult); });

    model.UnitTestLaunch.Value = launch;
    model.RunUnitTestLaunch.Start(lt, Unit.Instance);
    return launch;
}
```

#### 3. Replace `BackendUnityModel.Advise` in ScheduleOnRd block

Replace the fail-on-null pattern (lines 123-129) with:

```csharp
var reconnecting = 0;

_host.BackendUnityModel.Advise(lt, unityModel =>
{
    if (unityModel == null)
    {
        if (Interlocked.CompareExchange(ref reconnecting, 1, 0) != 0) return;
        ourLogger.Info("BackendUnityModel became null, waiting for reconnection...");
        Task.Run(async () =>
        {
            try
            {
                var reconnected = await WaitForUnityModel(
                    backendUnityHost, rdQueue, lt, TimeSpan.FromMinutes(2));
                if (reconnected == null)
                {
                    tcs.TrySetException(new Exception(
                        "Unity Editor did not reconnect within 2 minutes."));
                    return;
                }
                await ScheduleOnRd(rdQueue, () =>
                {
                    currentLaunch = LaunchTests(reconnected, lt, ...);
                });
            }
            finally { Interlocked.Exchange(ref reconnecting, 0); }
        });
    }
});
```

#### 4. Update `TryAbortLaunch`

Track `currentLaunch` (via a captured variable) to abort the latest launch, not the original.

### Thread Safety

| Operation | Thread | Safe? |
|-----------|--------|-------|
| Advise callback | Rd scheduler | Yes |
| Task.Run (reconnection) | TP worker | Yes — captures immutable/thread-safe refs |
| WaitForUnityModel inner Advise | Rd scheduler via rdQueue | Yes |
| tcs.TrySetException/TrySetResult | Any (RunContinuationsAsynchronously) | Yes |
| Interlocked on `reconnecting` | Any | Atomic |
| testResults ConcurrentDictionary | Any | Thread-safe |

### Manual Tests

| # | Scenario | Expected Result | Verification |
|---|----------|-----------------|--------------|
| 1 | EditMode test, no domain reload | Tests pass as before (no regression) | Run EditMode tests via MCP tool |
| 2 | PlayMode test (triggers domain reload) | Handler waits for reconnection, re-launches, tests complete | Run PlayMode tests via MCP tool |
| 3 | Unity Editor closed during test execution | Fails after 2 min: "did not reconnect" | Close Unity during test run |
| 4 | MCP_TOOL_TIMEOUT expires during reconnection | Overall timeout fires: "timed out after N seconds" | Set MCP_TOOL_TIMEOUT=10, trigger domain reload |
| 5 | Kotlin coroutine cancelled during reconnection | TrySetCanceled fires, Advise cleaned up by lifetime | Cancel from Kotlin side |

## Development Workflow

### Step 1: Skeleton (Compilable)

1. Add `WaitForUnityModel` static method signature (return `Task.FromResult<BackendUnityModel>(null)`)
2. Add `LaunchTests` helper method signature
3. Verify compilation with `mcp__jetbrains__build_project`

### Step 2: Implementation

1. Implement `WaitForUnityModel` (adapt from `UnityCompilationMcpHandler`)
2. Implement `LaunchTests` helper
3. Replace the `BackendUnityModel.Advise` block with reconnection logic
4. Update `TryAbortLaunch` to use tracked `currentLaunch`
5. Resolve diagnostics with `mcp__jetbrains__get_file_problems`
6. Build: `JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew --no-configuration-cache buildPlugin`
7. Commit

### Step 3: Refactoring

1. Review and simplify if needed (DRY, KISS)
2. Resolve diagnostics at `suggestion` or higher
3. Reformat with `mcp__jetbrains__reformat_file`
4. Commit

### Step 4: Documentation

1. Update `CLAUDE.md` constraint #6 and roadmap
2. Commit
