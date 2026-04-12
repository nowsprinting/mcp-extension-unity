# Introduce `UnityEditorToolset` as a Delegating Facade

## Context

JetBrains 2026.1 MCP Server の設定 UI（Settings → MCP Server → Tool Filtering）は、MCP ツールを
`McpToolset` クラス名ごとにカテゴリ分けして表示する。現状このプラグインは 4 つの `McpToolset`
（`CompilationResultToolset`, `RunUnityTestsToolset`, `RunMethodInUnityToolset`, `PlayControlToolset`）
を登録しており、Unity 関連ツールなのに 4 カテゴリにバラけて表示されてしまう。

本変更のゴールは、4 ツールを **単一カテゴリ `UnityEditorToolset`** に集約しつつ、既存の実装は
1 ツール 1 ファイルのまま可読性とテスト局所性を維持すること。

### Approach

既存の 4 ファイルを物理的にマージするのではなく、**デリゲートファサード** を導入する：

1. **既存 4 クラスをリネームし、`McpToolset` 継承を外す**：
   - `CompilationResultToolset` → `CompilationResultTool`
   - `RunUnityTestsToolset` → `RunUnityTestsTool`
   - `RunMethodInUnityToolset` → `RunMethodInUnityTool`
   - `PlayControlToolset` → `PlayControlTool`

   これらは単なる Kotlin クラスになり、実装ロジックを保持する。`@McpTool` / `@McpDescription`
   アノテーションは **削除** する（MCP サーバーからは参照されなくなるため）。

2. **新規クラス `UnityEditorToolset : McpToolset` を作成**。4 つの `*Tool` クラスをプライベート
   インスタンスとして保持し、`@McpTool` 注釈付きの `suspend fun` を 4 本定義してそれぞれ 1:1 で
   委譲する。

3. **`plugin.xml`**: 4 つの `<mcpToolset>` エントリを `UnityEditorToolset` を指す 1 エントリに置換。

リネーム後の `*Tool` クラスは、companion object（ユニットテストが呼び出しているヘルパー）や
トップレベルの結果型 sealed interface / data class / serializer を **すべてそのまま維持** する。
変更するのはクラス名・ファイル名・`McpToolset` 継承・`@McpTool` 系アノテーションの削除のみ。

## Files to Change

| File | Change |
|---|---|
| `src/main/kotlin/.../CompilationResultToolset.kt` | **Rename** → `CompilationResultTool.kt`; drop supertype + annotations |
| `src/main/kotlin/.../RunUnityTestsToolset.kt` | **Rename** → `RunUnityTestsTool.kt`; drop supertype + annotations |
| `src/main/kotlin/.../RunMethodInUnityToolset.kt` | **Rename** → `RunMethodInUnityTool.kt`; drop supertype + annotations |
| `src/main/kotlin/.../PlayControlToolset.kt` | **Rename** → `PlayControlTool.kt`; drop supertype + annotations |
| `src/test/kotlin/.../CompilationResultToolsetTest.kt` | **Rename** → `CompilationResultToolTest.kt`; update class refs |
| `src/test/kotlin/.../RunUnityTestsToolsetTest.kt` | **Rename** → `RunUnityTestsToolTest.kt`; update class refs |
| `src/test/kotlin/.../RunMethodInUnityToolsetTest.kt` | **Rename** → `RunMethodInUnityToolTest.kt`; update class refs |
| `src/test/kotlin/.../PlayControlToolsetTest.kt` | **Rename** → `PlayControlToolTest.kt`; update class refs |
| `src/main/kotlin/.../UnityEditorToolset.kt` | **Create** — delegating facade, the only `McpToolset` |
| `src/main/resources/META-INF/plugin.xml` | Replace 4 `<mcpToolset>` entries with 1 |
| `CLAUDE.md` | **Update** — Architecture図、Key Files、plugin.xml例、Constraints の参照を更新 |

## Design

### `*Tool` classes (after rename)

リネーム後の各クラスは **単なるクラス**（supertype なし、`@McpTool` 系アノテーションなし）で、
既存実装をそのまま保持する：

```kotlin
// CompilationResultTool.kt (was CompilationResultToolset.kt)
class CompilationResultTool {
    private val LOG = Logger.getInstance(CompilationResultTool::class.java)

    companion object {
        internal const val LOG_FLUSH_DELAY_MS = 500L
    }

    // アノテーションは削除。シグネチャ・本体は変更なし。
    suspend fun get_unity_compilation_result(): CompilationResult { /* verbatim */ }
}

// トップレベルの結果型はこのファイル内にそのまま残す。
```

他 3 クラスも同じ形：

- `RunUnityTestsTool` — companion の `sanitizeAssemblyNames` / `parseTestMode` / `filterLeafResults`、
  およびトップレベルの `TestDetail`, `RunUnityTestsResult`, `TestErrorResult`, `TestRunResult`,
  `RunUnityTestsResultSerializer` をそのまま維持。
- `RunMethodInUnityTool` — companion の `LOG_FLUSH_DELAY_MS` / `validateParam` / `formatErrorMessage`、
  およびトップレベルの `RunMethodInUnityResult`, `RunMethodInUnityErrorResult`,
  `RunMethodInUnitySuccessResult`, `RunMethodInUnityResultSerializer` をそのまま維持。
- `PlayControlTool` — companion の `PlayAction` enum + `parseAction`、およびトップレベルの
  `PlayControlResult`, `PlayControlErrorResult`, `PlayControlSuccessResult`,
  `PlayControlResultSerializer` をそのまま維持。

### `UnityEditorToolset` (new)

```kotlin
package com.nowsprinting.mcp_extension_unity

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool

class UnityEditorToolset : McpToolset {

    private val compilationResultTool = CompilationResultTool()
    private val runUnityTestsTool = RunUnityTestsTool()
    private val runMethodInUnityTool = RunMethodInUnityTool()
    private val playControlTool = PlayControlTool()

    @McpTool(name = "get_unity_compilation_result")
    @McpDescription(description = """<same text as current CompilationResultToolset>""")
    suspend fun get_unity_compilation_result(): CompilationResult =
        compilationResultTool.get_unity_compilation_result()

    @McpTool(name = "run_unity_tests")
    @McpDescription(description = """<same text as current RunUnityTestsToolset>""")
    suspend fun run_unity_tests(
        @McpDescription(description = "REQUIRED. `EditMode` or `PlayMode` (case insensitive).")
        testMode: String? = null,
        @McpDescription(description = "REQUIRED. Names of test assemblies to run (without .dll extension, e.g., 'MyFeature.Tests').")
        assemblyNames: List<String>? = null,
        @McpDescription(description = "Names of a category to include in the run. ...")
        categoryNames: List<String>? = null,
        @McpDescription(description = "Regex patterns to filter tests by their full name. ...")
        groupNames: List<String>? = null,
        @McpDescription(description = "The full name of the tests to match the filter. ...")
        testNames: List<String>? = null
    ): RunUnityTestsResult =
        runUnityTestsTool.run_unity_tests(testMode, assemblyNames, categoryNames, groupNames, testNames)

    @McpTool(name = "run_method_in_unity")
    @McpDescription(description = """<same text as current RunMethodInUnityToolset>""")
    suspend fun run_method_in_unity(
        @McpDescription(description = "Assembly name containing the type (e.g., 'Assembly-CSharp-Editor')")
        assemblyName: String? = null,
        @McpDescription(description = "Fully qualified type name (e.g., 'MyNamespace.MyEditorTool')")
        typeName: String? = null,
        @McpDescription(description = "Static method name to invoke (e.g., 'DoSomething')")
        methodName: String? = null
    ): RunMethodInUnityResult =
        runMethodInUnityTool.run_method_in_unity(assemblyName, typeName, methodName)

    @McpTool(name = "unity_play_control")
    @McpDescription(description = """<same text as current PlayControlToolset>""")
    suspend fun unity_play_control(
        @McpDescription(description = "Action to perform: `play`, `stop`, `pause`, `resume`, `step`, or `status` (case insensitive)")
        action: String? = null
    ): PlayControlResult =
        playControlTool.unity_play_control(action)
}
```

**ファサードへのアノテーション転記が必要。** JetBrains MCP は登録された `McpToolset` クラスから
`@McpTool` / `@McpDescription` をリフレクションで読むため、ツール説明とパラメータ注釈は
`UnityEditorToolset` 側に置く必要がある。説明文は既存クラスから **逐語転記** する（`*Tool` 側からは
削除するため重複ではない）。ただしメソッドシグネチャ（引数名・型・戻り値型）は `UnityEditorToolset`
と `*Tool` の両方に存在するため、将来シグネチャを変更する際は2箇所の修正が必要になる
（委譲パターン固有の同期コスト）。

### `plugin.xml`

```xml
<extensions defaultExtensionNs="com.intellij.mcpServer">
    <mcpToolset implementation="com.nowsprinting.mcp_extension_unity.UnityEditorToolset"/>
</extensions>
```

## Test Design

リファクタリングのみで振る舞いは無変更。既存テストはそのまま残し、クラス/ファイル名の更新だけ行う。

### Unit tests

| Test file | Change |
|---|---|
| `CompilationResultToolsetTest.kt` | Rename → `CompilationResultToolTest.kt`. クラス参照: `CompilationResultToolset` → `CompilationResultTool`. |
| `RunUnityTestsToolsetTest.kt` | Rename → `RunUnityTestsToolTest.kt`. 全 24 テストの `RunUnityTestsToolset.` companion 呼び出しを `RunUnityTestsTool.` に置換。 |
| `RunMethodInUnityToolsetTest.kt` | Rename → `RunMethodInUnityToolTest.kt`. クラス参照を置換。 |
| `PlayControlToolsetTest.kt` | Rename → `PlayControlToolTest.kt`. クラス参照を置換。 |

`UnityEditorToolset` 自体の単体テストは追加しない。委譲のみのファサードであり、
`*Tool` 側のテストが振る舞いを完全にカバーするため、Kotlin の `=` 委譲構文のみを検証する
テストになってしまい意味が薄い。

### E2E Tests

本プラン内に直接記載する（振る舞いが無変更なため別ドキュメント化は不要）：

| # | Item | Verification Method |
|---|---|---|
| 1 | 4 ツールがすべて単一の `UnityEditorToolset` カテゴリ下に表示される | Rider → Settings → MCP Server → Tool Filtering で目視確認 |
| 2 | `get_unity_compilation_result` が E2E で動作する | Claude Code から呼び出し、Unity リフレッシュと結果返却を確認 |
| 3 | `run_unity_tests` が E2E で動作する | Claude Code から呼び出し、テスト実行と結果返却を確認 |
| 4 | `run_method_in_unity` が E2E で動作する | Claude Code から呼び出し、メソッド実行とログ返却を確認 |
| 5 | `unity_play_control` が E2E で動作する | Claude Code から呼び出し、Play 状態変化を確認 |

## Implementation Steps

### Step 1: Rename production classes + test classes

本番コードとテストコードのリネームを同一ステップで行い、テスト green を確認してからコミットする。

1. `CompilationResultToolset.kt` → `CompilationResultTool.kt` にリネームし、ファイル内で：
   - `class CompilationResultToolset : McpToolset` → `class CompilationResultTool`
   - `import com.intellij.mcpserver.McpToolset` を削除
   - ツールメソッド上の `@McpTool(...)` とトップレベル `@McpDescription(...)` を削除
   - パラメータ上の `@McpDescription(...)` を削除
   - 未使用になる `import com.intellij.mcpserver.annotations.McpDescription` / `McpTool` を削除
   - `LOG = Logger.getInstance(CompilationResultToolset::class.java)` → `CompilationResultTool::class.java`
2. 同じパターンで `RunUnityTestsToolset` / `RunMethodInUnityToolset` / `PlayControlToolset` を
   リネーム。
3. 4 つのテストファイルをリネーム：
   `CompilationResultToolsetTest.kt` → `CompilationResultToolTest.kt`,
   `RunUnityTestsToolsetTest.kt` → `RunUnityTestsToolTest.kt`,
   `RunMethodInUnityToolsetTest.kt` → `RunMethodInUnityToolTest.kt`,
   `PlayControlToolsetTest.kt` → `PlayControlToolTest.kt`。
4. 各ファイル内でテストクラス名（`XxxToolsetTest` → `XxxToolTest`）とプロダクションコード参照
   （`XxxToolset.` → `XxxTool.`、companion メソッド呼び出し
   `RunUnityTestsToolset.parseTestMode` → `RunUnityTestsTool.parseTestMode` を含む）を置換。
5. コンパイル確認:
   `JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew --no-configuration-cache compileKotlin compileTestKotlin`
6. ユニットテスト実行 — 全件緑、件数変更なし（`RunUnityTestsToolTest` は 24 件）:
   `JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew --no-configuration-cache test`
7. コミット。

> Note: この時点で `plugin.xml` はまだ古いクラス名を参照しているためプラグインはロードできないが、
> コンパイルとテストは通る。Step 2 へ即座に進む。

### Step 2: Create `UnityEditorToolset` facade

1. `src/main/kotlin/.../UnityEditorToolset.kt` を Design セクションの通りに新規作成。
2. `@McpTool` / `@McpDescription` のテキストは git の Step 1 コミット前差分を参照し **逐語転記**
   （パラメータアノテーションも含む）。記述の言い換えは行わない — バイト一致コピーで
   正しさを担保する。
   - `CompilationResultToolset.kt:32-37` → `get_unity_compilation_result` の `@McpDescription`
   - `RunUnityTestsToolset.kt:78-100` → `run_unity_tests` の `@McpDescription` + パラメータアノテーション
   - `RunMethodInUnityToolset.kt:42-61` → `run_method_in_unity` の `@McpDescription` + パラメータアノテーション
   - `PlayControlToolset.kt:43-56` → `unity_play_control` の `@McpDescription` + パラメータアノテーション
3. `plugin.xml` の 4 `<mcpToolset>` エントリを `UnityEditorToolset` を指す 1 エントリに置換。
4. コンパイル確認:
   `JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew --no-configuration-cache compileKotlin`
5. ユニットテスト実行 — 全件緑（リグレッションなし確認）:
   `JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew --no-configuration-cache test`
6. コミット。

### Step 3: Update CLAUDE.md

1. Architecture 図: `← RunUnityTestsToolset.kt` → `← UnityEditorToolset.kt`
2. Key Files セクション: `RunUnityTestsToolset.kt` → `UnityEditorToolset.kt` + 4 つの `*Tool.kt` を記載
3. Key Files セクション: `RunUnityTestsToolsetTest.kt` → `RunUnityTestsToolTest.kt` 等テストファイル名更新
4. MCP Extension Pattern セクション: plugin.xml 例の `RunUnityTestsToolset` → `UnityEditorToolset`
5. Important Constraints #4: ログ例のクラス名を更新
6. コミット。

### Step 4: Polish

1. `mcp__jetbrains__get_file_problems` で新規/リネームファイルを検査し、`error` / `suggestion`
   レベルの診断を解消。
2. `mcp__jetbrains__reformat_file` で `UnityEditorToolset.kt` および 4 つの `*Tool.kt` を整形。
3. 再度ユニットテスト実行 — 緑確認。
4. コミット。

### Step 5: E2E smoke test（手動・人間が実施）

以下の手順で E2E テストを実施してください：

1. ビルド: `JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew --no-configuration-cache buildPlugin`
2. `build/distributions/` の ZIP を Rider にインストールし、再起動。
3. Unity Project を開いた Rider で以下を確認：
   - [ ] Settings → MCP Server → Tool Filtering で、4 ツールが「UnityEditorToolset」カテゴリ下に表示
   - [ ] `get_unity_compilation_result` が E2E で動作する（Unity リフレッシュと結果返却）
   - [ ] `run_unity_tests` が E2E で動作する（テスト実行と結果返却）
   - [ ] `run_method_in_unity` が E2E で動作する（メソッド実行とログ返却）
   - [ ] `unity_play_control` が E2E で動作する（Play 状態変化）
4. 失敗があれば報告 → 修正サイクルへ。

## Critical Files to Reference

- `src/main/kotlin/.../CompilationResultToolset.kt:23-135` — `CompilationResultTool` 本体と結果型のソース
- `src/main/kotlin/.../RunUnityTestsToolset.kt:78-101` — ファサードに転記する `run_unity_tests` アノテーション
- `src/main/kotlin/.../RunMethodInUnityToolset.kt:42-62` — ファサードに転記する `run_method_in_unity` アノテーション
- `src/main/kotlin/.../PlayControlToolset.kt:43-102` — ファサードに転記する `unity_play_control` アノテーション
- `src/main/resources/META-INF/plugin.xml:10-15` — 1 エントリにまとめる `<mcpToolset>` 行
- `src/test/kotlin/.../RunUnityTestsToolsetTest.kt` — 24 テスト、companion 呼び出しが多くリネーム対象が多い

## Notes / Caveats

- **名称変更のみのリファクタリング。** ロジック移動・振る舞い変更・公開 API の追加は一切なし。
  レビュー時に意味変更が見つかったら差し戻し。
- **ファサードへのアノテーション転記が必要。** 説明文は元ファイルからバイト単位でコピーし、言い換えない。
  `*Tool` 側からはアノテーションを削除するため重複ではなく転記。ただしメソッドシグネチャ（引数名・型・戻り値型）は
  `UnityEditorToolset` と `*Tool` の両方に存在するため、将来シグネチャを変更する際は2箇所の修正が必要になる
  （委譲パターン固有の同期コスト）。これが IDE 上での単一カテゴリ表示の対価となる。
- **`UnityEditorToolset` 単体のテストは意図的に省略。** ロジックが無く、Kotlin の委譲構文を
  検証するだけになるため。
- **`plugin.xml` の変更は `<mcpToolset>` 集約のみ。** version / idea-version / dependencies は
  変更しない。
