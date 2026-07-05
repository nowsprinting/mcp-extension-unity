# Rider 2026.2 (build 262) 互換対応 — Rd API 移行 & sinceBuild=262

## Context

このプラグインは現在 **Rider 2025.3.3 (build 253) SDK** に対してコンパイルされており、Rider **2026.2 (build 262)** にインストールすると壊れる。原因は Rd (Reactive Distributed) フレームワークの **バイナリ非互換な API 削除**。

`~/Downloads/RD-262.8665.68/report.html`（262-EAP9 に対する `verifyPlugin` 結果）は **4 件の互換性問題**を報告。全て 2 つの手書き Provider（`UnityTestMcpModelProvider` / `UnityCompilationMcpModelProvider`）の `getOrBindModel$lambda$0` に集中：

- `com.jetbrains.rd.framework.RdId.mix-0fMd8cM(long, String)` が未解決 → **NoSuchMethodError**（×2）
- `<Model>.identify-v_l8LFs(IIdentities, long)` が未解決（×2）

**javap による実測で根本原因を確定済み**：

| API | 253 / 261.3 (旧) | 262 (新) |
|-----|----------------|----------|
| mix | `RdId` の**インスタンスメソッド** `mix(String): RdId`（= 静的 `RdId.mix-0fMd8cM(long,String):long`） | **削除**。`IIdentities.mix-wiVt37Y(RdId, String): RdId`（identities 側インスタンスメソッド）へ移動。内部ヘルパ `RdIdUtil.mix` 追加 |
| identify | `RdBindableBase.identify(IIdentities, RdId)`（2 引数, `-v_l8LFs`） | `identify(IIdentities, RdId, Boolean)`（**3 引数**, `-Qh-KEmY`）。2 引数版は消滅 |

旧 API は 262 から**完全に消えている**ため、253 コンパイル成果物は 262 で必ず落ちる。

> **確定事実（javap 実測）**：253 と 262 に加え、**2026.1.3 (build `261.25134.178`) も実測**。2026.1.3 は旧 API のみを保持（`RdId.mix-0fMd8cM` 有り、`IIdentities.mix` 無し、`identify` は 2 引数、`RdIdUtil` 無し）— つまり **253 と 2026.1.3 は Rd API的に同一世代**であり、**262 だけが新世代**。

**重要な帰結**：現行 **v1.0.7（253 でコンパイル済み・既にリリース済み）は変更なしで 2026.1.3 上でもそのまま動く**。verifier が指摘した 4 件の非互換は「253→262」の間にのみ存在し、`preBind`/`bind`/`RdCall`/`RdExtBase`/serializers 等は全レンジで安定（verifier もこれ以外を指摘していない）。**261.3 は元々壊れていなかった**。壊れているのは 262 のみ。

**単一バイナリで 261.x と 262 の両方をカバーすることはできない**（確認済み・再検討不要）。理由：C# 側の Rd モデルは `reversed` ジェネレータによる**完全生成コード**で、Identify/Bind はコンストラクタに焼き込まれる。1 つの `McpExtensionUnity.dll` は 1 つの Rd 世代の API しか話せず、手書きコード側だけ reflection で新旧分岐しても C# 生成コードは分岐できない。

**JVM verifier に映らない C# 側も同じ問題を抱える**：rdgen を 262 対応版へ上げると生成 C# は新 C# API（`IIdentities.Mix` 等）を呼ぶため、253 の Rd.NET アセンブリではコンパイル不能 — 現行成果物の C# バックエンドも 262 上で暗黙に壊れる（verifier は検知しない）。

**確定した配布方針（ユーザー決定）**：
- **v1.0.7（253 コンパイル、既存リリース）はそのまま凍結**。261.x 系ユーザー向けとして維持し、追加の変更は行わない。
- **今後の新バージョンから `pluginSinceBuild=262`・`pluginUntilBuild` は設定しない（上限なし）** へ切り替える。本タスクはこの新ラインのみを対象とする。
- 262-EAP SDK（`~/Downloads/JetBrains Rider-262.8665.68/`）を使ってビルドしてよい。

これは 2 行修正ではなく、**コンパイルターゲットを丸ごと 262 へ移行**する作業（`2026-07-05-compatibility-verification-workflow.md` で「将来タスク」として先送りされていたもの）。261.3 のデュアルサポート検証（C# reflection プローブ等）は**不要**（上記の通り単一バイナリでの両対応自体を採用しないため）。

---

## Approach

### 1. コンパイルターゲットを 262 へ
`build.gradle.kts` の `dependencies { intellijPlatform { create("RD", "2025.3.3") } }` を、提供済みローカル 262-EAP SDK を指す **`local(...)`** に切替える。EAP はリポジトリ解決が壊れている（既知 issue #1852、`compatibility-verification.yml` の設計判断でも確認済み）ため `local` 一択。
- SDK パスは gradle プロパティ（例 `localRiderPath`）で外部注入できるようにし、ローカル開発と CI の双方から差し替え可能にする（既存 `verifyIdePaths` パターンに倣う）。
- `testFramework(TestFrameworkType.Platform)` はリポジトリから EAP 版 test-framework を引けない可能性がある → **実装時に確認**。引けない場合は test タスク用に別途 fallback を設計。
- `platformVersion` / `platformType` プロパティ（`gradle.properties`）と、`generateDotNetSdkProperties` が導出する `RiderSdkPath` / `RiderUnityPluginPath`（262 では `lib/ReSharperHost/*` と `plugins/rider-unity/dotnet/*` に健在＝確認済み）を追随。

### 2. rdgen を 2026.x へ
- `gradle.properties`：`rdGenVersion=2025.3.1` → 262 対応版（旧プラン注記では **2026.2.5**。maven 可用性を実装時に確認、無ければ SDK 同梱 `lib/rd-gen.jar` を使う）。
- `protocol/build.gradle.kts`：rdgen 2026.x の **DSL 変更に追随**（旧注記より `sources()` / `hashFolder` の廃止など）。`RdGenTask` import・ジェネレータ定義（root, language, transform, namespace, directory）を新 DSL で再構成。

### 3. モデル再生成（Kotlin + C#）
`:protocol:rdgen` を回し、`src/main/generated/*.Generated.kt` と `src/dotnet/McpExtensionUnity/Model/*.Generated.cs` を新 API 呼び出しで再生成。

### 4. 手書き Kotlin Provider の修正（互換問題の本体）
対象：`UnityTestMcpModelProvider.kt` / `UnityCompilationMcpModelProvider.kt`（各 18 行目付近）。

**まず調査**：rdgen 2026.x が Kotlin Root モデルに `create(lifetime, protocol)` **ファクトリを生成するか**を確認。
- **生成する** → reflection + `identify` + `mix` + `preBind` + `bind` の一連のハックを削除し、`UnityTestMcpModel.create(proto.lifetime, proto)` に置換（3 引数 identify の boolean を推測せずに済み、最もクリーン）。
- **生成しない**（現状 rdgen 2025.3.1 は private ctor・ファクトリ無し）→ 呼び出しを新 API へ手動移植：
  - `RdId.Null.mix("X")` → `proto.identity.mix(RdId.Null, "X")`（`IIdentities.mix`）
  - `model.identify(proto.identity, id)` → `model.identify(proto.identity, id, <bool>)`（3 引数）
  - **boolean 値は推測しない**。再生成された C# コンストラクタ（`reversed` 生成物）が Identify に渡す値を読んで一致させる（Provider の存在理由が「C# 生成コンストラクタの模倣」なので）。
  - `preBind`/`bind` の署名も 262 で変わっていないか併せて確認（verifier は未指摘なので恐らく不変）。

### 5. C# バックエンドの追随
- `src/dotnet/McpExtensionUnity/UnityTestMcpModelProvider.cs` / `UnityTestMcpHandler.cs` / `UnityCompilationMcpHandler.cs`：`compileDotNet`（`dotnet msbuild ...`）を 262 SDK に対して通し、破綻箇所を修正。
- `.csproj` の `TargetFramework`：現在 **net472**。262 のバックエンドが .NET (NetCore) 主体へ移行している可能性（`lib/ReSharperHost/NetCore/` サブフォルダの存在）→ **実装時に net472 で通るか確認**。通らなければ `net8.0` 等へ変更。
- 参照 DLL（`JetBrains.RdFramework` / `JetBrains.Lifetimes` / `JetBrains.Platform.*` / `JetBrains.Unity.Model` 等）は 262 で健在（確認済み）。API 破壊は `compileDotNet` が検出する。

### 6. ビルド配線の掃除
- `build.gradle.kts` の `riderModel` → `lib/rd.jar`：**262 には `lib/rd.jar` が無い**（`lib/intellij.libraries.rd.framework.jar` へ再分割）。Explore で **消費者が見つからなかった**ため、まず本当に dead か確認して**削除**。もし必要なら新 jar へ repoint。`check(it.isFile){"rd.jar not found"}` が発火し得る状態を残さない。

### 7. メタデータ / ドキュメント
- `gradle.properties`：`pluginSinceBuild=262`。`pluginUntilBuild` は追加しない（上限なし）。
- `README.md`：`Requirements` の「JetBrains Rider 2025.3+」→「2026.2+」。Rider 2026.1.x 以前のユーザーは v1.0.7 を使い続ける旨を明記。
- `CLAUDE.md` の Tech Stack「Target IDE Rider 2025.3.3」等を追随。
- CI（`build.yml`）：build/verify を 262 に対して回すため、`compatibility-verification.yml` のダウンロード方式を流用して 262-EAP を取得しローカルビルドする経路を追加（実装時に詳細設計）。

---

## 修正対象ファイル

| ファイル | 変更 |
|---------|------|
| `gradle.properties` | `pluginSinceBuild` 253→262、`rdGenVersion` 2025.3.1→2026.2.x、`platformVersion`/`platformType` 追随 |
| `build.gradle.kts` | `create("RD",…)`→`local(...)`、`riderModel`/`rd.jar` 掃除、testFramework 対応 |
| `protocol/build.gradle.kts` | rdgen 2026.x DSL 追随（`sources()`/`hashFolder` 廃止等） |
| `settings.gradle.kts` | rdgen plugin バージョン解決（`rdGenVersion` 経由）の追随確認 |
| `src/main/kotlin/.../UnityTestMcpModelProvider.kt` | `mix`/`identify` を新 API へ（or 生成 `create()` へ置換） |
| `src/main/kotlin/.../UnityCompilationMcpModelProvider.kt` | 同上 |
| `src/main/generated/*.Generated.kt` | rdgen 再生成（gitignore・成果物） |
| `src/dotnet/McpExtensionUnity/Model/*.Generated.cs` | rdgen 再生成 |
| `src/dotnet/McpExtensionUnity/*.cs`（Provider/Handler） | 262 C# Rd API 追随（compileDotNet で確認） |
| `src/dotnet/.../McpExtensionUnity.csproj` | TargetFramework（net472→必要なら net8.0）確認 |
| `.github/workflows/build.yml` | 262 に対する build/verify 経路 |
| `README.md` / `CLAUDE.md` | 対応バージョン表記 |

---

## Test Design

本変更の中心は **ビルド設定・SDK 移行・生成コード・Rd バインド**で、単体テスト適性が低い（Provider の `mix`/`identify` はライブ `IProtocol`/`IIdentities` と実バインドを要し、テストダブルで検証不能）。したがって：

- **既存 Kotlin 単体テストは全て pass を維持**（`RunUnityTestsToolTest` / `CompilationResultToolTest` / `PlayControlToolTest` / `RunMethodInUnityToolTest` / `UnityConsoleLogCollectorTest` / `EditorConnectionUtilsTest`）。SDK バンプでコンパイル/実行が壊れないことの回帰。
- **新規単体テストは追加しない**（対象が生成コード/ビルド設定/Rd バインドで、コードで検証可能な新仕様が無い）。API 正しさは **コンパイラ**（`buildPlugin` / `compileDotNet`）と **`verifyPlugin`** が事実上のテスト。
- 検証の主体は **`verifyPlugin`（262 で 0 問題）** と **E2E**。

### E2E Tests

`docs/e2e-tests.md` の各ツール節に沿って、下記を回帰項目として追加（Rd ブリッジは JVM verifier では検証不能なため必須）。対象は **新ライン（since=262）のみ**。261.x 系は v1.0.7 が既に対応済みのため対象外。

| # | Item | Verification Method |
|---|------|---------------------|
| 1 | Rider **262** にプラグイン導入後 `run_unity_tests` が Unity で実テスト実行し結果返却 | 262 にインストール→MCP ツール実行→結果確認 |
| 2 | Rider **262** で `get_unity_compilation_result` が成功 | 同上 |
| 3 | Rider **262** で `run_method_in_unity` / `unity_play_control` が動作 | 同上 |
| 4 | `verifyPlugin` を 262 ローカル SDK に対し実行し **0 問題** | `./gradlew verifyPlugin -PverifyIdePaths=<262>` |

---

## Development Workflow

> 本タスクは test-first の新規プロダクトコードが乏しい移行作業のため、標準の Skeleton→TestFirst→… ではなく移行フローで進める。各ステップ後に該当コミット。

### Step 1: SDK / rdgen バンプ（コンパイル可能化）
`gradle.properties` / `build.gradle.kts` / `protocol/build.gradle.kts` を 262 + rdgen 2026.x へ。`riderModel`/`rd.jar` 掃除。`:protocol:rdgen` を回して Kotlin+C# を再生成。この時点では Provider 未修正でコンパイル失敗し得る（生成 `create()` 有無をここで確認）。

### Step 2: Provider / C# 修正
- Kotlin Provider を新 API（or 生成ファクトリ）へ。
- `compileDotNet` を 262 で通す（C# Provider/Handler/csproj TargetFramework 修正）。
- `buildPlugin` 成功、既存 Kotlin テスト全 pass を確認。コミット。

### Step 3: Refactoring
1. 変更ファイルごとに `mcp__jetbrains__open_file_in_editor` → `mcp__ide__getDiagnostics` で warning 以上を解消（1 ファイルずつ）
2. テスト全 pass 確認
3. Claude Code 組込 `/simplify`（`Skill({skill: "simplify"})`）で品質改善
4. テスト全 pass 確認
5. 残変更をコミット

### Step 4: 互換検証
`verifyPlugin` を 262 ローカル SDK に対し実行し **0 問題** を確認。CI（`build.yml`）に 262 build/verify 経路を追加。

### Step 5: E2E Tests
上表の E2E 項目を `docs/e2e-tests.md` の該当ツール節に追記（既存フォーマット/採番に従う）。Rider 導入を伴う破壊的操作を含むものは `docs/plans/{plan-file-name}-e2e-tests.md` に分離。

### Step 6: Finalize
1. 本プランを `docs/plans/2026-07-05-rd262-compatibility.md` にコピー
2. コピー先末尾に `## Implementation Notes` を追記：
   - Design decisions（v1.0.7 凍結 + since=262 新ラインという 2 ビルド方針の根拠、`create()` ファクトリ有無、testFramework/csproj TargetFramework の判断）
   - Deviations（仕様から意図的に外した点と理由）
   - Tradeoffs（検討した代替案と選定理由：例 local build vs repository、reflection 継続 vs 生成ファクトリ、単一バイナリでの新旧デュアル対応を採用しなかった理由）
   - Test changes（実装中に変更した既存テスト）
   - Open questions
3. `CHANGELOG.md` の `## [Unreleased]` にエントリ追記（例：`### Changed` に「Rider 2026.2 (build 262) 以降のみサポート。2026.1.x 以前は v1.0.7 を継続利用のこと」。`.claude/`・`.github/`・`/src/test/` 配下のみの変更は対象外）

---

## リスク / 未確定（実装時に解消）
- **rdgen 2026.x の maven 可用性**と DSL 変更点 — Step 1 で確定。
- **rdgen が Kotlin `create()` を生成するか** — Step 1–2 で確定（Provider 修正量が変わる）。
- **testFramework の EAP 解決** — Step 1 で確定（test タスク維持策）。
- **C# csproj TargetFramework（net472 → net8.0?）** — Step 2 で確定。
- **CI ビルドを 262 ローカル SDK へ切替える配線** — Step 4 で確定。

---

## Implementation Notes

### Design decisions

- **2-build strategy confirmed and executed as planned**: v1.0.7 (compiled against build 253) stays frozen for Rider 261.x users; this migration switches the ongoing line to `pluginSinceBuild=262` with no `pluginUntilBuild` upper bound. Rationale (re-confirmed during implementation): the C# side of the Rd model is fully generated code with Identify/Bind baked into the constructor by the `reversed` rdgen generator — a single `McpExtensionUnity.dll` can only speak one Rd API generation, so dual-version support in one binary was never viable.
- **rdgen 2026.2.5 confirmed available on Maven Central** and resolved without needing the SDK-bundled `rd-gen.jar` fallback the plan anticipated.
- **rdgen 2026.2.5 still does not generate a public Kotlin factory** (`create(lifetime, protocol)`) for Rd Ext models — same private-constructor-only shape as rdgen 2025.3.1. Confirmed by reading the regenerated `.Generated.kt` files. This meant the "generates `create()`" branch of Step 4 in the Approach section was not taken; the reflection-based hand-written Provider pattern (`UnityTestMcpModelProvider.kt` / `UnityCompilationMcpModelProvider.kt`) had to continue, updated only at the two call sites that changed (`mix`, `identify`).
- **New API call shape** (confirmed against the regenerated C# constructor as ground truth, per the plan's own instruction not to guess the boolean argument):
  ```kotlin
  model.identify(proto.identity, proto.identity.mix(RdId.Null, "UnityTestMcpModel"), true)
  ```
  matching the C# generated constructor:
  ```csharp
  Identify(protocol.Identities, protocol.Identities.Mix(RdId.Root, "UnityTestMcpModel"), true);
  ```
- **`testFramework(TestFrameworkType.Platform)`** required no change — it resolves independently of the EAP-channel platform artifact.
- **`.csproj` `TargetFramework=net472`** required no change — compiled cleanly against the 262 SDK's bundled reference assemblies.
- **IntelliJ Platform Gradle Plugin bumped 2.11.0 → 2.17.0** (not originally listed as a required change) — necessary because 2.11.0's module-descriptor XML deserializer (`nl.adaptivity.xmlutil.serialization`) cannot parse Rider 262's newer `module`/`namespace` module-descriptor attributes (`UnknownXmlFieldException`). Found the version already cached locally, so no network resolution issue.
- **`bundledModule("intellij.rider.rdclient.dotnet")` added** (not anticipated by the plan) — Rider 2026.2 split `Project.solution` (`SolutionHostExtensionsKt.getSolution`), previously always available via the core `"RD"` platform target, out into this separately-declarable module. Discovered by diffing `product-backend.jar` contents between 253 and 262, then confirming the call site inside Rider 262's own bundled `rider-unity` plugin (`UnityProfilerMcpToolset.class`) referenced the same relocated class.
- **`riderModel` Gradle configuration and its `lib/rd.jar` artifact wiring were dead code** (zero consumers repo-wide, confirmed via Explore before removal) and were deleted rather than repointed at 262's reshuffled jar layout.
- **rd-gen 2026.2.5 dropped its own `sources()`/`hashFolder`/`force` change-hash properties** (confirmed via javap diff of `RdGenExtension`). Since `RdGenTask` is a bare `JavaExec` with no Gradle-native `@InputFiles`/`@OutputDirectory`, this silently made every build force-regenerate the Rd model. Compensated by declaring `inputs.dir(...)`/`outputs.dirs(...)` on `tasks.withType<RdGenTask>()` in `protocol/build.gradle.kts`, restoring Gradle's own up-to-date skip (verified: second consecutive `:protocol:rdgen` run reports `UP-TO-DATE`).
- **First attempt (later reverted): manual local SDK download.** Initially, `create("RD", "2025.3.3")` was replaced with a `local(File(localRiderPath))` / `create(platformType, platformVersion)` branch, gated by a new `-PlocalRiderPath` Gradle property, based on the (incorrect) assumption that EAP-channel Rider artifacts cannot be resolved from a Maven repository at all. `build.yml` grew a `resolve` job (querying the same JetBrains releases feed `compatibility-verification.yml` uses) plus per-job download+extract steps, and `compatibility-verification.yml`'s `verify` job gained a matching `-PlocalRiderPath` flag. This was fully implemented, committed, and verified working (`buildPlugin`/`verifyPlugin` succeeded via a manually downloaded 262-EAP9 SDK).
- **Root cause correction — the local-download workaround was unnecessary.** Investigating whether to wait for upstream issues [intellij-platform-gradle-plugin#1852](https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/1852) or [#2174](https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/2174) to resolve, a live experiment (`create("RD", "2026.2-EAP9-SNAPSHOT") { useInstaller = false }`, no `-PlocalRiderPath`) surfaced the actual, more mundane root cause: the plain `create("RD", "2026.2")` call implicitly defaults to `useInstaller = true`, which the plugin explicitly does not support for Rider (`#1852`'s exact warning appeared in the full — not grep-filtered — build log, previously missed); **and separately**, `"2026.2"` is not a version string that exists in JetBrains' Maven repository at all — published EAP coordinates use a `-SNAPSHOT`-suffixed string (`2026.2-EAP9-SNAPSHOT` for build 262.8665.68). Setting `useInstaller = false` **and** using the exact snapshot coordinate together resolved cleanly via ordinary Maven dependency resolution: `BUILD SUCCESSFUL` for `compileKotlin`, `buildPlugin`, and `verifyPlugin` (`Compatible` against `RD-262.8665.68`, no `-PverifyIdePaths` needed either — `pluginVerification.ides`'s existing fallback to `intellijPlatform.platformPath` picked up the same Maven-resolved SDK automatically). Neither #1852 nor #2174 was actually blocking this project — both are narrower/different bugs; our own `create()` call was simply missing `useInstaller = false` and using a non-existent version string.
- **Bonus discovery: `test` now runs locally on macOS.** The Maven `riderRD` artifact bundles JBRs for all supported host platforms (unlike the single-OS installer archive the local-download workaround used, which is Linux-only and can't execute on macOS). Running `./gradlew test` locally on Apple Silicon macOS succeeded outright (79 tests, 0 failures) — the previously-documented "macOS + Linux SDK download" limitation no longer applies.
- **Reverted the entire local-download workaround** via `git revert` of its standalone commit (kept isolated specifically so it could be cleanly reverted — see the original plan's Deviations/Tradeoffs discussion below, which describes the workaround as implemented before this correction). `build.gradle.kts`, `CLAUDE.md`, `build.yml`, and `compatibility-verification.yml` are now back to their pre-workaround simple form (no `-PlocalRiderPath`, no CI `resolve`/download jobs) plus the two-line `useInstaller = false` fix and `platformVersion=2026.2-EAP9-SNAPSHOT` in `gradle.properties`.
- **`platformVersion` is now an EAP snapshot string that will need manual bumps** as JetBrains publishes new EAP builds (and eventually a stable release) — this is treated as ordinary version-bump maintenance, the same as the previous `2025.3.3` pin, not something requiring automation.
- **CI wiring (`build.yml`, `compatibility-verification.yml`) needed no changes at all** once the compile-time dependency resolves via plain Maven — both workflows are back to the exact form they had before this migration (`compatibility-verification.yml`'s pre-existing `-PverifyIdePaths` pattern for its release+EAP matrix is unaffected and unrelated to this fix).

### Deviations

- **Dropped the planned 4-item E2E table in `docs/e2e-tests.md` in favor of a single note.** The existing E2E suite already exhaustively covers all 4 tools' observable behavior (sections 1–4), and this migration changes zero observable tool behavior — only the internal Rd transport. Adding 4 new numbered test cases that just re-run existing assertions "on Rider 262" would have been duplicative. Instead, added one `[!NOTE]` block at the top of `docs/e2e-tests.md` directing that the full suite be run once against the target Rider version after any change to `pluginSinceBuild`, the rdgen version, or the hand-written model providers — since the Rd binding layer they exercise is invisible to both unit tests and `verifyPlugin` (bytecode-only).
  - The plan's 4th E2E item, "`verifyPlugin` reports 0 problems against the 262 SDK," is a one-time build-verification gate already performed during this implementation (Step 4), not a recurring regression test a human runs each cycle — so it also was not added to `docs/e2e-tests.md`.
- **A local-SDK-download CI mechanism was built, verified, and then fully reverted within this same implementation pass** once the actual root cause (`useInstaller` default + wrong version string, not a genuinely broken upstream artifact resolution) was found. See Design decisions above.

### Tradeoffs

- **Continued the reflection-based Kotlin Provider hack** rather than any cleaner alternative, since rdgen 2026.2.5 confirmed no public factory is generated — there was no cleaner option available within this rdgen version.
- **Local SDK download for CI (rejected in favor of plain Maven resolution).** The local-download approach (`curl` + `tar` extraction, matching `compatibility-verification.yml`'s existing pattern for its verification matrix) was initially implemented as a hedge against EAP-channel Maven resolution being broken. Once `useInstaller = false` + the correct snapshot coordinate proved plain Maven resolution works fine, the local-download approach was rejected: it pins to a single-OS artifact (breaking local `test` execution on non-matching hosts), needs a `resolve`+download step in every CI job, and carries no advantage over letting Gradle's own dependency resolution handle it.
- **Not pinning to a specific patch/timestamp of the `-SNAPSHOT` coordinate.** Maven `-SNAPSHOT` versions can be updated in place upstream; this project accepts standard Gradle snapshot-resolution semantics (checked against remote timestamps per Gradle's cache policy) rather than pinning to a fully-qualified timestamped build, consistent with not over-engineering a moving EAP target.

### Test changes

None — no existing test files were modified. Only production Kotlin/Gradle/CI/doc files changed.

### Open questions

- When Rider 2026.2 reaches a stable/GA release, `platformVersion` should be bumped from the EAP snapshot string to the stable release's plain version string (and `useInstaller = false` can likely stay, or be revisited — stable Rider releases were not tested with `useInstaller = true` in this session either).
- Live E2E tests (`run_unity_tests` / `get_unity_compilation_result` / `run_method_in_unity` / `unity_play_control` against a real Unity Editor + Rider 2026.2 session) have not yet been executed in this session — recommended before tagging a release.
