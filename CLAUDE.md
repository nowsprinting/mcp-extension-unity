# CLAUDE.md — mcp-extension-unity

## Project Overview

A Rider IDE plugin (PoC stage) that extends the built-in JetBrains MCP Server with a custom MCP tool.
The goal is to allow Coding Agents (e.g., Claude Code) to run Unity tests through Rider's test infrastructure,
rather than invoking Unity directly.

**Current status**: Steps 1–11 complete. Initial connection wait added in Step 11.

## Architecture

```
Coding Agent (Claude Code)
    ↓ MCP (HTTP/SSE)
JetBrains MCP Server (built into Rider 2025.3+)
    ↓ extension point (com.intellij.mcpServer)
[This Plugin — Kotlin Frontend]   ← UnityEditorToolset.kt
    ↓ UnityTestMcpModel (custom Rd: IRdCall<McpRunTestsRequest, McpRunTestsResponse>)
[Plugin Backend — C# / UnityTestMcpHandler]
    ↓ BackendUnityModel.UnitTestLaunch + RunUnitTestLaunch (existing Rd)
Unity Editor
    ↓ TestRunnerApi.Execute()
Test execution (results via TestResult/RunResult signals)
```

Rider itself uses two separate Rd (Reactive Distributed) protocol connections:

- **Kotlin Frontend ↔ C# Backend**: `FrontendBackendModel`
- **C# Backend ↔ Unity Editor**: `BackendUnityModel`

The Kotlin Frontend **cannot** directly access `BackendUnityModel`; a custom Rd model (`UnityTestMcpModel`) bridges the two layers, implemented in Step 6.

## Tech Stack

| Item          | Value                                                        |
|---------------|--------------------------------------------------------------|
| Language      | Kotlin 2.3.0                                                 |
| Serialization | kotlinx-serialization 1.6.3 (`compileOnly`)                  |
| Build plugin  | IntelliJ Platform Gradle Plugin 2.11.0                       |
| Target IDE    | Rider 2025.3.3 (build `RD-253.31033.136`)                    |
| JDK           | JBR 25.0.2 (`~/Library/Java/JavaVirtualMachines/jbr-25.0.2`) |
| Gradle        | 9.3.1                                                        |

## Key Files

```
mcp-extension-unity/
├── CLAUDE.md                                          # this file
├── build.gradle.kts                                   # build configuration (incl. compileDotNet, prepareSandbox)
├── gradle.properties
├── settings.gradle.kts
├── protocol/                                          # Rd model definition (rdgen)
│   └── src/main/kotlin/model/rider/
│       ├── UnityTestMcpModel.kt                       # Rd DSL: McpRunTestsRequest/Response
│       └── UnityCompilationMcpModel.kt                # Rd DSL: compilation result model
├── src/main/
│   ├── kotlin/com/nowsprinting/mcp_extension_unity/   # Kotlin frontend: McpToolset implementations and Rd model providers
│   ├── generated/                                     # auto-generated Kotlin model (gitignored)
│   └── resources/META-INF/
│       └── plugin.xml                                 # plugin descriptor
├── src/test/
│   └── kotlin/com/nowsprinting/mcp_extension_unity/   # Kotlin unit tests for each tool and utility
├── src/dotnet/
│   ├── McpExtensionUnity.sln
│   └── McpExtensionUnity/                             # C# backend: Rd handlers, model providers, and connection utilities
└── docs/plans/                                        # Implementation plan documents (written in Japanese)
```

## Build

> **First-time setup**: On a fresh clone, run `dotnet restore` once before building.
> The Gradle `restoreDotNet` task now handles this automatically, but if you encounter
> `MSB3644: .NETFramework,Version=v4.7.2 reference assemblies not found`, run manually:
> ```bash
> dotnet restore src/dotnet/McpExtensionUnity.sln
> ```

```bash
JAVA_HOME=~/Library/Java/JavaVirtualMachines/jbr-25.0.2/Contents/Home ./gradlew --no-configuration-cache buildPlugin
```

> **Note**: `--no-configuration-cache` is required due to incompatibilities with the `rdgen` and
> `generateDotNetSdkProperties` tasks under Gradle 9.3.1 configuration cache.

> **If `dotnet` is not found during `restoreDotNet`**: The Gradle daemon may have started without
> `/opt/homebrew/bin` in its PATH. Stop the daemon and re-run without a persistent daemon:
> ```bash
> JAVA_HOME=~/Library/Java/JavaVirtualMachines/jbr-25.0.2/Contents/Home ./gradlew --stop
> PATH="/opt/homebrew/bin:$PATH" JAVA_HOME=~/Library/Java/JavaVirtualMachines/jbr-25.0.2/Contents/Home ./gradlew --no-daemon --no-configuration-cache buildPlugin
> ```

Output ZIP is generated under `build/distributions/`.

**Install**: Rider → Settings → Plugins → Install Plugin from Disk → select ZIP → restart Rider.

## Unit Tests

```bash
JAVA_HOME=~/Library/Java/JavaVirtualMachines/jbr-25.0.2/Contents/Home ./gradlew --no-configuration-cache test
```

## MCP Extension Pattern

Use `McpToolset` + `@McpTool` + `@McpDescription` (confirmed working in Rider 2025.3.3):

```kotlin
@Suppress("RedundantSuspendModifier")
class MyToolset : McpToolset {
    @McpTool(name = "tool_name")
    @McpDescription(description = "What this tool does")
    suspend fun tool_name(
        @McpDescription(description = "Parameter description")
        param: String = "default"
    ): MyResult {
    }
}
```

Register in `plugin.xml`:

```xml
<extensions defaultExtensionNs="com.intellij.mcpServer">
    <mcpToolset implementation="com.nowsprinting.mcp_extension_unity.UnityEditorToolset"/>
</extensions>
```

> **Note**: The legacy `AbstractMcpTool<T>` pattern (used in the mcpExtensionPlugin demo) is **deprecated**
> in the Rider 2025.3.3 bundled MCP Server. Always use `McpToolset` + annotations.

## Important Constraints

1. **`kotlinx-serialization-json` must be `compileOnly`** — using `implementation` causes a class collision
   with the version bundled in the MCP Server plugin.

2. **Rider's "Unit testing configuration" is not XML-reproducible** — it is Rd-based and generated
   in-memory at runtime. There is no `create_run_configuration` MCP tool, and the configuration cannot
   be persisted as an XML file.

3. **Kotlin Frontend cannot directly access `BackendUnityModel`** — the two Rd connections
   (Frontend↔Backend and Backend↔Unity) are independent. A custom Rd model is required to bridge them.

4. **`@McpTool` annotation is required** — omitting it causes a runtime warning and the toolset is skipped:
   ```
   WARN - ReflectionToolsProvider - Cannot load tools for UnityEditorToolset
   java.lang.IllegalArgumentException: No tools found in class ...UnityEditorToolset
   ```

5. **All input validation is done on the Kotlin side (fail-fast)** — `assemblyNames` and `testMode`
   are validated in `RunUnityTestsTool.kt` before the Rd call is made. Invalid inputs return an
   immediate error without reaching the C# backend or Unity Editor.
   - `assemblyNames`: must contain at least one non-blank name (empty `TestFilter` disconnects Unity Editor)
   - `testMode`: must be one of `EditMode`, `edit`, `PlayMode`, `play` (case insensitive)
   - Find assembly names in `.asmdef` files or Rider's Unit Test Explorer.

6. **Initial connection wait** — All 4 MCP tools wait up to **30 seconds** for the Unity Editor to connect
   before failing with "not connected". This covers the domain-reload window after `.cs` file creation/modification.
   Implemented in `EditorConnectionUtils.kt` (Kotlin side) and `RdConnectionHelper.cs` (C# side).

7. **Cancellation, disconnection, and domain-reload handling** — `UnityTestMcpHandler.cs` monitors three failure paths:
   - `lt.OnTermination`: Rd lifetime ends (protocol disconnect, Kotlin coroutine cancel) → `TrySetCanceled()`
   - `BackendUnityModel.Advise(null)`: Unity Editor disconnects mid-run → waits up to `MCP_TOOL_TIMEOUT` milliseconds for reconnection (domain-reload tolerance). If reconnected, re-launches tests on the new model. If not, `TrySetException("did not reconnect within N seconds")`
   - Timeout timer: configurable via `MCP_TOOL_TIMEOUT` env var (milliseconds per Claude Code spec, default 100000000) → `TrySetException("timed out after N seconds")`
   - All failure paths call `TryAbortLaunch` (best-effort; aborts whatever launch is currently on the model).
   - **Known limitation**: Unity Test Runner manual Cancel may not fire `RunResult`, causing a wait until timeout.
     Set `MCP_TOOL_TIMEOUT` to a smaller value (in milliseconds) to reduce feedback delay in this case.

   `MCP_TOOL_TIMEOUT` applies to each waiting phase independently:
   - `run_unity_tests`: test execution wait + domain-reload reconnection wait (both = `MCP_TOOL_TIMEOUT`)
   - `get_unity_compilation_result`: Refresh wait + post-Refresh reconnection wait + compilation result wait (each = `MCP_TOOL_TIMEOUT`); shared helper `RdConnectionHelper.GetMcpToolTimeout()` reads the env var
   - `run_method_in_unity` / `unity_play_control`: **not governed by `MCP_TOOL_TIMEOUT`** — these use resharper-unity's existing Rd RPCs directly; a Kotlin-side timeout would orphan the in-flight RPC and risk double-invocation on retry

8. **`get_unity_compilation_result` domain-reload race condition** — `UnityCompilationMcpHandler.cs` handles two race conditions that occur when the tool is called while Unity is compiling:
   - **Stale model race**: After `Refresh.Start()` throws (domain reload detected), `BackendUnityModel` may still point to the pre-reload instance. `WaitForModelReconnect` (in `RdConnectionHelper.cs`) requires a *different* instance (`!ReferenceEquals`) to ensure the post-reload model is used.
   - **Transient model race**: During domain reload, the Rd client may briefly expose a new `BackendUnityModel` instance that is immediately rejected ("lifetime is already canceled"). A retry loop updates `previousModel` on each transient rejection and waits for the next candidate, eventually reaching the stable connection.
   - If `GetCompilationResult` is still cancelled after reconnection, the error message instructs the agent to wait and retry.

## Reference Documents

- `docs/plans/2026-02-22-poc-rider-mcp-unity-test.md` — Full PoC investigation: Rider architecture,
  Rd model details, MCP extension mechanism, encountered issues, and verification results.

## External References

- [resharper-unity](https://github.com/JetBrains/resharper-unity) — Rider Unity Support source
  - `BackendUnityModel.kt`, `FrontendBackendModel.kt`
  - `RunViaUnityEditorStrategy.cs`, `UnityNUnitServiceProvider.cs`
- [mcpExtensionPlugin Demo](https://github.com/MaXal/mcpExtensionPlugin) — MCP extension reference (old API pattern)
- [JetBrains MCP Server Plugin](https://github.com/JetBrains/mcp-server-plugin) — extension point spec
- [MCP Server | JetBrains Rider Documentation](https://www.jetbrains.com/help/rider/mcp-server.html)

## Language Guidelines

- All files, commit messages, GitHub Issues, and Pull Requests must be written in **English**.
- Exception: `docs/` — write in **Japanese**.

## Skill Guidelines

<important if="Feature implementation planning (writing or modifying a feature implementation plan in plan mode)">
- Read the `/implementation-planning-guide` skill to orchestrate the test-first planning workflow
- Add the following final step at the end of the `## Development Workflow` section in the plan file (adjust the step number to follow the last existing step):

  ```markdown
  ### Step N: Finalize

  1. Copy this plan file to `docs/plans/yyyy-MM-dd-<plan-name>.md`
  2. Append `## Implementation Notes` to the end of the copied plan file and write the following:
    - Design decisions: choices you made where the spec was ambiguous
    - Deviations: places where you intentionally departed from the spec, and why
    - Tradeoffs: alternatives you considered and why you picked what you did
    - Test changes: Test code modified during the implementation phase
    - Open questions: anything you'd want me to confirm or revise
  3. Append an entry to `CHANGELOG.md` under `## [Unreleased]`
    - Do not document changes whose paths start with `.claude/`, `.github/`, and `/src/test/` (test code changes are excluded from the changelog)
    - If the plan only modifies files under `.claude/`, `.github/`, and `/src/test/`, skip this step entirely
  ```
</important>
