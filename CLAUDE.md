# CLAUDE.md — rider-unity-test-mcp-plugin

## Core Principles

- **Do NOT maintain backward compatibility** unless explicitly requested. Break things boldly.

## Project Overview

A Rider IDE plugin (PoC stage) that extends the built-in JetBrains MCP Server with a custom MCP tool.
The goal is to allow Coding Agents (e.g., Claude Code) to run Unity tests through Rider's test infrastructure,
rather than invoking Unity directly.

**Current status**: Steps 1–5 complete. `FrontendBackendModel` access working; Unity Editor state and test preference retrievable. Real test execution (Step 6+) not yet implemented.

---

## Architecture

```
Coding Agent (Claude Code)
    ↓ MCP (HTTP/SSE)
JetBrains MCP Server (built into Rider 2025.3+)
    ↓ extension point (com.intellij.mcpServer)
[This Plugin — Kotlin Frontend]   ← current layer (PoC complete)
    ↓ custom Rd model (not yet implemented)
[Plugin Backend — C# / ReSharper] (not yet implemented)
    ↓ BackendUnityModel (existing Rd)
Unity Editor
    ↓ TestRunnerApi.Execute()
Test execution
```

Rider itself uses two separate Rd (Reactive Distributed) protocol connections:

- **Kotlin Frontend ↔ C# Backend**: `FrontendBackendModel`
- **C# Backend ↔ Unity Editor**: `BackendUnityModel`

The Kotlin Frontend **cannot** directly access `BackendUnityModel`; a custom Rd model bridging the two layers is needed for Step 6+.

---

## Tech Stack

| Item | Value |
|---|---|
| Language | Kotlin 2.3.0 |
| Serialization | kotlinx-serialization 1.6.3 (`compileOnly`) |
| Build plugin | IntelliJ Platform Gradle Plugin 2.11.0 |
| Target IDE | Rider 2025.3.3 (build `RD-253.31033.136`) |
| JDK | 21 |
| Gradle | 9.3.1 |

---

## Key Files

```
rider-unity-test-mcp-plugin/
├── CLAUDE.md                                          # this file
├── build.gradle.kts                                   # build configuration
├── gradle.properties
├── settings.gradle.kts
├── src/main/
│   ├── kotlin/com/github/rider/unity/mcp/
│   │   └── RunUnityTestsToolset.kt                   # MCP tool implementation
│   └── resources/META-INF/
│       └── plugin.xml                                 # plugin descriptor
└── docs/plans/
    ├── 2026-02-22-poc-rider-mcp-unity-test.md        # PoC investigation report
    └── 2026-02-22-step5-frontend-backend-model.md    # Step 5: FrontendBackendModel access
```

---

## Build

```bash
JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew buildPlugin
```

Output ZIP is generated under `build/distributions/`.

**Install**: Rider → Settings → Plugins → Install Plugin from Disk → select ZIP → restart Rider.

---

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
    <mcpToolset implementation="com.github.rider.unity.mcp.RunUnityTestsToolset"/>
</extensions>
```

> **Note**: The legacy `AbstractMcpTool<T>` pattern (used in the mcpExtensionPlugin demo) is **deprecated**
> in the Rider 2025.3.3 bundled MCP Server. Always use `McpToolset` + annotations.

---

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

---

## Development Roadmap

| Step | Description | Status |
|---|---|---|
| 1 | Set up Gradle project with IntelliJ Platform Plugin | Done |
| 2 | Register MCP extension point in plugin.xml | Done |
| 3 | Implement `RunUnityTestsToolset` with `@McpTool` | Done |
| 4 | Verify echo-back response from Claude Code | Done |
| 5 | Access `FrontendBackendModel` to get Unity Editor connection state | Done |
| 6 | Define custom Rd model (Kotlin ↔ C#) | Planned |
| 7 | Implement C# Backend handler, call `BackendUnityModel` | Planned |
| 8 | Stream test results back to the MCP caller | Planned |

---

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

---

## Language

- All files, commit messages, GitHub Issues, and Pull Requests must be written in **English**.
- Exception: `docs/` — write in **Japanese**.

## Implementation Plan

After a plan file is approved, copy it to `./docs/plans/` directory with the current datetime prefix in `yyyy-MM-dd` format. For example: `2026-01-18-plan-name.md`
