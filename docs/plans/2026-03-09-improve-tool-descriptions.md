# Plan: Improve MCP tool descriptions for better Claude interpretation

## Context

Claude Code がこのプラグインのMCPツールを呼び出す際、description の情報不足や曖昧さにより意図通りに解釈・実行できないケースがある。ツール description、パラメータ description を改善し、Claudeが自律的に正しいワークフローで呼び出せるようにする。

## Changes

### 1. [高] `unity_play_control` description 拡充
**File**: `src/main/kotlin/com/nowsprinting/mcp_extension_unity/PlayControlToolset.kt` (L44-46)

Current:
```
Control Unity Editor's play mode.
```

Proposed (各アクションの説明、ワークフロー上の位置付けを追加):
```
Control Unity Editor's play mode.

Actions:
- `play`: Enter play mode.
- `stop`: Exit play mode. IMPORTANT: Must stop play mode before calling `get_unity_compilation_result`.
- `pause`: Pause at the current frame while in play mode.
- `resume`: Resume from paused state.
- `step`: Advance exactly one frame while paused.
- `status`: Read-only query. Returns current `isPlaying` and `isPaused` state without changing anything.
```

### 2. [高] `run_method_in_unity` アセンブリ名特定方法を追加
**File**: `src/main/kotlin/com/nowsprinting/mcp_extension_unity/RunMethodInUnityToolset.kt` (L43-49)

Current description にアセンブリ名の探し方がない。`run_unity_tests` と同様の .asmdef ガイダンスを追加:
```
Identify Assembly:
1. Find the assembly definition file (.asmdef) in the parent directory hierarchy of the target file.
2. The assembly name is the `name` property in the .asmdef file.
3. If no .asmdef exists in the hierarchy, check the directory path: if it contains a directory named `Editor`, use `Assembly-CSharp-Editor`; otherwise use `Assembly-CSharp`.
```

### 3. [中] `get_unity_compilation_result` に具体的な事前手順を明記
**File**: `src/main/kotlin/com/nowsprinting/mcp_extension_unity/CompilationResultToolset.kt` (L36-37)

Current:
```
IMPORTANT: Before calling this tool, use `unity_play_control` to check the Unity Editor state. If Unity Editor is in Play mode, stop it first, then call this tool.
```

Proposed (具体的なアクション名を明記):
```
IMPORTANT: Before calling this tool, call `unity_play_control` with `action='status'` to check the Unity Editor state. If `isPlaying` is true, call `unity_play_control` with `action='stop'` first, then call this tool.
```

### 4. [中] `groupNames` の説明を独立化
**File**: `src/main/kotlin/com/nowsprinting/mcp_extension_unity/RunUnityTestsToolset.kt` (L97)

Current:
```
Same as `testNames`, except that it allows for Regex. This is useful for running specific fixtures or namespaces. Generally, specify the test class that corresponds to the modified class (same namespace, class name with `Test` appended).
```

Proposed (testNamesへの依存を除去し、独立して理解できるように):
```
Regex patterns to filter tests by their full name. Matches against test fixtures, namespaces, or individual test names. Generally, specify the test class that corresponds to the modified class (same namespace, class name with `Test` appended).
```

## Files to modify
1. `src/main/kotlin/com/nowsprinting/mcp_extension_unity/PlayControlToolset.kt`
2. `src/main/kotlin/com/nowsprinting/mcp_extension_unity/RunMethodInUnityToolset.kt`
3. `src/main/kotlin/com/nowsprinting/mcp_extension_unity/CompilationResultToolset.kt`
4. `src/main/kotlin/com/nowsprinting/mcp_extension_unity/RunUnityTestsToolset.kt`

## Out of scope
- `testMode` の description とエラーメッセージの一貫性（低優先度）
- `logs` フィールドの事前説明（不要と判断）

## Verification
1. `JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew --no-configuration-cache test` — 既存テストがパスすること
2. `JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew --no-configuration-cache buildPlugin` — ビルド成功
3. description の変更のみなのでロジックへの影響なし
