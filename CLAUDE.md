# CLAUDE.md — mcp-extension-unity

## Project Overview

A Rider IDE plugin (PoC stage) that extends the built-in JetBrains MCP Server with a custom MCP tool.
The goal is to allow Coding Agents (e.g., Claude Code) to run Unity tests through Rider's test infrastructure,
rather than invoking Unity directly.

**Current status**: Steps 1–10 complete. Domain-reload reconnection handling added in Step 10.

## Architecture

```
Coding Agent (Claude Code)
    ↓ MCP (HTTP/SSE)
JetBrains MCP Server (built into Rider 2025.3+)
    ↓ extension point (com.intellij.mcpServer)
[This Plugin — Kotlin Frontend]   ← RunUnityTestsToolset.kt
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

| Item          | Value                                       |
|---------------|---------------------------------------------|
| Language      | Kotlin 2.3.0                                |
| Serialization | kotlinx-serialization 1.6.3 (`compileOnly`) |
| Build plugin  | IntelliJ Platform Gradle Plugin 2.11.0      |
| Target IDE    | Rider 2025.3.3 (build `RD-253.31033.136`)   |
| JDK           | 21                                          |
| Gradle        | 9.3.1                                       |

## Key Files

```
mcp-extension-unity/
├── CLAUDE.md                                          # this file
├── build.gradle.kts                                   # build configuration (incl. compileDotNet, prepareSandbox)
├── gradle.properties
├── settings.gradle.kts
├── protocol/                                          # Rd model definition (rdgen)
│   └── src/main/kotlin/model/rider/
│       └── UnityTestMcpModel.kt                       # Rd DSL: McpRunTestsRequest/Response
├── src/main/
│   ├── kotlin/com/nowsprinting/mcp_extension_unity/
│   │   ├── RunUnityTestsToolset.kt                   # MCP tool → Rd call (assemblyNames validation)
│   │   └── UnityConsoleLogCollector.kt               # Console log collector (start/stop lifecycle)
│   ├── generated/                                     # auto-generated Kotlin model (gitignored)
│   └── resources/META-INF/
│       └── plugin.xml                                 # plugin descriptor
├── src/test/
│   └── kotlin/com/nowsprinting/mcp_extension_unity/
│       └── RunUnityTestsToolsetTest.kt                # Kotlin unit tests (20 cases)
├── src/dotnet/
│   ├── McpExtensionUnity.sln
│   ├── McpExtensionUnity/
│   │   ├── McpExtensionUnity.csproj
│   │   ├── UnityTestMcpHandler.cs                     # C# handler → BackendUnityModel
│   │   ├── ZoneMarker.cs
│   │   └── Model/                                     # auto-generated C# model (gitignored)
└── docs/plans/
    ├── 2026-02-22-poc-rider-mcp-unity-test.md        # PoC investigation report
    ├── 2026-02-22-step5-frontend-backend-model.md    # Step 5: FrontendBackendModel access
    └── 2026-02-23-step7-end-to-end-verification.md   # Step 7: E2E verification checklist (9 test cases)
```

## Build

> **First-time setup**: On a fresh clone, run `dotnet restore` once before building.
> The Gradle `restoreDotNet` task now handles this automatically, but if you encounter
> `MSB3644: .NETFramework,Version=v4.7.2 reference assemblies not found`, run manually:
> ```bash
> dotnet restore src/dotnet/McpExtensionUnity.sln
> ```

```bash
JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew --no-configuration-cache buildPlugin
```

> **Note**: `--no-configuration-cache` is required due to incompatibilities with the `rdgen` and
> `generateDotNetSdkProperties` tasks under Gradle 9.3.1 configuration cache.

Output ZIP is generated under `build/distributions/`.

**Install**: Rider → Settings → Plugins → Install Plugin from Disk → select ZIP → restart Rider.

## Unit Tests

```bash
JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew --no-configuration-cache test
```

## MCP Extension Pattern

Use `McpToolset` + `@McpTool` + `@McpDescription` (confirmed working in Rider 2025.3.3):

```kotlin
class MyToolset : McpToolset {
    @McpTool(name = "tool_name")
    @McpDescription(description = "What this tool does")
    suspend fun tool_name(
        @McpDescription(description = "Parameter description")
        param: String = "default"
    ): MyResult { ... }
}
```

Register in `plugin.xml`:

```xml
<extensions defaultExtensionNs="com.intellij.mcpServer">
    <mcpToolset implementation="com.nowsprinting.mcp_extension_unity.RunUnityTestsToolset"/>
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
   WARN - ReflectionToolsProvider - Cannot load tools for RunUnityTestsToolset
   java.lang.IllegalArgumentException: No tools found in class ...RunUnityTestsToolset
   ```

5. **All input validation is done on the Kotlin side (fail-fast)** — `assemblyNames` and `testMode`
   are validated in `RunUnityTestsToolset.kt` before the Rd call is made. Invalid inputs return an
   immediate error without reaching the C# backend or Unity Editor.
   - `assemblyNames`: must contain at least one non-blank name (empty `TestFilter` disconnects Unity Editor)
   - `testMode`: must be one of `EditMode`, `edit`, `PlayMode`, `play` (case insensitive)
   - Find assembly names in `.asmdef` files or Rider's Unit Test Explorer.

6. **Cancellation, disconnection, and domain-reload handling** — `UnityTestMcpHandler.cs` monitors three failure paths:
   - `lt.OnTermination`: Rd lifetime ends (protocol disconnect, Kotlin coroutine cancel) → `TrySetCanceled()`
   - `BackendUnityModel.Advise(null)`: Unity Editor disconnects mid-run → waits up to **2 minutes** for reconnection (domain-reload tolerance). If reconnected, re-launches tests on the new model. If not, `TrySetException("did not reconnect within 2 minutes")`
   - Timeout timer: configurable via `MCP_TOOL_TIMEOUT` env var (seconds, default 300) → `TrySetException("timed out after N seconds")`
   - All failure paths call `TryAbortLaunch` (best-effort; aborts whatever launch is currently on the model).
   - **Known limitation**: Unity Test Runner manual Cancel may not fire `RunResult`, causing a wait until timeout.
     Set `MCP_TOOL_TIMEOUT` to a smaller value to reduce feedback delay in this case.

## Development Roadmap

| Step | Description                                                               | Status |
|------|---------------------------------------------------------------------------|--------|
| 1    | Set up Gradle project with IntelliJ Platform Plugin                       | Done   |
| 2    | Register MCP extension point in plugin.xml                                | Done   |
| 3    | Implement `RunUnityTestsToolset` with `@McpTool`                          | Done   |
| 4    | Verify echo-back response from Claude Code                                | Done   |
| 5    | Access `FrontendBackendModel` to get Unity Editor connection state        | Done   |
| 6    | Define custom Rd model + implement C# handler calling `BackendUnityModel` | Done   |
| 7    | Verify end-to-end test execution with a real Unity project                | Done   |
| 8    | Add cancellation/disconnection handling and `MCP_TOOL_TIMEOUT` env var    | Done   |
| 9    | Add console log collection to `run_method_in_unity`                       | Done   |
| 10   | Add domain-reload reconnection handling to `UnityTestMcpHandler`          | Done   |

## Reference Documents

- `docs/plans/2026-02-22-poc-rider-mcp-unity-test.md` — Full PoC investigation: Rider architecture,
  Rd model details, MCP extension mechanism, encountered issues, and verification results.
- `docs/plans/2026-02-23-step7-end-to-end-verification.md` — Step 7: End-to-end verification checklist
  with phases for build, install, Unity project setup, MCP configuration, and test case execution.

## External References

- [resharper-unity](https://github.com/JetBrains/resharper-unity) — Rider Unity Support source
  - `BackendUnityModel.kt`, `FrontendBackendModel.kt`
  - `RunViaUnityEditorStrategy.cs`, `UnityNUnitServiceProvider.cs`
- [mcpExtensionPlugin Demo](https://github.com/MaXal/mcpExtensionPlugin) — MCP extension reference (old API pattern)
- [JetBrains MCP Server Plugin](https://github.com/JetBrains/mcp-server-plugin) — extension point spec
- [MCP Server | JetBrains Rider Documentation](https://www.jetbrains.com/help/rider/mcp-server.html)

## Guidelines

- **Implementation planning** (writing or modifying code planning in plan mode):
  - Read the `/implementation-planning-guide` skill to include test design and development workflow in your planning
  - After a plan is approved, copy the plan file to `./docs/plans/` with a `yyyy-MM-dd` date prefix. For example: `2026-01-18-plan-name.md`
- **Writing or modifying code**:
  - Read the `/code-writing-guide` skill

## Language

- All files, commit messages, GitHub Issues, and Pull Requests must be written in **English**.
- Exception: `docs/` — write in **Japanese**.
