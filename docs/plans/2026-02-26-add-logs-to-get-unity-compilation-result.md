# Add console log collection to `get_unity_compilation_result`

## Context

`run_method_in_unity` ツールは Step 9 で `UnityConsoleLogCollector` を使ってコンソールログ（Error, Warning, Message）を収集し、MCP レスポンスの `logs` フィールドに返すようになった。`get_unity_compilation_result` にも同じログ収集機能を追加し、コンパイル時に Unity Editor が出力するログ（コンパイルエラー詳細など）を返せるようにする。フィルタリングは不要で、全レベルのログをそのまま返す。

## Approach

`RunMethodInUnityToolset.kt` のパターンを踏襲し、`CompilationResultToolset.kt` に `UnityConsoleLogCollector` を組み込む。

- **C# バックエンド変更なし** — ログ収集は Kotlin フロントエンドの `FrontendBackendModel.consoleLogging.onConsoleLogEvent` シグナルを使うため
- **Rd モデル変更なし** — `McpCompilationResponse` への変更不要（ログは Kotlin 側で付加）
- 既存の `UnityConsoleLogCollector` と `CollectedLogEntry` をそのまま再利用

## Files to Modify

| File | Change |
|------|--------|
| `src/main/kotlin/.../CompilationResultToolset.kt` | ログ収集追加、Result 型に `logs` 追加、Serializer 更新 |
| `src/test/kotlin/.../CompilationResultToolsetTest.kt` | ログ付きシリアライゼーションのテスト追加 |

## Reuse

- `UnityConsoleLogCollector` (`src/main/kotlin/.../UnityConsoleLogCollector.kt`) — start/stop lifecycle, そのまま利用
- `CollectedLogEntry` — 同上
- `LOG_FLUSH_DELAY_MS` パターン — `RunMethodInUnityToolset.companion` から定数を参照するか、`CompilationResultToolset` に同じ定数を定義

## Design

### `CompilationResultToolset.kt` の変更

1. **import 追加**: `frontendBackendModel`, `isConnectedToEditor`, `delay`, `withTimeout`
2. **`LOG_FLUSH_DELAY_MS` 定数** を companion object に定義（`500L`）
3. **`get_unity_compilation_result()` メソッド**:
   - Unity Editor 接続チェック追加（`project.isConnectedToEditor()`）
   - `UnityConsoleLogCollector` を作成、`start()` → Rd 呼び出し → `delay(500ms)` → `stop()`
   - `RunMethodInUnityToolset` と同じパターン（collector 変数の null 管理、例外時の cleanup）
   - 成功/失敗どちらも `logs` を含めて返す
4. **Result 型変更**:
   - `CompilationSuccessResult` を `object` → `data class` に変更し `logs` フィールド追加
   - `CompilationErrorResult` に `logs: List<CollectedLogEntry> = emptyList()` フィールド追加
5. **`CompilationResultSerializer` 更新**: 両ケースで `logs` 配列を出力

### レスポンス JSON 形式

**成功（ログあり）:**
```json
{"success":true,"logs":[{"type":"Message","message":"Reimporting...","stackTrace":""}]}
```

**成功（ログなし）:**
```json
{"success":true,"logs":[]}
```

**エラー（ログあり）:**
```json
{"success":false,"errorMessage":"Unity compilation failed.","logs":[{"type":"Error","message":"CS0246...","stackTrace":""}]}
```

**エラー（ログなし — 例外やバリデーション失敗）:**
```json
{"success":false,"errorMessage":"TimeoutCancellationException: ...","logs":[]}
```

### MCP ツール description 更新

```
Trigger Unity's AssetDatabase.Refresh() and check if compilation succeeded.
Useful for verifying that code changes compile before running tests.
Console logs (Debug.Log, Debug.LogWarning, Debug.LogError) generated during compilation are captured
and returned in the "logs" field of the response.
```

### Test Cases of kotlin tests

#### CompilationResultToolsetTest

| Test Method | Description |
|---|---|
| `CompilationErrorResult_serializes_to_error_pattern` | 既存テスト — ログなしエラーのシリアライゼーション（`logs:[]` が追加されることを確認するよう更新） |
| `CompilationSuccessResult_serializes_correctly` | 既存テスト — ログなし成功のシリアライゼーション（`logs:[]` が追加されることを確認するよう更新） |
| `CompilationErrorResult_with_compilation_failure_serializes_correctly` | 既存テスト — 更新 |
| `CompilationSuccessResult_with_logs_serializes_correctly` | ログ付き成功レスポンスのシリアライゼーション |
| `CompilationErrorResult_with_logs_serializes_correctly` | ログ付きエラーレスポンスのシリアライゼーション |
| `CompilationSuccessResult_log_entry_serializes_all_fields` | ログの全フィールド（type, message, stackTrace）がシリアライズされること |

### Manual Tests

| # | Item | Verification Method |
|---|------|---------------------|
| 1 | コンパイル成功時にログが返る | Rider で Unity プロジェクトを開き、`get_unity_compilation_result` を Claude Code から呼び出し、レスポンスの `logs` フィールドを確認 |
| 2 | コンパイルエラー時にエラーログが返る | Unity プロジェクトにコンパイルエラーを意図的に導入し、`get_unity_compilation_result` を呼び出して `logs` にエラーログが含まれることを確認 |

## Development Workflow

### Step 1: Skeleton (Compilable)

1. `CompilationResultToolset.kt` の Result 型に `logs` フィールドを追加
2. `CompilationSuccessResult` を `object` → `data class(logs)` に変更
3. `CompilationErrorResult` に `logs` パラメータ追加（デフォルト `emptyList()`）
4. `CompilationResultSerializer` を `logs` 配列出力に対応
5. `companion object` に `LOG_FLUSH_DELAY_MS` 定数追加
6. ビルドが通ることを確認

### Step 2: Test First

1. `CompilationResultToolsetTest.kt` の既存テスト3件を `logs` 付き期待値に更新
2. 新規テスト3件を追加（ログ付き成功、ログ付きエラー、全フィールド確認）
3. テスト実行 → 失敗を確認
4. git commit

### Step 3: Implementation

1. `get_unity_compilation_result()` に `UnityConsoleLogCollector` の start/stop ライフサイクルを実装
2. Unity Editor 接続チェック追加
3. `withTimeout` でタイムアウト対応（`MCP_TOOL_TIMEOUT` 環境変数）
4. `delay(LOG_FLUSH_DELAY_MS)` でログフラッシュ待ち
5. 例外時の collector cleanup
6. MCP description 更新
7. `mcp__jetbrains__get_file_problems` でエラー確認
8. テスト実行 → 全件パスを確認
9. git commit

### Step 4: Refactoring

1. DRY/KISS/SOLID 観点でリファクタリング（`LOG_FLUSH_DELAY_MS` の共有化を検討）
2. `mcp__jetbrains__get_file_problems` で suggestion 以上を解消
3. `mcp__jetbrains__reformat_file` でフォーマット
4. テスト再実行 → パス確認
5. git commit

## Verification

1. `./gradlew --no-configuration-cache test` でユニットテスト全件パス
2. `./gradlew --no-configuration-cache buildPlugin` でプラグインビルド成功
3. （手動）Rider にインストールし、Unity プロジェクトで `get_unity_compilation_result` を Claude Code から呼び出し、`logs` フィールドにコンソールログが含まれることを確認
