# PoC調査結果: Rider MCPカスタムツールによるUnityテスト実行

> **実装状況 (2026-02-23更新)**: Steps 1–7 完了。`assemblyNames` バリデーション追加・ユニットテスト整備・E2E人手検証すべて完了。

## 1. 背景・目的

Coding Agent（Claude Code等）からUnityテストを実行する際に、Riderの実行基盤を経路として使いたい。
RiderのUnit Testsツールウィンドウは内部的にRun Configurationを動的に生成して実行しているように見えるため、以下を調査した:

1. RiderのUnit Testsツールウィンドウが生成するRun Configurationと同等のものを、Coding Agentから組み立てられるか？
2. JetBrains MCP Serverで、Run Configurationを実行できるか？（→ `execute_run_configuration`で可能）

## 2. Riderのテスト実行アーキテクチャ

### 2.1 全体構成

Riderは2層のRd（Reactive Distributed）プロトコル接続で構成される:

```
Kotlin Frontend  ←Rd (FrontendBackendModel)→  C# Backend (ReSharper)  ←Rd (BackendUnityModel)→  Unity Editor
```

- **Kotlin Frontend**: UI、MCP Server、ユーザー操作のハンドリング
- **C# Backend (ReSharper)**: コード解析、テスト実行ロジック
- **Unity Editor**: 実際のテスト実行（`TestRunnerApi.Execute()`）

### 2.2 テスト実行フロー

Riderの「Unit testing configuration」の実体は通常のRun ConfigurationではなくReSharperのUnit Testセッション管理に基づく:

1. `UnityNUnitServiceProvider.GetRunStrategy()` が実行戦略を選択
2. `IsUnityUnitTestStrategy()` がtrueなら `RunViaUnityEditorStrategy` を返す
3. `RunViaUnityEditorStrategy` が Rdプロトコルで Unity Editor にテスト実行を要求:
   - ソリューション保存 → Unityリフレッシュ
   - コンパイル結果確認（`model.GetCompilationResult.Start()`）
   - `UnitTestLaunch` オブジェクト作成（テストフィルタ + モード）
   - `model.RunUnitTestLaunch.Start()` でRPC送信
   - `launch.TestResult.AdviseNotNull()` で個別結果を購読
   - `launch.RunResult.AdviseNotNull()` で全体結果を購読

### 2.3 Rdモデル定義

**BackendUnityModel** (C# Backend ↔ Unity Editor):

| Property/Call | 型 | 用途 |
|---|---|---|
| `unitTestLaunch` | Property\<UnitTestLaunch\> | テスト実行設定 |
| `runUnitTestLaunch` | Call\<Unit, Boolean\> | テスト実行トリガー |
| `testResult` | Sink\<TestResult\> | 個別テスト結果 |
| `runResult` | Sink\<RunResult\> | 全体結果 |

**UnitTestLaunch**: `sessionId` (guid), `testFilters` (List\<TestFilter\>), `testMode` (Both/Edit/Play)

**TestFilter**: `assemblyName`, `testNames`, `groupNames`, `testCategories`

**TestResult**: `testId`, `projectName`, `output`, `duration`, `status` (Pending/Running/Inconclusive/Ignored/Success/Failure), `parentId`

**FrontendBackendModel** (Kotlin Frontend ↔ C# Backend):
- `unitTestPreference` (NUnit/EditMode/PlayMode/Both) — フロントエンドからはモード選択のみ
- `unityEditorConnected` — Unity Editor接続状態

### 2.4 実行経路の比較

| 経路 | トランスポート | テスト実行場所 | 結果統合 |
|---|---|---|---|
| Rider Unit Tests窓 | Rd → Rd | Unity Editor（ライブ） | Rider UI |
| Unity Natural MCP | HTTP/MCP | Unity Editor（ライブ） | JSON（Agent内） |
| バッチモード | CLI args | 別Unityプロセス | Console/XML |

Rider経路とUnity Natural MCP経路は、最終的に同じ `TestRunnerApi.Execute()` に到達する。違いはトランスポート層のみ。

### 2.5 重要な制約

- Riderの「Unit testing configuration」は**XMLで再現不可能**（Rdプロトコルベースでメモリ上で動的生成）
- `get_run_configurations` で見える「Unit testing configuration」はMCPブリッジ経由の表示であり、XMLファイルとして永続化されていない
- Kotlin Frontendから `BackendUnityModel` に**直接アクセスできない**（2つの独立したRd接続のため）
- JetBrains MCP Serverに `create_run_configuration` ツールは**存在しない**

## 3. JetBrains MCP Server拡張ポイント

### 3.1 拡張メカニズム

Rider 2025.2以降の内蔵MCP Serverは、`com.intellij.mcpServer` extension pointでカスタムツールの登録をサポートする:

- `McpToolset` インターフェースを実装するクラスを作成
- `@McpTool` アノテーションでツールメソッドをマーク
- `@McpDescription` でツールやパラメータの説明を付与
- `plugin.xml` で `<mcpToolset>` として登録

### 3.2 実装パターン（Rider 2025.3.3で確認）

```kotlin
class RunUnityTestsToolset : McpToolset {
    @McpTool(name = "run_unity_tests")
    @McpDescription(description = "Run Unity tests through Rider's test infrastructure")
    suspend fun run_unity_tests(
        @McpDescription(description = "Test mode: EditMode or PlayMode")
        testMode: String = "EditMode",
        // ...
    ): RunUnityTestsResult { ... }
}
```

```xml
<extensions defaultExtensionNs="com.intellij.mcpServer">
    <mcpToolset implementation="com.github.rider.unity.mcp.RunUnityTestsToolset"/>
</extensions>
```

**注意**: 旧来の `AbstractMcpTool<T>` パターン（[mcpExtensionPlugin Demo](https://github.com/MaXal/mcpExtensionPlugin)）は Rider 2025.3.3 バンドル版では非推奨。`McpToolset` + `@McpTool` アノテーションパターンが正。

### 3.3 PoC中に遭遇した問題

`@McpTool` アノテーションを付けずにビルドしたところ、Riderログに以下のエラー:

```
WARN - ReflectionToolsProvider - Cannot load tools for RunUnityTestsToolset
java.lang.IllegalArgumentException: No tools found in class ...RunUnityTestsToolset
```

`@McpTool` + `@McpDescription` アノテーションの追加で解決。

## 4. PoC実装

### 4.1 プロジェクト構成

```
rider-unity-test-mcp-plugin/
├── build.gradle.kts          # Gradle IntelliJ Platform Plugin 2.11.0, Kotlin 2.3.0
├── gradle.properties
├── settings.gradle.kts
├── src/main/
│   ├── kotlin/com/github/rider/unity/mcp/
│   │   └── RunUnityTestsToolset.kt   # MCPツール実装
│   └── resources/META-INF/
│       └── plugin.xml                 # プラグイン定義
```

### 4.2 ビルド設定

- **ターゲット**: Rider 2025.3.3 (build RD-253.31033.136)
- **Kotlin**: 2.3.0 + kotlinx-serialization
- **JDK**: 21
- **IntelliJ Platform Gradle Plugin**: 2.11.0
- **依存**: `bundledPlugin("com.intellij.mcpServer")`（Rider内蔵）
- **sinceBuild**: 253

### 4.3 実装コード (Steps 1–7完了時点)

- `run_unity_tests` MCPツールを1つ登録
- パラメータ: `testMode`, `assemblyNames`（必須）, `testNames`, `groupNames`, `categoryNames`
- `assemblyNames` 未指定時は Kotlin 側で即座にエラー返却（Unity Editor 切断を防止）
- Rd経由で C# バックエンドに `McpRunTestsRequest` を送信し、`McpRunTestsResponse` を受け取る
- C# バックエンドが `BackendUnityModel.UnitTestLaunch` + `RunUnitTestLaunch` でUnity Editorにテスト実行を要求
- 戻り値: `RunUnityTestsResult`（`passCount`, `failCount`, `failedTests[]` 等）

### 4.4 ビルド & インストール手順

```bash
JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew buildPlugin
```

1. `build/distributions/` に生成されたZIPファイルを確認
2. Rider → Settings → Plugins → Install Plugin from Disk でZIPを選択
3. Rider を再起動

### 4.5 検証結果

- Claude Codeの MCPツール一覧に `run_unity_tests` が表示される ✅
- ツールを呼び出すと `poc_echo` レスポンスが返る ✅
- パラメータ（testMode, assemblyNames等）が正しくシリアライズ/デシリアライズされる ✅

## 5. 実装済みアーキテクチャ (Steps 1–7完了)

```
Coding Agent (Claude Code)
    ↓ MCP (HTTP/SSE)
JetBrains MCP Server (Rider内蔵)
    ↓ extension point (com.intellij.mcpServer)
[Plugin Frontend - Kotlin]  ✅ RunUnityTestsToolset.kt
    ↓ UnityTestMcpModel (カスタムRd: IRdCall<McpRunTestsRequest, McpRunTestsResponse>)
[Plugin Backend - C#]       ✅ UnityTestMcpHandler.cs
    ↓ BackendUnityModel.UnitTestLaunch + RunUnitTestLaunch (既存Rd)
Unity Editor                ✅ E2E人手検証完了
    ↓ TestRunnerApi.Execute()
テスト実行
```

### 実装済みの主要機能

- `assemblyNames` 必須バリデーション（Kotlin側で空フィルタ防止）
- カスタムRdモデル `UnityTestMcpModel` によるプロセス間通信
- C# バックエンドの `IStartupActivity` 実装によるeager instantiation
- ユニットテスト: Kotlin 6件 + C# 5件

## 参考リソース

- [resharper-unity](https://github.com/JetBrains/resharper-unity) — Rider Unity Supportのソースコード
  - [BackendUnityModel.kt](https://github.com/JetBrains/resharper-unity/blob/master/rider/protocol/src/main/kotlin/model/backendUnity/BackendUnityModel.kt)
  - [FrontendBackendModel.kt](https://github.com/JetBrains/resharper-unity/blob/master/rider/protocol/src/main/kotlin/model/frontendBackend/FrontendBackendModel.kt)
  - [RunViaUnityEditorStrategy.cs](https://github.com/JetBrains/resharper-unity/blob/master/resharper/resharper-unity/src/Unity.Rider/Integration/Core/Feature/UnitTesting/RunViaUnityEditorStrategy.cs)
  - [UnityNUnitServiceProvider.cs](https://github.com/JetBrains/resharper-unity/blob/master/resharper/resharper-unity/src/Unity.Rider/Integration/Core/Feature/UnitTesting/UnityNUnitServiceProvider.cs)
  - [UnitTestLauncherState.kt](https://github.com/JetBrains/resharper-unity/blob/master/rider/src/main/kotlin/com/jetbrains/rider/plugins/unity/ui/unitTesting/UnitTestLauncherState.kt)
- [mcpExtensionPlugin Demo](https://github.com/MaXal/mcpExtensionPlugin) — MCP拡張のリファレンス（旧APIパターン）
- [JetBrains MCP Server Plugin](https://github.com/JetBrains/mcp-server-plugin) — 拡張ポイント仕様（deprecated → built-in）
- [MCP Server | JetBrains Rider Documentation](https://www.jetbrains.com/help/rider/mcp-server.html)
- [IntelliJ Platform Plugin SDK - Run Configurations](https://plugins.jetbrains.com/docs/intellij/run-configurations.html)
