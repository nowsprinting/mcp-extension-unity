# Remove `logs` field from `RunMethodInUnityErrorResult`

## Context

`run_method_in_unity` の `success: false` レスポンスに `logs` フィールドが含まれているが、エラー時にはログは不要なので削除する。

## Changes

### `src/main/kotlin/com/nowsprinting/mcp_extension_unity/RunMethodInUnityToolset.kt`

1. **`RunMethodInUnityErrorResult` data class (line 116-119)**: `logs` パラメータを削除
2. **`RunMethodInUnityResultSerializer` error case (line 131-143)**: `putJsonArray("logs")` ブロックを削除
3. **Call site (line 103)**: `RunMethodInUnityErrorResult(formatErrorMessage(...), logs)` から `logs` 引数を削除

### `src/test/kotlin/com/nowsprinting/mcp_extension_unity/RunMethodInUnityToolsetTest.kt`

4. **Line 52**: expected JSON を `{"success":false,"errorMessage":"Assembly not found."}` に変更
5. **Lines 80-86**: `RunMethodInUnityErrorResult with logs serializes correctly` テストを削除（`logs` フィールド自体がなくなるため）
6. **Lines 88-93**: expected JSON を `{"success":false,"errorMessage":"err"}` に変更

## Test Cases of kotlin tests

#### RunMethodInUnityToolsetTest

| Test Method | Description |
|---|---|
| `RunMethodInUnityErrorResult serializes to error pattern` | エラー結果に `logs` フィールドが含まれないことを確認 |
| `RunMethodInUnityErrorResult with default empty logs serializes correctly` | → テスト名を変更不要。expected JSON から `logs` を除去 |
| `RunMethodInUnityErrorResult with logs serializes correctly` | **削除** — `logs` フィールドが存在しないため不要 |

既存の Success 系テストは変更なし。

## Development Workflow

### Step 1: Skeleton (Compilable)

不要（既存クラスの変更のみ）

### Step 2: Test First

1. テストコードを先に修正（expected JSON の変更、不要テスト削除）
2. テスト実行 → プロダクトコード未変更のため **fail** を確認
3. Commit

### Step 3: Implementation

1. `RunMethodInUnityErrorResult` から `logs` を削除
2. Serializer のエラーケースから `logs` 出力を削除
3. Call site (line 103) から `logs` 引数を削除
4. `mcp__jetbrains__get_file_problems` でエラーがないことを確認
5. テスト実行 → **pass** を確認
6. Commit

### Step 4: Refactoring

1. 不要な import があれば削除
2. `mcp__jetbrains__reformat_file` で整形
3. Commit
