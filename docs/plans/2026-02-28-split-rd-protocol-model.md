# Plan: Split Rd protocol model — separate compilation from test model

## Context

`UnityTestMcpModel.kt` currently defines both test-related types (`McpRunTestsRequest`, `McpRunTestsResponse`, etc.) and `McpCompilationResponse` + `getCompilationResult` call. Since the C# backend was already split into `UnityTestMcpHandler` (tests) and `UnityCompilationMcpHandler` (compilation), the Rd protocol model should also be split for consistency.

## Changes

### 1. New file: `protocol/src/main/kotlin/model/rider/UnityCompilationMcpModel.kt`

Create a new `Root()` model containing only the compilation-related type and call:

```kotlin
object UnityCompilationMcpModel : Root() {
    private val McpCompilationResponse = structdef {
        field("success", bool)
        field("errorMessage", string)
    }

    init {
        setting(Kotlin11Generator.Namespace, "com.nowsprinting.mcp_extension_unity.model")
        setting(CSharp50Generator.Namespace, "McpExtensionUnity.Model")
        call("getCompilationResult", void, McpCompilationResponse)
    }
}
```

### 2. Update: `protocol/src/main/kotlin/model/rider/UnityTestMcpModel.kt`

Remove `McpCompilationResponse` structdef (lines 52–55) and `call("getCompilationResult", ...)` (line 62). Also remove `import PredefinedType.void` if no longer needed.

### 3. Update: `protocol/build.gradle.kts`

Add 2 generator blocks for the new model (after the existing 2 blocks):

```kotlin
generator {
    language = "kotlin"
    transform = "asis"
    root = "model.rider.UnityCompilationMcpModel"
    directory = kotlinGeneratedDir.absolutePath
    generatedFileSuffix = ".Generated"
}
generator {
    language = "csharp"
    transform = "reversed"
    root = "model.rider.UnityCompilationMcpModel"
    namespace = "McpExtensionUnity.Model"
    directory = csharpGeneratedDir.absolutePath
    generatedFileSuffix = ".Generated"
}
```

### 4. New file: `src/main/kotlin/com/nowsprinting/mcp_extension_unity/UnityCompilationMcpModelProvider.kt`

Same reflection-based binding pattern as `UnityTestMcpModelProvider.kt`, targeting the new generated `UnityCompilationMcpModel` class with `mix("UnityCompilationMcpModel")`.

### 5. Update: `src/main/kotlin/com/nowsprinting/mcp_extension_unity/CompilationResultToolset.kt`

Change `UnityTestMcpModelProvider.getOrBindModel(protocol)` to `UnityCompilationMcpModelProvider.getOrBindModel(protocol)`.

### 6. New file: `src/dotnet/McpExtensionUnity/UnityCompilationMcpModelProvider.cs`

`[SolutionComponent]` that creates and exposes `UnityCompilationMcpModel`:

```csharp
[SolutionComponent(Instantiation.DemandAnyThreadSafe)]
public class UnityCompilationMcpModelProvider
{
    public readonly UnityCompilationMcpModel Model;
    public UnityCompilationMcpModelProvider(Lifetime lifetime, IProtocol protocol)
    {
        Model = new UnityCompilationMcpModel(lifetime, protocol);
    }
}
```

### 7. Update: `src/dotnet/McpExtensionUnity/UnityCompilationMcpHandler.cs`

Change constructor parameter from `UnityTestMcpModelProvider modelProvider` to `UnityCompilationMcpModelProvider modelProvider`.

### 8. Update: `src/dotnet/McpExtensionUnity/UnityTestMcpModelProvider.cs`

Remove the comment referencing `UnityCompilationMcpHandler` since it no longer uses this provider.

## No test code changes needed

This is a structural refactoring — no new logic is introduced. The existing Kotlin unit tests in `RunUnityTestsToolsetTest.kt` test input validation on the Kotlin side, which is unaffected. The Rd model split is transparent to the test infrastructure since each model binds independently.

### Manual Tests

| # | Item | Verification Method |
|---|------|---------------------|
| 1 | `run_unity_tests` still works end-to-end | Run tests from Claude Code — verify tests execute and results return |
| 2 | `get_unity_compilation_result` still works end-to-end | Call the tool directly — verify it triggers Refresh and returns compilation status |
| 3 | Both models bind independently | Check Rider logs for binding errors after plugin install |

## Development Workflow

### Step 1: Implementation

1. Create `UnityCompilationMcpModel.kt` (Rd DSL).
2. Remove compilation types from `UnityTestMcpModel.kt`.
3. Add generator blocks in `protocol/build.gradle.kts`.
4. Create `UnityCompilationMcpModelProvider.kt` (Kotlin).
5. Update `CompilationResultToolset.kt` to use the new provider.
6. Create `UnityCompilationMcpModelProvider.cs` (C#).
7. Update `UnityCompilationMcpHandler.cs` to inject the new provider.
8. Update comment in `UnityTestMcpModelProvider.cs`.
9. Resolve diagnostics using `mcp__jetbrains__get_file_problems`.
10. Build: `JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew --no-configuration-cache buildPlugin`

### Step 2: Refactoring

1. Reformat modified files using `mcp__jetbrains__reformat_file`.
2. Commit to git.
