# Step 8: Test Cancellation/Disconnection Handling

## Context

テスト実行中にUnity EditorのTest Runnerウィンドウでキャンセルすると、`RunResult`シグナルが発火しない場合があり、MCP client側はタイムアウトまで待ち続けてしまう。
また、Unity Editorのクラッシュ・終了・ドメインリロードなど、テスト実行中の接続断にも対応できていない。

resharper-unityの`RunViaUnityEditorStrategy.cs`を参考に、キャンセル/切断検出とクリーンアップ処理を追加する。
加えて、タイムアウト値を環境変数 `MCP_TOOL_TIMEOUT` で設定可能にする。

## Changes

**対象ファイル**: `src/dotnet/McpExtensionUnity/UnityTestMcpHandler.cs` のみ（+ CLAUDE.md更新）

### 8-1: `lt.OnTermination` ハンドラ追加

TCS作成直後に `lt.OnTermination(() => tcs.TrySetCanceled())` を追加。
Rdライフタイム終了時（Kotlin側コルーチンキャンセル、プロトコル切断）に即座にTCSをキャンセルする。

### 8-2: `BackendUnityModel` 切断監視

`backendUnityHost.BackendUnityModel.Advise(lt, ...)` でモデルがnullになったことを検出。
Unity Editor終了・クラッシュ・ドメインリロード時に即座にエラーを返す。

```csharp
backendUnityHost.BackendUnityModel.Advise(lt, model =>
{
    if (model == null)
        tcs.TrySetException(new Exception(
            "Unity Editor disconnected during test execution. " +
            "This may be caused by a domain reload, crash, or the editor being closed."));
});
```

### 8-3: `TryAbortLaunch` ヘルパーメソッド追加

タイムアウトやキャンセル時に `launch.Abort.Start()` を呼び出し、Unity側のテスト実行を停止する（ベストエフォート）。

```csharp
private static void TryAbortLaunch(Lifetime lt, BackendUnityHost host, UnitTestLaunch launch)
```

- ライフタイム生存確認、モデルnullチェック、セッションID一致確認後にAbort
- 例外はcatchしてログのみ（Unity既に切断されている場合あり）

### 8-4: 環境変数 `MCP_TOOL_TIMEOUT` によるタイムアウト設定

環境変数 `MCP_TOOL_TIMEOUT` でテスト実行のタイムアウト（秒）を設定可能にする。

- 未設定 or パース失敗時: デフォルト300秒（5分）
- 読み取り: `Environment.GetEnvironmentVariable("MCP_TOOL_TIMEOUT")`
- 型: `int`（秒単位）
- `TimeSpan.FromSeconds(timeoutSeconds)` で `CancellationTokenSource` に使用
- エラーメッセージにも実際のタイムアウト値を含める: `$"Test execution timed out after {timeoutSeconds} seconds."`

```csharp
var timeoutSeconds = 300; // default: 5 minutes
var envTimeout = Environment.GetEnvironmentVariable("MCP_TOOL_TIMEOUT");
if (envTimeout != null && int.TryParse(envTimeout, out var parsed) && parsed > 0)
    timeoutSeconds = parsed;
```

### 8-5: 待機ロジック再構築

タイムアウトを `TrySetCanceled` → `TrySetException("...timed out...")` に変更し、catch文で原因を区別:

| 例外型 | 原因 | レスポンス |
|---|---|---|
| `OperationCanceledException` | Rdライフタイム終了 | "Test execution was cancelled..." |
| `Exception` | タイムアウト or Unity切断 | `ex.Message` をそのまま返す |

両方のエラーパスで `TryAbortLaunch` を呼び出す。

### 8-6: CLAUDE.md更新

- Important Constraintsセクションにキャンセル/タイムアウト処理の注記を追加
- `MCP_TOOL_TIMEOUT` 環境変数の説明を追加

## Thread Safety

- `tcs.TrySetCanceled()` / `TrySetResult()` / `TrySetException()` はすべてスレッドセーフ（`RunContinuationsAsynchronously`付き）。先勝ちで1つだけ成功する。
- `lt.OnTermination` コールバック: Rdスケジューラスレッドで実行
- `BackendUnityModel.Advise` コールバック: Rdスケジューラスレッドで実行
- `CancellationTokenSource.Token.Register` コールバック: タイマースレッドで実行
- 複数シグナルが競合しても`Try*`パターンにより1つだけ勝つ。いずれの結果も正しい。

## Limitations

Unity Test Runnerウィンドウでのキャンセル時に `RunResult` が発火しない場合、タイムアウトまで待つ。
これはresharper-unity自体にも同様の制限があり、Unity側のRdモデルを変更しない限り検出できない。
`MCP_TOOL_TIMEOUT` で短いタイムアウトを設定することで、この場合のフィードバック速度を改善できる。

## Verification

ビルド: `JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew --no-configuration-cache buildPlugin`

| # | シナリオ | 期待結果 |
|---|---|---|
| 1 | 正常なテスト実行（EditMode） | 動作変更なし |
| 2 | テスト実行中にUnity Editorを閉じる | 即座にエラー: "Unity Editor disconnected..." + Abort試行 |
| 3 | タイムアウト（デフォルト5分） | エラー: "timed out after 300 seconds" + Abort試行 |
| 4 | `MCP_TOOL_TIMEOUT=60` でタイムアウト | エラー: "timed out after 60 seconds" + Abort試行 |
| 5 | Unity Test RunnerでCancel（RunResult発火する場合） | 正常完了（部分結果） |
| 6 | Unity Test RunnerでCancel（RunResult発火しない場合） | タイムアウト後にエラー（既知の制限） |
