# Plan: Add Initial Connection Wait to All MCP Tools

## Context

When a coding agent creates or modifies `.cs` files in a Unity project, Unity triggers a domain reload
(script recompilation). During this reload (typically 5-30 seconds), the EditorPlugin's Rd protocol
connection temporarily drops (BackendUnityModel becomes null). If an MCP tool is called during this
window, it fails immediately with "Unity Editor is not connected to Rider."

All 4 MCP tools currently fail-fast on this check. The fix adds a short wait-for-connection at the
initial connection check, covering the domain-reload gap without requiring the user to retry manually.

**Root cause verified via Rider log**: `.cs` files created at 09:31 triggered domain reload;
`get_unity_compilation_result` called at 09:31:54 failed; `ProtocolInstance.json` updated at 09:33
(connection re-established). TCP connection was confirmed ESTABLISHED after reconnection.

## Approach

Add a **30-second initial connection wait** to all 4 tools. If Unity reconnects within 30 seconds
(typical for domain reload), the tool proceeds. If not, it returns the existing "not connected" error.

### Architecture

Two layers need changes, matching the existing split:

| Tool | Connection wait location |
|------|------------------------|
| `run_unity_tests` | C# handler (`UnityTestMcpHandler.cs`) |
| `get_unity_compilation_result` | C# handler (`UnityCompilationMcpHandler.cs`) |
| `run_method_in_unity` | Kotlin toolset (`RunMethodInUnityToolset.kt`) |
| `unity_play_control` | Kotlin toolset (`PlayControlToolset.kt`) |

### Reusable patterns found

- **C# side**: `WaitForUnityModel()` already exists in both handlers (duplicated). Uses `BackendUnityModel.Advise` + `TaskCompletionSource` + `Task.WhenAny` with timeout.
  - `UnityTestMcpHandler.cs:248` (static, takes `host`, `rdQueue`, `lt`, `timeout`)
  - `UnityCompilationMcpHandler.cs:129` (instance, uses `_rdQueue`, `_host`)
- **Kotlin side**: `IOptProperty<Boolean>.nextTrueValue()` suspend extension (from `rd.jar` `ISourceCoroutineUtilKt`) returns immediately if already `true`, or suspends until `true`.

## Files to Modify

### New file: `src/main/kotlin/.../EditorConnectionUtils.kt`

Shared Kotlin utility for Kotlin-side connection waiting.

```kotlin
object EditorConnectionUtils {
    const val CONNECTION_WAIT_TIMEOUT_MS = 30_000L

    /**
     * Waits for Unity Editor connection.
     * Returns true immediately if already connected, or suspends until connected or timeout.
     */
    suspend fun awaitEditorConnection(
        connectedProperty: IOptProperty<Boolean>,
        timeoutMs: Long = CONNECTION_WAIT_TIMEOUT_MS
    ): Boolean {
        if (connectedProperty.valueOrDefault(false)) return true
        return try {
            withTimeout(timeoutMs) { connectedProperty.nextTrueValue() }
            true
        } catch (e: TimeoutCancellationException) {
            false
        }
    }
}
```

### Modify: `src/main/kotlin/.../RunMethodInUnityToolset.kt`

Replace immediate `isConnectedToEditor()` check (line 69-71) with:
```kotlin
val fbModel = solution.frontendBackendModel
if (!EditorConnectionUtils.awaitEditorConnection(fbModel.unityEditorConnected)) {
    return RunMethodInUnityErrorResult(
        "Unity Editor is not connected to Rider. Please open Unity Editor with the project.")
}
```

### Modify: `src/main/kotlin/.../PlayControlToolset.kt`

Replace immediate `isConnectedToEditor()` check (line 62-64) with same pattern.

### New file: `src/dotnet/McpExtensionUnity/RdConnectionHelper.cs`

Extract shared C# utilities from the two handlers into a static helper class:

```csharp
internal static class RdConnectionHelper
{
    /// Waits for BackendUnityModel to become non-null. Returns immediately if already connected.
    /// Advise call is scheduled on the Rd scheduler thread via rdQueue.
    internal static async Task<BackendUnityModel> WaitForUnityModel(
        BackendUnityHost host, Action<Action> rdQueue, Lifetime lt, TimeSpan timeout) { ... }

    /// Schedules action on the Rd scheduler thread. Returns Task that completes when done.
    internal static Task ScheduleOnRd(Action<Action> rdQueue, Action action) { ... }

    /// Schedules func on the Rd scheduler thread. Returns Task<T> with the result.
    internal static Task<T> ScheduleOnRd<T>(Action<Action> rdQueue, Func<T> func) { ... }

    /// Converts IRdTask<T> to Task<T> using Advise on the result property.
    internal static Task<T> AwaitRdTask<T>(Lifetime lt, IRdTask<T> rdTask) { ... }
}
```

Consolidates:
- `WaitForUnityModel` from `UnityTestMcpHandler.cs:248` (static version) and `UnityCompilationMcpHandler.cs:129` (instance version)
- `ScheduleOnRd` from `UnityTestMcpHandler.cs:235` (void version) and `UnityCompilationMcpHandler.cs:174` (generic version)
- `AwaitRdTask` from `UnityCompilationMcpHandler.cs:153`

### Modify: `src/dotnet/McpExtensionUnity/UnityCompilationMcpHandler.cs`

1. Replace initial connection check (lines 52-59) with `RdConnectionHelper.WaitForUnityModel` call:
```csharp
var unityModel = await RdConnectionHelper.WaitForUnityModel(
    _host, _rdQueue, lt, TimeSpan.FromSeconds(30)).ConfigureAwait(false);
if (unityModel == null)
    return CompilationErrorResponse(
        "Unity Editor did not connect within 30 seconds. Please open Unity Editor with the project.");
```
2. Replace internal `WaitForUnityModel`, `ScheduleOnRd`, `AwaitRdTask` calls with `RdConnectionHelper.*`
3. Remove the now-unused private methods

### Modify: `src/dotnet/McpExtensionUnity/UnityTestMcpHandler.cs`

1. Replace initial connection check (lines 61-69) with `RdConnectionHelper.WaitForUnityModel` call:
```csharp
var initialModel = await RdConnectionHelper.WaitForUnityModel(
    backendUnityHost, rdQueue, lt, TimeSpan.FromSeconds(30)).ConfigureAwait(false);
if (initialModel == null)
    return ErrorResponse(
        "Unity Editor did not connect within 30 seconds. Please open Unity Editor with the project.");
```
2. Replace internal `WaitForUnityModel`, `ScheduleOnRd` calls with `RdConnectionHelper.*`
3. Remove the now-unused private static methods
4. Remove the redundant `backendUnityModel` null check inside the `ScheduleOnRd` block (lines 102-108)
   since `initialModel` is now guaranteed non-null when we reach that point.

### New file: `src/test/kotlin/.../EditorConnectionUtilsTest.kt`

Unit tests for the shared Kotlin utility using `RdOptionalProperty<Boolean>()`.

## Test Cases

### Test Cases of kotlin tests

#### EditorConnectionUtilsTest

| Test Method | Description |
|---|---|
| `awaitEditorConnection_AlreadyConnected_ReturnsTrue` | Property already true -> returns true immediately |
| `awaitEditorConnection_NotSetThenBecomesTrue_ReturnsTrue` | Property unset, set to true within timeout -> returns true |
| `awaitEditorConnection_FalseThenBecomesTrue_ReturnsTrue` | Property false, set to true within timeout -> returns true |
| `awaitEditorConnection_NotConnectedTimeout_ReturnsFalse` | Property never set -> returns false after timeout |
| `awaitEditorConnection_FalseTimeout_ReturnsFalse` | Property stays false -> returns false after timeout |
| `awaitEditorConnection_DefaultTimeout_Is30Seconds` | Verify default timeout constant is 30000ms |

### Manual Tests

| # | Item | Verification Method |
|---|------|---------------------|
| 1 | `get_unity_compilation_result` succeeds after domain reload | Create a `.cs` file via `create_new_file` MCP tool, immediately call `get_unity_compilation_result` -> should wait and succeed |
| 2 | `run_unity_tests` succeeds after domain reload | Create a `.cs` file, immediately call `run_unity_tests` -> should wait and succeed |
| 3 | `run_method_in_unity` succeeds after domain reload | Create a `.cs` file, immediately call `run_method_in_unity` -> should wait and succeed |
| 4 | `unity_play_control` succeeds after domain reload | Create a `.cs` file, immediately call `unity_play_control` with `status` action -> should wait and succeed |
| 5 | Tools fail when Unity is genuinely not running | Close Unity Editor, call any tool -> should fail after 30-second wait with clear error |
| 6 | Tools succeed immediately when already connected | With Unity connected, call any tool -> should NOT wait 30 seconds, proceed immediately |

## Development Workflow

### Step 1: Skeleton (Compilable)

1. Create `EditorConnectionUtils.kt` with the function signature and stub body
2. Build to verify compilation

### Step 2: Test First

1. Create `EditorConnectionUtilsTest.kt` with all 6 test cases
2. Run tests, confirm they fail (skeleton returns incorrect values)
3. Commit

### Step 3: Implementation

1. Implement `EditorConnectionUtils.awaitEditorConnection()`
2. Run Kotlin tests, confirm they pass
3. Create `RdConnectionHelper.cs` — extract `WaitForUnityModel`, `ScheduleOnRd`, `AwaitRdTask`
4. Modify `UnityCompilationMcpHandler.cs` — replace initial check + use `RdConnectionHelper`
5. Modify `UnityTestMcpHandler.cs` — replace initial check + use `RdConnectionHelper`
6. Modify `RunMethodInUnityToolset.kt` — replace `isConnectedToEditor()` with `awaitEditorConnection()`
7. Modify `PlayControlToolset.kt` — replace `isConnectedToEditor()` with `awaitEditorConnection()`
8. Resolve diagnostics with `mcp__jetbrains__get_file_problems`
9. Run all tests (`./gradlew test`), confirm they pass
10. Build plugin (`./gradlew buildPlugin`), confirm it builds
11. Commit

### Step 4: Refactoring

1. Remove unused `isConnectedToEditor()` imports from toolset files
2. Remove now-unused private methods from both C# handlers
3. Resolve diagnostics at suggestion level
4. Reformat modified files with `mcp__jetbrains__reformat_file`
5. Run all tests, confirm they pass
6. Commit

## Verification

1. `JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew --no-configuration-cache test`
2. `JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew --no-configuration-cache buildPlugin`
3. Install plugin ZIP in Rider, restart
4. Open Unity project, create a `.cs` file, immediately call `get_unity_compilation_result` -> should wait and succeed
