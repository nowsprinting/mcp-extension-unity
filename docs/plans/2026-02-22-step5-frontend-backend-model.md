# Step 5: FrontendBackendModel連携 — Unity接続状態の取得

## Context

PoC（Step 1-4）が成功し、`run_unity_tests` MCPツールがClaude Codeから呼び出せることを確認済み。
現在のツールはエコーバック（引数をそのまま返す）のみで、実際のテスト実行ロジックは未実装。

Riderの内部テスト実行は以下のアーキテクチャで動作する:

```
Kotlin Frontend  ←Rd (FrontendBackendModel)→  C# Backend  ←Rd (BackendUnityModel)→  Unity Editor
```

テスト実行の最終目標（BackendUnityModel経由）に向けて、段階的に進める。
Step 5では、最初の一歩として**FrontendBackendModelへのアクセス**を実装し、Unity Editorの接続状態やテストランチャー設定を取得する。

## 実装内容

### 5-1. Unity Supportプラグインへの依存追加

`build.gradle.kts` に `com.intellij.resharper.unity` (Rider Unity Support) プラグイン依存を追加する。

```kotlin
dependencies {
    intellijPlatform {
        create("RD", "2025.3.3")
        testFramework(TestFrameworkType.Platform)
        bundledPlugin("com.intellij.mcpServer")
        bundledPlugin("com.intellij.resharper.unity")  // ← 追加
    }
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}
```

`plugin.xml` にも依存を追加:

```xml
<depends>com.intellij.resharper.unity</depends>
```

**要検証**: `com.intellij.resharper.unity` が正しいプラグインIDかどうか。Rider 2025.3.3 の bundledPlugin 一覧で確認する。

### 5-2. MCPツールでFrontendBackendModelにアクセス

`RunUnityTestsToolset.kt` を拡張し、FrontendBackendModelから以下の情報を取得:

- `unityEditorConnected`: Unity Editorが接続されているか
- `unitTestPreference`: 現在のテストランチャー設定（NUnit/EditMode/PlayMode/Both）

```kotlin
@McpTool(name = "run_unity_tests")
@McpDescription(description = "Run Unity tests through Rider's test infrastructure")
suspend fun run_unity_tests(
    @McpDescription(description = "Test mode: EditMode or PlayMode")
    testMode: String = "EditMode",
    // ... 他のパラメータ
): RunUnityTestsResult {
    val project = /* project取得 */
    val model = project.solution.frontendBackendModel

    // Unity Editor接続確認
    val isConnected = model.unityEditorConnected.valueOrDefault(false)
    if (!isConnected) {
        return RunUnityTestsResult(
            status = "error",
            message = "Unity Editor is not connected to Rider",
            // ...
        )
    }

    // 現在のテストランチャー設定を取得
    val currentPreference = model.unitTestPreference.value

    return RunUnityTestsResult(
        status = "connected",
        message = "Unity Editor connected. Launcher: $currentPreference",
        testMode = testMode,
        // ...
    )
}
```

**要調査**: `McpToolset`の`handle`メソッド（`suspend fun`）内で`Project`インスタンスをどう取得するか。
- Riderバンドル版の`ExecutionToolset`等を参照して、`Project`取得パターンを確認する
- `McpToolset`インターフェースが`Project`をどう提供するか

### 5-3. RunUnityTestsResultの拡張

結果データクラスにUnity接続情報を追加:

```kotlin
@Serializable
data class RunUnityTestsResult(
    val status: String,       // "connected", "error", "poc_echo", etc.
    val message: String? = null,
    val testMode: String? = null,
    val assemblyNames: List<String>? = null,
    val testNames: List<String>? = null,
    val groupNames: List<String>? = null,
    val testCategories: List<String>? = null,
    val unityEditorConnected: Boolean? = null,
    val currentLauncherPreference: String? = null
)
```

## テストケース

### 正常系
- Unity Editorが接続された状態で `run_unity_tests` を呼び出す → `status: "connected"`, `unityEditorConnected: true` が返る
- テストランチャー設定（EditMode/PlayMode/Both/NUnit）が正しく返される

### 異常系
- Unity Editorが接続されていない状態で呼び出す → `status: "error"`, `message: "Unity Editor is not connected to Rider"` が返る
- Riderプロジェクトが開かれていない場合 → 適切なエラーメッセージ

## 検証方法

1. `JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew buildPlugin`
2. Riderにプラグインを再インストール → 再起動
3. Unity Editorを起動し、Riderに接続
4. Claude Codeから `run_unity_tests` を呼び出し、Unity接続状態が返ることを確認
5. Unity Editorを終了した状態で呼び出し、エラーが返ることを確認

## 対象ファイル

- `build.gradle.kts` — Unity Supportプラグイン依存追加
- `src/main/resources/META-INF/plugin.xml` — depends追加
- `src/main/kotlin/com/github/rider/unity/mcp/RunUnityTestsToolset.kt` — FrontendBackendModelアクセス実装

## 参考コード（resharper-unity）

- [`UnitTestLauncherState.kt`](https://github.com/JetBrains/resharper-unity/blob/master/rider/src/main/kotlin/com/jetbrains/rider/plugins/unity/ui/unitTesting/UnitTestLauncherState.kt) — `SolutionExtListener<FrontendBackendModel>` によるRdモデルアクセスパターン
- [`FrontendBackendHost.kt`](https://github.com/JetBrains/resharper-unity/blob/master/rider/src/main/kotlin/com/jetbrains/rider/plugins/unity/FrontendBackendHost.kt) — `project.solution.frontendBackendModel` によるアクセス、`isConnectedToEditor()` パターン

## 実装結果（2026-02-22 検証済み）

### ビルド
`JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew buildPlugin` → `BUILD SUCCESSFUL`

### 動作確認

**Unity Editor未接続時:**
```json
{
    "status": "error",
    "message": "Unity Editor is not connected to Rider. Please open Unity Editor with the project.",
    "unityEditorConnected": false,
    "unityEditorState": "Disconnected",
    "unitTestPreference": "PlayMode",
    "testMode": "EditMode"
}
```

**Unity Editor接続時:**
```json
{
    "status": "connected",
    "message": "Unity Editor is connected. Test execution not yet implemented (Step 6+).",
    "unityEditorConnected": true,
    "unityEditorState": "Idle",
    "unitTestPreference": "PlayMode",
    "testMode": "EditMode"
}
```

### 確認できたこと
- `currentCoroutineContext().project` → プロジェクト取得OK
- `project.solution.frontendBackendModel` → Rdモデルアクセス確認
- `project.isConnectedToEditor()` → 接続状態の判定OK
- `model.unityEditorState.valueOrNull` → `"Disconnected"` / `"Idle"` が返ることを確認
- `model.unitTestPreference.value?.toString()` → `"PlayMode"` が返ることを確認

---

## 今後のステップ（Step 6以降の見通し）

Step 5でFrontendBackendModelへのアクセスが確認できたら、テスト実行の実装に進む。
テスト実行にはC# Backend側のコンポーネントが必要（BackendUnityModelはKotlinから直接アクセス不可）:

1. **Step 6**: カスタムRdプロトコルモデル定義（Frontend↔Backend間のテスト実行RPC）
2. **Step 7**: C# Backendハンドラ実装（Rdリクエスト受信 → BackendUnityModel経由でテスト実行）
3. **Step 8**: 結果集約＆MCP Responseとして返却
