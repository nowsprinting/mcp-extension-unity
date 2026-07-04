# Plan: `compatibility-verification.yml` の追加

## Context

`build.yml` には現在 `verify` ジョブがあり、`./gradlew verifyPlugin` を実行しているが、対象は
**ローカルにダウンロード済みの Rider ビルドのみ**（`build.gradle.kts` → `pluginVerification { ides { local(...) } }`、
すなわち Rider 2025.3.3）。これによりコンパイル対象と同一の IDE でプラグインがロードできることは確認できるが、
JetBrains Marketplace が実際に検証対象とする IDE バージョン——`pluginSinceBuild..pluginUntilBuild`
（253 .. 261.\*）の範囲内での最新安定版リリースと現行 EAP——との互換性は確認できていない。
これらの対象バージョンは時間とともに変化する（EAPが更新される）。

**目的**: `.github/workflows/compatibility-verification.yml` を追加し、`build.yml` の `verify` ジョブが行う
プラグイン検証処理のみを、IntelliJ Platform Gradle Plugin の `recommended()` が再現する
**Marketplace選定のIDEバージョン**（since/until範囲内の最新RELEASE + 最新EAP）に対して実行する。

**トリガー**: 手動のみ（`workflow_dispatch`）。

**注記（ユーザー判断による簡略化）**: `recommended()` は該当する全IDEを1回の `verifyPlugin` 呼び出し内で
まとめて検証する。1ステップで2バージョンを実行することは許容されたため、resolve-then-fan-out方式の
matrixは不要——`build.yml` の `verify` を踏襲しつつ検証対象IDEの選定を `recommended()` に切り替えるだけの
単一ジョブで十分。

## Design

### 1. `build.gradle.kts` — プロパティによる検証対象IDEの切り替え

`build.yml` は引き続きローカルの Rider ビルドを使う必要がある（挙動変更なし）。`recommended()` を使うのは
新しいワークフローのみ。`pluginVerification.ides` にCLIからの上書き手段は無いため、小さなGradleプロパティの
分岐を追加する（現状は無条件の `local(...)`、109〜114行目付近）:

```kotlin
pluginVerification {
    ides {
        // compatibility-verification.yml passes -PverifyRecommended to check against the IDE builds
        // JetBrains Marketplace verifies against (latest RELEASE + latest EAP within
        // pluginSinceBuild..pluginUntilBuild). Without the property (build.yml's verify job and local
        // dev), fall back to the already-downloaded Rider build — existing behavior is unchanged.
        if (providers.gradleProperty("verifyRecommended").isPresent) {
            recommended()
        } else {
            local(intellijPlatform.platformPath.toFile())
        }
    }
}
```

- `recommended()` は `ProductReleasesValueSource` 経由で該当リリースを解決し（プロダクトコード `RD`、
  `pluginConfiguration.ideaVersion` 由来の `sinceBuild`/`untilBuild`、デフォルトチャンネル）、
  既に設定済みの `defaultRepositories()` からダウンロードする。
- プロパティ未指定 → `local(...)` → `build.yml` およびローカル開発への影響なし。

### 2. `.github/workflows/compatibility-verification.yml`

`build.yml` の `verify` ジョブ（checkout → fetch tags → Java 21 → .NET 8.0.x → Gradle → `verifyPlugin`）を
踏襲した単一の `verify` ジョブ。差分は `workflow_dispatch` トリガーと `-PverifyRecommended` フラグのみ。

`build.yml` と同じピン留め済みaction SHA、`JAVA_HOME=$JAVA_HOME_21_X64`、`--no-configuration-cache` の
規約、および `permissions: {}`、`concurrency`、`defaults.run.shell: bash` を踏襲する。

```yaml
name: Compatibility Verification

on:
  workflow_dispatch:

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

permissions: { }

defaults:
  run:
    shell: bash

jobs:
  verify:
    name: Verify (Marketplace recommended IDEs)
    runs-on: ubuntu-latest
    steps:
      - name: Fetch Sources
        uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd # v6.0.2
      - name: Fetch tags
        run: git fetch --tags --deepen=100
      - name: Set up Java
        uses: actions/setup-java@be666c2fcd27ec809703dec50e508c2fdc7f6654 # v5.2.0
        with:
          distribution: zulu
          java-version: 21
      - name: Set up .NET
        uses: actions/setup-dotnet@c2fa09f4bde5ebb9d1777cf28262a3eb3db3ced7 # v5.2.0
        with:
          dotnet-version: '8.0.x'
      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@50e97c2cd7a37755bbfafc9c5b7cafaece252f6e # v6.1.0

      - name: Run Plugin Verification
        run: JAVA_HOME=$JAVA_HOME_21_X64 ./gradlew --no-configuration-cache verifyPlugin -PverifyRecommended

      - name: Upload Verification Results
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        if: always()
        with:
          name: verification-results
          path: build/reports/pluginVerifier/
```

`.NET` は `verifyPlugin` → `prepareSandbox` → `compileDotNet` の依存チェーンのため必須
（`build.yml` の `verify` と同様）。Plugin Verifier のIDEダウンロードキャッシュ（JetBrainsテンプレートが
行っているような、products-releasesリストをキーにしたもの）は、初版はシンプルさを優先し省略する。

## Files to modify / add

| File | Change |
|------|--------|
| `build.gradle.kts`（109〜114行目付近） | `pluginVerification.ides`: `-PverifyRecommended` があれば `recommended()`、無ければ従来の `local(...)` を維持 |
| `.github/workflows/compatibility-verification.yml` | **新規** — `recommended()` を使う単一の手動 `verify` ジョブ |

## Tests

この変更にはユニットテスト可能な本番コードが**存在しない**——GitHub Actionsワークフローと
Gradle設定の分岐のみ。実装計画ガイドに従い、テストコードで検証できないケースはE2E項目として記載する。

### Test Cases

なし（Kotlin/.NETの本番コード追加なし）。

### E2E Tests

| # | Item | Verification Method |
|---|------|---------------------|
| 1 | `recommended()` がMarketplaceのIDEを解決・ダウンロードし、検証が実行される | ローカルで `./gradlew --no-configuration-cache verifyPlugin -PverifyRecommended` を実行し、最新RELEASE + EAPのRiderビルドがダウンロードされ `build/reports/pluginVerifier/` にレポートが生成されることを確認 |
| 2 | `build.yml` の `verify` ジョブが無変更であること | プロパティ**無し**で `./gradlew --no-configuration-cache verifyPlugin` を実行し、従来通りローカルのRiderビルドを使い成功することを確認 |
| 3 | E2Eワークフロー実行 | ブランチをpushし `workflow_dispatch`（Actionsタブ / `gh workflow run`）でトリガー。recommendedなIDEに対して検証が行われ、`verification-results` アーティファクトがアップロードされ、ランナーのディスク容量が十分であることを確認 |

## Development Workflow

### Step 1: `build.gradle.kts` のフック追加 + ローカル検証

1. `pluginVerification.ides` のプロパティ分岐を適用する（§Design 1）。
2. E2E #1 をローカルで実行（`verifyPlugin -PverifyRecommended`）——`recommended()` がrelease + EAPの
   ビルドをダウンロードし検証が完了することを確認する。選定されたバージョンを記録する（Implementation Notes
   に記載し、「最新release + 次期EAP」という想定と一致するか確認するため）。
3. E2E #2 を実行——プロパティ無しの場合に引き続き `local` が使われることを確認する。
4. `build.gradle.kts` をコミットする。

### Step 2: ワークフローの追加

1. §Design 2 に従い `.github/workflows/compatibility-verification.yml` を作成する。他のaction SHAは
   すべて `build.yml` からコピーする。
2. YAMLを検証する（`gh workflow view`、あるいはローカルの `actionlint` があればそれを使う）。
3. ワークフローをコミットする。

### Step 3: E2E検証

1. ブランチをpushし、E2E #3（`gh workflow run compatibility-verification.yml --ref <branch>`）を実行する。
   Actions実行結果を確認する：recommendedなIDEに対して検証、アーティファクトのアップロード、ディスク容量。

### Step 4: Finalize

1. このプランファイルを `docs/plans/2026-07-05-compatibility-verification-workflow.md` にコピーする
2. コピーしたプランファイルの末尾に `## Implementation Notes` を追記し、以下を記載する:
   - Design decisions: 仕様が曖昧だった箇所で行った選択
   - Deviations: 意図的に仕様から逸脱した箇所とその理由
   - Tradeoffs: 検討した代替案とそれを採用しなかった理由
   - Test changes: N/A（テストコードなし）
   - Open questions: 確認・修正してほしい点
3. `CHANGELOG.md` の `## [Unreleased]` にエントリを追記する。
   - `.github/` のワークフローファイルはCHANGELOG対象外だが、`build.gradle.kts` の変更は対象外**ではない**
     ——新しい `verifyRecommended` 検証対象フックについて簡潔な1行を追加する。

## Implementation Notes

- Design decisions:
  - resolve-then-fan-out方式のmatrixは採用せず、単一ジョブで `recommended()` を使う設計を維持した。
    1ステップで2バージョンを実行することは許容する、というユーザーの明示的な判断による。
  - トリガーは `workflow_dispatch` のみとした（ユーザーの明示的な判断）。`schedule` やpush/PRは追加しない
    ——各実行でRiderビルド一式をダウンロードするため、PR毎の実行はコストが高すぎる。
  - プロパティ名: `verifyRecommended`（存在チェックのみのフラグ。値は無視される）。
  - ローカル実行では `recommended()` が **RD-253.32098.97**（253系の最新リリース）と
    **RD-261.26222.60**（`untilBuild` の `261.*` 範囲内での最新EAP）を選定した——
    「最新release + 次期EAP」という想定、および `recommended()` が `pluginSinceBuild`/`pluginUntilBuild`
    からMarketplaceの検証対象を正しく再現していることを確認できた。
- Deviations（当初の計画からの変更点）:
  - **`jlumbroso/free-disk-space` ステップを削除した。** ローカルでの `-PverifyRecommended` 実行では
    追加ダウンロードは約289MBのみ（verifier全体のディスク使用量は1.12GB）——GitHub-hosted
    `ubuntu-latest` ランナーのデフォルトの空き容量（約14GB）に対して十分な余裕がある。このステップを
    追加しても実測上のメリットが無く、サードパーティaction依存が増えるだけのため、最終的なワークフロー
    からは省略した。
  - **CHANGELOGへのエントリ追加を行わなかった。** プランテンプレートの既定手順ではCHANGELOG追記対象
    だったが（`.github/`除外規定は`build.gradle.kts`には適用されない）、ユーザーから明示的に
    「CHANGELOGには追加しない」との指示があったため、追加しなかった。
- Tradeoffs: プラン本文に記載の内容以外に特になし（resolve-then-fan-out matrix案は、ユーザーが
  「1ステップ2バージョンを許容する」と明示的に判断したことで不要になった）。
- Test changes: N/A（テストコードなし。CIワークフロー + Gradle設定のみの変更）。
- Open questions / follow-ups:
  - E2E #3（`gh workflow run compatibility-verification.yml --ref master`）はPR #31マージ後に実行した
    ——実際の結果はPR/コミット履歴からリンクされている実行を参照。
  - JetBrainsテンプレートのようなPlugin Verifier IDEダウンロードのキャッシュは、初版のシンプルさを
    優先し追加していない。実行時間・コストが問題になれば見直す。
