# Step 7: End-to-End Verification Checklist

## Context

Steps 1–6 are complete. The plugin (`MCP Server Extension for Unity`) implements:
- Kotlin frontend: `RunUnityTestsToolset.kt` — MCP tool `run_unity_tests`
- Rd model: `UnityTestMcpModel` — bridges Kotlin ↔ C# process boundary
- C# backend: `UnityTestMcpHandler.cs` — calls `BackendUnityModel` to run Unity tests

**This step is human-only verification.** The goal is to confirm the full pipeline works with a real Unity project.

During Step 7, the following code fix was implemented:
- **Empty filter validation**: `assemblyNames` is now **required**. Calling `run_unity_tests` without `assemblyNames` returns an immediate error on the Kotlin side, preventing a situation where the Unity Editor disconnects due to an empty `TestFilter`.
- All test cases below have been updated to include `assemblyNames`.

---

## Prerequisites

- macOS with JDK 21 installed at `/usr/local/opt/openjdk@21`
- Rider 2025.3.3 (build `RD-253.31033.136`)
- Unity Editor (2022.3 LTS or later recommended)
- A Unity project with at least a few EditMode tests (e.g., in `Assets/Tests/EditMode/`)
- Claude Code (or any MCP client that can call Rider's MCP Server)

---

## Phase 1: Build the Plugin

### 1-1. Build the plugin ZIP

```bash
JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew --no-configuration-cache buildPlugin
```

**What to expect:**
- `BUILD SUCCESSFUL` at the end
- ZIP file created at: `build/distributions/mcp-extension-for-unity-*.zip`

**If it fails:**
- `rdgen` error → run `./gradlew --no-configuration-cache clean` then retry
- `msbuild` not found → make sure `dotnet` CLI or Mono is installed
- JDK error → verify `java -version` shows 21.x when using that JAVA_HOME

### 1-2. Verify the ZIP contents

```bash
unzip -l build/distributions/mcp-extension-for-unity-*.zip
```

**Check that these files exist inside the ZIP:**
- `mcp-extension-for-unity/lib/mcp-extension-for-unity-*.jar` (Kotlin side)
- `mcp-extension-for-unity/dotnet/McpExtensionForUnity.dll` (C# side)
- `mcp-extension-for-unity/dotnet/McpExtensionForUnity.pdb` (debug symbols)

> If `McpExtensionForUnity.dll` is missing → the `compileDotNet` task failed silently. Check build logs.

---

## Phase 2: Install the Plugin in Rider

### 2-1. Open Rider Settings

1. Rider を開く
2. メニューバー → **Rider** → **Settings...** (macOS) / **File** → **Settings** (Windows/Linux)
3. 左のツリーから **Plugins** を選ぶ

### 2-2. Install from Disk

1. 右上の ⚙️ (歯車アイコン) をクリック
2. **Install Plugin from Disk...** を選ぶ
3. ファイル選択ダイアログで `build/distributions/mcp-extension-for-unity-*.zip` を選ぶ
4. **OK** を押す

### 2-3. Restart Rider

1. 「Restart IDE」ボタンが表示される → **クリックして再起動**
2. 再起動を待つ

### 2-4. Verify Plugin is Loaded

1. 再起動後、再度 **Settings** → **Plugins** を開く
2. **Installed** タブを選ぶ
3. 「**MCP Server Extension for Unity**」が一覧にあり、有効 (チェックが入っている) ことを確認

> もし見つからない場合 → ZIPの構造が間違っている可能性。Phase 1-2 に戻って確認。

---

## Phase 3: Prepare Unity Project

### 3-1. Unity プロジェクトを開く

1. Unity Hub から適当な Unity プロジェクトを開く
2. **テストが既にあるプロジェクト**がベスト
3. テストがない場合は以下で作成（Phase 3-2 へ）

### 3-2. (テストがない場合) テストを作成する

1. Unity Editor → **Window** → **Test Runner** を開く
2. **EditMode** タブを選ぶ
3. 「Create EditMode Test Assembly Folder」ボタンがあれば押す
4. 以下のファイルを `Assets/Tests/EditMode/` に作成:

```csharp
// SampleTest.cs
using NUnit.Framework;

public class SampleTest
{
    [Test]
    public void PassingTest()
    {
        Assert.AreEqual(1, 1);
    }

    [Test]
    public void AnotherPassingTest()
    {
        Assert.IsTrue(true);
    }

    [Test]
    public void FailingTest()
    {
        Assert.AreEqual(1, 2, "This test is intentionally failing");
    }

    [Test]
    [Category("Slow")]
    public void CategorizedTest()
    {
        Assert.Pass();
    }
}
```

5. Unity Editor で **Ctrl+S** (保存) → コンパイルを待つ
6. Test Runner で上記テストが表示されることを確認

### 3-3. Rider で Unity プロジェクトを開く

1. Rider を起動
2. **File** → **Open** → Unity プロジェクトの `.sln` ファイルを開く
3. ウィンドウ右下のステータスバーに Unity アイコンが表示されるまで待つ

### 3-4. Unity Editor が接続されていることを確認

1. Rider のウィンドウ右下のステータスバーを見る
2. Unity アイコンが **緑** (接続済み) であること
3. 赤やグレーの場合は Unity Editor が起動していない or 別プロジェクトが開かれている

> **重要**: Unity Editor と Rider は同じプロジェクトを開いている必要がある！

---

## Phase 4: MCP Server の準備

### 4-1. Rider の MCP Server を有効にする

1. Rider → **Settings** → **Tools** → **MCP Server**
2. **Enable MCP Server** にチェックが入っていることを確認
3. ポート番号を確認 (デフォルト: 自動割り当て)

### 4-2. Claude Code の MCP 設定

Claude Code の設定ファイル (`~/.claude/settings.json` や `.mcp.json`) で Rider の MCP Server を接続する。

設定方法は Rider の MCP Server ページ (**Settings** → **Tools** → **MCP Server**) に表示される接続情報を参照。

### 4-3. MCP ツール一覧を確認

Claude Code で以下を確認:
- `run_unity_tests` ツールが認識されているか
- ツールの説明・パラメータが表示されるか

> 表示されない場合 → プラグインが正しくロードされていない or MCP Server 設定に問題

---

## Phase 5: テスト実行 — メインの検証

### Test Case 1: 全 EditMode テストを実行

Claude Code から:
```
run_unity_tests ツールを使って、assemblyNames に "TestHelper.Editor.Tests" を指定して EditMode のテストを全部実行して
```

> アセンブリ名は Unity プロジェクトの `.asmdef` ファイル名に合わせること。Rider の Unit Test Explorer でも確認可。

**期待される結果:**
```json
{
  "passCount": 3,
  "failCount": 1,
  "skipCount": 0,
  "inconclusiveCount": 0,
  "failedTests": [
    {
      "testId": "SampleTest.FailingTest",
      "output": "...",
      "duration": 0
    }
  ],
  "inconclusiveTests": []
}
```

チェック:
- [x] レスポンスが JSON で返ってくる
- [x] `passCount` が正しい (成功したテスト数と一致)
- [x] `failCount` が正しい (失敗したテスト数と一致)
- [x] `failedTests` に失敗テストの詳細がある
- [x] `failedTests[].testId` にテスト名が入っている
- [x] `failedTests[].output` にエラーメッセージが入っている

### Test Case 2: テスト名を指定して実行

Claude Code から:
```
run_unity_tests で assemblyNames に "TestHelper.Editor.Tests"、testNames に "SampleTest.PassingTest" を指定して実行して
```

**期待される結果:**
- `passCount: 1`, `failCount: 0`
- 他のテストは実行されない

チェック:
- [x] 指定したテストだけが実行される
- [x] `passCount` が 1

### Test Case 3: アセンブリ名を指定して実行

Claude Code から:
```
run_unity_tests で assemblyNames に "Tests" を指定して EditMode テストを実行して
```

> アセンブリ名は Unity プロジェクトの `.asmdef` ファイル名に依存する。Test Runner で確認。

チェック:
- [x] 指定アセンブリのテストだけが実行される

### Test Case 4: PlayMode テストを実行 (あれば)

```
run_unity_tests で assemblyNames に "TestHelper.PlayMode.Tests" を指定して PlayMode のテストを実行して
```

> PlayMode 用の .asmdef ファイル名に合わせること。

チェック:
- [x] PlayMode テストが実行される
- [x] Unity Editor が Play モードに入って戻ってくる

### Test Case 5: カテゴリ指定で実行

```
run_unity_tests で assemblyNames に "TestHelper.Editor.Tests"、categoryNames に "Slow" を指定して実行して
```

チェック:
- [x] `CategorizedTest` だけが実行される
- [x] `passCount: 1`

### Test Case 9: groupNames（正規表現）を指定して実行

Claude Code から:
```
run_unity_tests で assemblyNames に "TestHelper.Editor.Tests"、groupNames に "^SampleTest\\." を指定してテストを実行して
```

チェック:
- [x] 指定パターンにマッチするテスト（`SampleTest` クラスのみ）が実行される
- [x] 他のクラスのテストは実行されない

---

## Phase 6: エラーケースの検証

### Test Case 6: Unity Editor 未接続時

1. **Unity Editor を閉じる** (Rider はそのまま)
2. Rider の右下のステータスバーで Unity アイコンが赤/グレーになるまで待つ
3. Claude Code から `run_unity_tests` を実行

**期待される結果:**
```json
{
  "error": "Unity Editor is not connected to Rider. Please open Unity Editor with the project."
}
```

チェック:
- [x] クラッシュしない
- [x] エラーメッセージが返る
- [x] Rider がフリーズしない

### Test Case 7: 存在しないテスト名を指定

```
run_unity_tests で assemblyNames に "TestHelper.Editor.Tests"、testNames に "NonExistent.Test" を指定して実行して
```

チェック:
- [x] クラッシュしない
- [x] なんらかのレスポンスが返る (空の結果 or エラー)

### Test Case 8: assemblyNames を指定せずに実行（空フィルタバリデーション）

Claude Code から:
```
run_unity_tests で assemblyNames を指定せずにテストを実行して
```

**期待される結果:**
```json
{
  "error": "assemblyNames is required and must contain at least one non-empty assembly name. ..."
}
```

チェック:
- [x] Kotlin 側で即座にエラーが返る
- [x] Unity Editor に到達しない（切断されない）
- [x] エラーメッセージに assemblyNames が必須であることが記載される
- [x] エラーメッセージに `.asmdef` の案内が含まれる

---

## Phase 7: Rider ログの確認 (トラブルシューティング用)

何か問題が起きた場合：

### 7-1. Rider のログファイル

- **Help** → **Show Log in Finder** でログフォルダが開く
- `idea.log` を確認
- `McpExtensionForUnity` や `UnityTestMcp` で検索

### 7-2. よくある問題と対処

| 症状 | 原因 | 対処 |
|---|---|---|
| `run_unity_tests` ツールが表示されない | プラグイン未ロード | Settings → Plugins で有効か確認 |
| `No tools found in class` 警告 (ログ) | `@McpTool` 漏れ | ビルドし直し (コード修正が必要な場合) |
| Unity 接続エラーが出る | Unity Editor 未起動 | Unity Editor を同じプロジェクトで起動 |
| タイムアウト | テスト数が多すぎる | テスト数を減らして再試行 (上限5分) |
| `ClassNotFoundException` 等 | DLL未配置 | ZIP内に `McpExtensionForUnity.dll` があるか確認 |
| `assemblyNames is required` エラー | assemblyNames 未指定 | `.asmdef` ファイル名を確認して assemblyNames に指定 |

---

## Results Summary Template

検証結果を記録するテンプレート:

```
## Step 7 Verification Results — [日付]

### Environment
- Rider: [version]
- Unity: [version]
- macOS: [version]
- Plugin build: [OK / NG]

### Test Results
| # | Test Case | Result | Notes |
|---|---|---|---|
| 1 | All EditMode tests | [ ] | |
| 2 | Specific test name | [ ] | |
| 3 | Assembly filter | [ ] | |
| 4 | PlayMode tests | [ ] | |
| 5 | Category filter | [ ] | |
| 6 | Unity disconnected | [ ] | |
| 7 | Non-existent test | [ ] | |
| 8 | assemblyNames not specified (validation) | [ ] | |
| 9 | groupNames filter | [ ] | |

### Issues Found
- (issue description)

### Overall: [ ] PASS / [ ] FAIL
```

---

## Verification

This plan is documentation-only (a checklist). No code changes, no automated tests. Human executes each step manually and fills in the results template.

After verification:
- If all pass → Step 7 complete, PoC is validated
- If issues found → file them and fix in follow-up steps
