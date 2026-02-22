# Step 6: カスタムRdモデルによるUnityテスト実行の実装

## Context

Steps 1-5完了により、MCPツール `run_unity_tests` からFrontendBackendModelへのアクセスが可能になった。
しかしKotlin FrontendからBackendUnityModel（C# Backend ↔ Unity Editor間のRdプロトコル）に直接アクセスできないため、
カスタムRdモデルを定義してKotlin Frontend → C# Backend → BackendUnityModel → Unity Editorの経路でテストを実行する必要がある。

Step 6では、Rdモデル定義・C# Backendハンドラ・Kotlin Frontendの更新をすべて実装し、エンドツーエンドのテスト実行を実現する。

---

## アーキテクチャ

```
Coding Agent (Claude Code)
    ↓ MCP (HTTP/SSE)
JetBrains MCP Server (Rider内蔵)
    ↓ extension point
[RunUnityTestsToolset — Kotlin Frontend]  ← 既存（改修）
    ↓ UnityTestMcpModel (NEW: カスタムRdモデル)
[UnityTestMcpHandler — C# Backend]        ← 新規
    ↓ BackendUnityModel (既存Rd)
Unity Editor
    ↓ TestRunnerApi.Execute()
テスト実行
```

---

## MCPツール仕様

### Description

```
Run Unity tests through Rider's test infrastructure.
Recommend filtering by assemblyNames, groupNames, or testNames to narrow down the tests to be executed to the scope of changes.
```

### 入力パラメータ

| パラメータ | 型 | 説明 |
|---|---|---|
| `testMode` | String (default: "EditMode") | `EditMode` or `PlayMode` (case insensitive) |
| `assemblyNames` | List\<String\>? | The names of assemblies included in the run. That is the assembly name, without the .dll file extension. e.g., MyTestAssembly |
| `categoryNames` | List\<String\>? | The name of a Category to include in the run. Any test or fixture runs that have a Category matching the string. |
| `groupNames` | List\<String\>? | The same as testNames, except that it allows for Regex. This is useful for running specific fixtures or namespaces. e.g., "^MyNamespace\\." Runs any tests where the top namespace is MyNamespace. |
| `testNames` | List\<String\>? | The full name of the tests to match the filter. This is usually in the format FixtureName.TestName. If the test has test arguments, then include them in parentheses. e.g., MyTestClass2.MyTestWithMultipleValues(1). |

**注**: 既存の `testCategories` パラメータは `categoryNames` にリネーム。

### レスポンス (JSON)

成功時:
```json
{
  "failCount": 0,
  "passCount": 10,
  "skipCount": 2,
  "inconclusiveCount": 0,
  "failedTests": [],
  "inconclusiveTests": []
}
```

失敗/Inconclusiveテストの詳細:
```json
{
  "failCount": 1,
  "passCount": 9,
  "skipCount": 0,
  "inconclusiveCount": 1,
  "failedTests": [
    {"testId": "MyNamespace.MyTestClass.FailingTest", "output": "Expected: 1\n  But was: 2", "duration": 123}
  ],
  "inconclusiveTests": [
    {"testId": "MyNamespace.MyTestClass.InconclusiveTest", "output": "...", "duration": 456}
  ]
}
```

エラー時:
```json
{
  "error": "Unity Editor is not connected to Rider. Please open Unity Editor with the project."
}
```

---

## Step 6完了後のプロジェクト構成

```
rider-unity-test-mcp-plugin/
├── build.gradle.kts                                   # 改修: riderModel, compileDotNet, PrepareSandbox
├── gradle.properties                                  # 改修: rdGenVersion, dotNetPluginId追加
├── settings.gradle.kts                                # 改修: :protocol include, pluginManagement
├── .gitignore                                         # 改修: generated/dotnetパターン追加
│
├── protocol/                                          # 新規: Rdモデル定義
│   ├── build.gradle.kts                               #   rd-gen設定
│   └── src/main/kotlin/model/rider/
│       └── UnityTestMcpModel.kt                       #   Rdモデル定義 (DSL)
│
├── src/main/
│   ├── kotlin/com/github/rider/unity/mcp/
│   │   └── RunUnityTestsToolset.kt                    # 改修: Rd callを使用、レスポンス形式変更
│   ├── generated/                                     # 自動生成 (gitignore)
│   │   └── com/github/rider/unity/mcp/model/
│   │       └── UnityTestMcpModel.Generated.kt
│   └── resources/META-INF/
│       └── plugin.xml                                 # 変更なし
│
├── src/dotnet/                                        # 新規: C# Backend
│   ├── Directory.Build.props                          #   共通MSBuild設定
│   ├── RiderUnityTestMcp.sln                          #   .NETソリューション
│   └── RiderUnityTestMcp/
│       ├── RiderUnityTestMcp.csproj                   #   C#プロジェクト
│       ├── ZoneMarker.cs                              #   ReSharperゾーンマーカー
│       ├── UnityTestMcpHandler.cs                     #   Rdハンドラ（テスト実行ロジック）
│       └── Model/                                     #   自動生成 (gitignore)
│           └── UnityTestMcpModel.Generated.cs
```

---

## Rdモデル定義

**ファイル**: `protocol/src/main/kotlin/model/rider/UnityTestMcpModel.kt`

```kotlin
object UnityTestMcpModel : Ext(SolutionModel.Solution) {
    private val McpTestMode = enum { +"EditMode"; +"PlayMode" }

    private val McpTestFilter = structdef {
        field("assemblyNames", immutableList(string))
        field("testNames", immutableList(string))
        field("groupNames", immutableList(string))
        field("categoryNames", immutableList(string))
    }

    private val McpRunTestsRequest = structdef {
        field("testMode", McpTestMode)
        field("filter", McpTestFilter)
    }

    private val McpTestDetail = structdef {
        field("testId", string)
        field("output", string)
        field("duration", int)    // milliseconds
    }

    private val McpRunTestsResponse = structdef {
        field("success", bool)
        field("errorMessage", string)  // empty on success
        field("passCount", int)
        field("failCount", int)
        field("skipCount", int)
        field("inconclusiveCount", int)
        field("failedTests", immutableList(McpTestDetail))
        field("inconclusiveTests", immutableList(McpTestDetail))
    }

    init {
        setting(Kotlin11Generator.Namespace, "com.github.rider.unity.mcp.model")
        setting(CSharp50Generator.Namespace, "RiderUnityTestMcp.Model")
        call("runTests", McpRunTestsRequest, McpRunTestsResponse)
    }
}
```

**設計判断**:
- **`call` (request-response)**: MCPプロトコルは1リクエスト1レスポンス。C# Backendで結果を集約してから返すため、ストリーミング不要。
- **`errorMessage` は `string` (nullable でない)**: Rd structdefのフィールドはプリミティブ型のnullableを避けるのが慣例。成功時は空文字列。
- **`immutableList(string)` (nullable でない)**: 空リストを「フィルタなし」として使用。nullable よりシンプル。

---

## C# Backend設計

### BackendUnityModelへのアクセス経路

1. `plugin.xml` に `<depends>com.intellij.resharper.unity</depends>` を宣言済み（Step 5）
2. resharper-unity の `BackendUnityHost` は public な `SolutionComponent` → コンストラクタインジェクションで取得可能
3. `BackendUnityHost.BackendUnityModel.Value` でUnity Editor接続時のモデルインスタンスを取得

### UnityTestMcpHandler.cs のロジック

1. `McpRunTestsRequest` を受信
2. `BackendUnityHost.BackendUnityModel.Value` の null チェック（Unity未接続ならエラー返却）
3. `McpTestFilter` → `TestFilter[]` に変換（assemblyNames ごとに1つの TestFilter を生成）
4. `testMode` → `TestMode.Edit` / `TestMode.Play` にマッピング
5. `UnitTestLaunch` を生成し `backendUnityModel.UnitTestLaunch.SetValue(launch)` で設定
6. `launch.TestResult` シンクと `launch.RunResult` シンクを購読（結果収集用）
7. `backendUnityModel.RunUnitTestLaunch.Start()` でテスト実行トリガー
8. `RunResult` を待機（タイムアウト: 5分）
9. 収集した `TestResult` を集約して `McpRunTestsResponse` を返却

### エラーハンドリング

| 状況 | エラーメッセージ |
|---|---|
| Unity Editor未接続 | Unity Editor is not connected to Rider. |
| テスト実行開始失敗 | Failed to start test execution in Unity Editor. |
| タイムアウト (5分) | Test execution timed out after 5 minutes. |

### .NET プロジェクト設定

- **Target Framework**: `net472` （Rider 2025.3のReSharper Backendと同じ。net8.0への移行があれば要調整）
- **NuGet**: `JetBrains.ReSharper.SDK 2025.3.0`
- **DLL参照**: resharper-unity プラグインの DLL（`plugins/rider-unity/dotnet/` から）

---

## 実装サブステップ

### 6-1: プロトコルサブプロジェクトとrd-genセットアップ

**新規ファイル**: `protocol/build.gradle.kts`, `protocol/src/main/kotlin/model/rider/UnityTestMcpModel.kt` (簡易版)
**改修ファイル**: `settings.gradle.kts`, `gradle.properties`, `build.gradle.kts`

**検証**:
```bash
JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew :protocol:rdgen
```
- `src/main/generated/` と `src/dotnet/RiderUnityTestMcp/Model/` に生成コードが出力されること

### 6-2: 完全なRdモデル定義

**改修ファイル**: `protocol/src/main/kotlin/model/rider/UnityTestMcpModel.kt`

**検証**:
```bash
JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew :protocol:rdgen && ./gradlew compileKotlin
```
- 生成Kotlinコードに `McpRunTestsRequest`, `McpRunTestsResponse` 等のデータクラスが含まれること
- `compileKotlin` が成功すること

### 6-3: C# Backendプロジェクト（スケルトン）

**新規ファイル**: `src/dotnet/Directory.Build.props`, `src/dotnet/RiderUnityTestMcp.sln`, `src/dotnet/RiderUnityTestMcp/RiderUnityTestMcp.csproj`, `src/dotnet/RiderUnityTestMcp/ZoneMarker.cs`, `src/dotnet/RiderUnityTestMcp/UnityTestMcpHandler.cs` (ハードコードレスポンス)
**改修ファイル**: `build.gradle.kts` (`compileDotNet`, `generateDotNetSdkProperties`, `PrepareSandboxTask`), `.gitignore`

**検証**:
```bash
JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew buildPlugin
```
- `build/distributions/` のZIPに `dotnet/RiderUnityTestMcp.dll` が含まれること
- Riderにインストール後、ログにコンポーネント初期化エラーがないこと

### 6-4: Kotlin Frontend → Rd → C# Backend の疎通確認

**改修ファイル**: `src/main/kotlin/.../RunUnityTestsToolset.kt`

**検証**:
1. プラグインをインストール
2. Claude Codeから `run_unity_tests` を呼び出し
3. C# Backendのハードコードレスポンス（例: `passCount: 42`）が返ること

### 6-5: resharper-unity DLL参照とBackendUnityHostアクセス

**改修ファイル**: `build.gradle.kts` (`generateDotNetSdkProperties`にRiderUnityPluginPath追加), `.csproj` (DLL参照追加), `UnityTestMcpHandler.cs` (BackendUnityHostインジェクション)

**検証**:
- Unity Editor未接続時: `error: "Unity Editor is not connected to Rider."` が返ること
- Unity Editor接続時: スケルトンレスポンスが返ること

### 6-6: テスト実行ロジック実装

**改修ファイル**: `src/dotnet/RiderUnityTestMcp/UnityTestMcpHandler.cs`

**検証**:
1. Unity EditModeテストを含むプロジェクトを開く
2. `run_unity_tests(testMode: "EditMode")` でテストが実行されること
3. `passCount`, `failCount` が正しいこと
4. 失敗テストの `failedTests` にoutputが含まれること
5. `assemblyNames`, `testNames`, `groupNames` フィルタが機能すること

### 6-7: エラーハンドリングと仕上げ

**改修ファイル**: `UnityTestMcpHandler.cs` (タイムアウト、エラーハンドリング)

**検証**:
- 各エラーシナリオで適切なメッセージが返ること
- 無効な `testMode` でKotlin側でバリデーションエラーが返ること

---

## リスクと対策

| リスク | 影響 | 対策 |
|---|---|---|
| resharper-unity DLLのパスがバージョンで異なる | ビルド失敗 | `intellijPlatform.platformPath` から動的解決。パスが変わってもGradleタスクのみ修正 |
| .NET Target Framework不一致 (net472 vs net8.0) | DLLロード失敗 | Rider SDK内のDLLメタデータで確認。NuGetリストアの失敗メッセージで判断可能 |
| rd-genバージョンとRider SDK不一致 | ランタイムシリアライズエラー | Rider SDKの `rider-model.jar` をrd-genに渡すことで基本型の整合性を確保 |
| BackendUnityHostがinternal | DIインジェクション不可 | resharper-unityソースで public を確認済み。万一の場合は `ISolution.GetComponent<>()` にフォールバック |
| TestResult/RunResultの競合状態 | 結果の欠損 | RunViaUnityEditorStrategyと同じパターン: 購読はStart()前に行う。RunResultはTestResult完了後に発火される保証あり |

---

## 変更対象ファイル一覧

| ファイル | 状態 | 概要 |
|---|---|---|
| `settings.gradle.kts` | 改修 | `:protocol` include, `pluginManagement` ブロック追加 |
| `gradle.properties` | 改修 | `rdGenVersion`, `dotNetPluginId`, `buildConfiguration` 追加 |
| `build.gradle.kts` | 改修 | riderModel設定, generated srcDir, compileDotNet, PrepareSandboxTask |
| `.gitignore` | 改修 | generated/, dotnet bin/obj/ パターン追加 |
| `protocol/build.gradle.kts` | 新規 | rd-gen設定 (Kotlin/C#両方向生成) |
| `protocol/src/.../UnityTestMcpModel.kt` | 新規 | Rdモデル定義 |
| `src/main/kotlin/.../RunUnityTestsToolset.kt` | 改修 | Rd call使用、レスポンス形式変更、categoryNamesリネーム |
| `src/dotnet/Directory.Build.props` | 新規 | 共通MSBuild設定 |
| `src/dotnet/RiderUnityTestMcp.sln` | 新規 | .NETソリューション |
| `src/dotnet/RiderUnityTestMcp/RiderUnityTestMcp.csproj` | 新規 | C#プロジェクト |
| `src/dotnet/RiderUnityTestMcp/ZoneMarker.cs` | 新規 | ゾーンマーカー |
| `src/dotnet/RiderUnityTestMcp/UnityTestMcpHandler.cs` | 新規 | テスト実行ハンドラ |
| `plugin.xml` | 変更なし | 依存関係はStep 5で宣言済み |
| `CLAUDE.md` | 改修 | Step 6ステータス更新 |
