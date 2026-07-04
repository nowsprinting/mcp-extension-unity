# Plan: `compatibility-verification.yml` の追加

## Context

`build.yml` には現在 `verify` ジョブがあり、`./gradlew verifyPlugin` を実行しているが、対象は
**ローカルにダウンロード済みの Rider ビルドのみ**（`build.gradle.kts` → `pluginVerification { ides { local(...) } }`、
すなわち Rider 2025.3.3）。これによりコンパイル対象と同一の IDE でプラグインがロードできることは確認できるが、
JetBrains Marketplace が実際に検証対象とする IDE バージョン——`pluginSinceBuild..pluginUntilBuild`
の範囲内での最新安定版リリースと現行 EAP——との互換性は確認できていない。
これらの対象バージョンは時間とともに変化する（EAPが更新される）。

過去に一度、Marketplace提出後の互換性チェックで次期バージョン（Rider 2026.2）との非互換が発覚し、
該当バージョンを廃版にした経緯がある。**提出前にローカル/CIで互換性チェックを完了させたい**、というのが
本質的なゴール。

**目的**: `.github/workflows/compatibility-verification.yml` を追加し、`build.yml` の `verify` ジョブが行う
プラグイン検証処理のみを、**Marketplace選定のIDEバージョン**（since/until範囲内の最新RELEASE + 最新EAP）
に対して実行する。

**トリガー**: 手動のみ（`workflow_dispatch`）。

## 設計変更の経緯（重要）

当初は IntelliJ Platform Gradle Plugin の `recommended()` でMarketplace選定を再現する設計だった
（1ステップで2バージョン実行することはユーザー承認済み）。しかし実装・検証の過程で、
**`recommended()`（および `ide()`/`create()`）によるRiderのIDE解決が、EAPチャンネルのビルドに対して
根本的に壊れている**ことが判明した:

1. `recommended()` はRiderに対して内部的に `useInstaller=true` を使うが、これは
   [JetBrains/intellij-platform-gradle-plugin#1852](https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/1852)
   で「Riderでは現状未対応」と公式に認められている。EAPチャンネルのインストーラーファイル名規則
   （例: `JetBrains.Rider-2026.2-EAP9-262.8665.68.Checked.tar.gz`——マーケティングバージョンと
   `.Checked` サフィックスを含む）が、プラグインの解決ロジックが想定するファイル名パターン
   （`JetBrains.Rider-{buildNumber}.tar.gz`）と一致せず、404で解決失敗する。
   RELEASEチャンネル（253.32098.97、261.26222.60等）はファイル名が単純なため偶然動いていただけ。
2. 代替の `useInstaller=false`（Maven経由解決、座標 `com.jetbrains.intellij.rider:riderRD`）も試したが、
   **既知の動作確認済みRELEASEビルドですら404**——この座標自体がRiderには存在しない。
3. 一方 `pluginVerification.ides.local(directory)`（既にダウンロード・展開済みのIDEディレクトリを
   直接指定する方式）は確実に動作することを、手動ダウンロードしたRider 2026.2 EAP9（build 262.8665.68）
   で実証済み。

このため、**Gradle Pluginの壊れたRider依存解決を経由せず、CI側で直接ダウンロード→展開→`local()`で
指定する方式**に設計変更した。副産物として、GitHub Actionsランナーは（ローカルのBash sandboxと違い）
無制限のネットワークアクセスを持つため、このアプローチが素直に実装できる。

## Design

### 1. `build.gradle.kts` — ローカルIDEパスによる検証対象の指定

```kotlin
pluginVerification {
    ides {
        // compatibility-verification.yml passes -PverifyIdePaths (comma-separated local IDE home
        // directories, manually downloaded+extracted) to check against the IDE builds JetBrains
        // Marketplace verifies against (latest RELEASE + latest EAP within pluginSinceBuild..
        // pluginUntilBuild). This bypasses the Gradle plugin's Rider dependency resolution, which
        // is broken for EAP-channel artifacts: https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/1852
        // Without the property (build.yml's verify job and local dev), fall back to the
        // already-downloaded Rider build — existing behavior is unchanged.
        val verifyIdePaths = providers.gradleProperty("verifyIdePaths")
        if (verifyIdePaths.isPresent) {
            verifyIdePaths.get().split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { path ->
                local(File(path))
            }
        } else {
            local(intellijPlatform.platformPath.toFile())
        }
    }
}
```

- `verifyIdePaths` はカンマ区切りのローカルディレクトリパス（0個以上のIDEホームディレクトリ）。
- プロパティ未指定 → 既存の `local(...)` → `build.yml` およびローカル開発への影響なし。

### 2. `.github/workflows/compatibility-verification.yml` — resolve → matrix fan-out

ユーザー要望により、対象バージョンの**解決（resolve）と検証（verify）をジョブ分割**し、
verifyはmatrixで並列実行する。

**`resolve` ジョブ**:
- `gradle.properties` から `pluginSinceBuild`/`pluginUntilBuild` を読み取る
- JetBrains公式リリースフィード（`https://data.services.jetbrains.com/products?code=RD&fields=code,releases`——
  Marketplaceの互換性検証が参照するのと同じ公式ソース）を `curl` + `jq` で取得
- 範囲内の最新 `release` と最新 `eap` を抽出し、それぞれの `build` 番号とLinux向けダウンロードURLを
  JSON配列としてジョブ出力（`matrix`）に格納
- 該当バージョンが0件ならエラーで明示的に失敗

**`verify` ジョブ**（`needs: resolve`、`matrix.ide` でfan-out、`fail-fast: false`）:
- `matrix.ide.url` から該当Riderビルドを `curl` でダウンロード → `tar -xzf --strip-components=1` で展開
- `./gradlew verifyPlugin -PverifyIdePaths=<展開先パス>` を実行（1セル1バージョン）
- 結果をセルごとに個別アーティファクト名でアップロード

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
  resolve:
    name: Resolve target Rider builds
    runs-on: ubuntu-latest
    outputs:
      matrix: ${{ steps.resolve.outputs.matrix }}
    steps:
      - name: Fetch Sources
        uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd # v6.0.2

      - name: Resolve latest release + EAP Rider builds in range
        id: resolve
        run: |
          SINCE_BUILD="$(grep '^pluginSinceBuild=' gradle.properties | cut -d= -f2)" || true
          UNTIL_BUILD_RAW="$(grep '^pluginUntilBuild=' gradle.properties | cut -d= -f2)" || true
          UNTIL_BUILD_MAJOR="${UNTIL_BUILD_RAW%.\*}"
          echo "Range: sinceBuild=$SINCE_BUILD untilBuild=${UNTIL_BUILD_RAW:-<none>}"

          RELEASES_JSON="$(curl -sSL "https://data.services.jetbrains.com/products?code=RD&fields=code,releases")"

          MATRIX="$(echo "$RELEASES_JSON" | jq -c --arg since "$SINCE_BUILD" --arg until "$UNTIL_BUILD_MAJOR" '
            def inRange:
              (.build | split(".")[0] | tonumber) as $major
              | $major >= ($since | tonumber)
                and ($until == "" or $major <= ($until | tonumber));
            [.[0].releases[] | select(inRange)] as $inRange
            | ($inRange | map(select(.type == "release")) | .[0]) as $release
            | ($inRange | map(select(.type == "eap")) | .[0]) as $eap
            | [$release, $eap]
            | map(select(. != null))
            | map({type: .type, build: .build, url: .downloads.linux.link})
          ')"

          COUNT="$(echo "$MATRIX" | jq 'length')"
          echo "Resolved targets ($COUNT):"
          echo "$MATRIX" | jq .
          if [ "$COUNT" -eq 0 ]; then
            echo "::error::No Rider releases found matching sinceBuild=$SINCE_BUILD untilBuild=${UNTIL_BUILD_RAW:-<none>}"
            exit 1
          fi

          echo "matrix=$MATRIX" >> "$GITHUB_OUTPUT"

  verify:
    name: Verify (${{ matrix.ide.type }} RD-${{ matrix.ide.build }})
    needs: resolve
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        ide: ${{ fromJson(needs.resolve.outputs.matrix) }}
    steps:
      - name: Fetch Sources
        uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd # v6.0.2

      - name: Fetch tags
        run: |
          git fetch --tags --deepen=100

      - name: Download and extract Rider ${{ matrix.ide.type }} RD-${{ matrix.ide.build }}
        id: download
        run: |
          IDE_DIR="$RUNNER_TEMP/rider-ide"
          mkdir -p "$IDE_DIR"
          curl -sSL -o "$RUNNER_TEMP/rider.tar.gz" "${{ matrix.ide.url }}"
          tar -xzf "$RUNNER_TEMP/rider.tar.gz" -C "$IDE_DIR" --strip-components=1
          rm "$RUNNER_TEMP/rider.tar.gz"
          echo "path=$IDE_DIR" >> "$GITHUB_OUTPUT"

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
        run: JAVA_HOME=$JAVA_HOME_21_X64 ./gradlew --no-configuration-cache verifyPlugin -PverifyIdePaths=${{ steps.download.outputs.path }}

      - name: Upload Verification Results
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        if: always()
        with:
          name: verification-results-${{ matrix.ide.type }}-${{ matrix.ide.build }}
          path: build/reports/pluginVerifier/
```

`.NET` は `verifyPlugin` → `prepareSandbox` → `compileDotNet` の依存チェーンのため必須
（`build.yml` の `verify` と同様）。Linux向けの `tar.gz` を使用（プラグイン検証はJARの静的解析のため
OSに依存しない。macOSの`.dmg`はマウントが必要で扱いにくいため回避）。

## Files to modify / add

| File | Change |
|------|--------|
| `build.gradle.kts`（109〜125行目付近） | `pluginVerification.ides`: `-PverifyIdePaths`（カンマ区切りローカルパス）があれば各パスを`local()`指定、無ければ従来の `local(intellijPlatform.platformPath.toFile())` を維持 |
| `.github/workflows/compatibility-verification.yml` | **新規** — `resolve` ジョブ（JetBrains公式フィードから対象バージョンを解決）+ `verify` ジョブ（matrix fan-out、ダウンロード→展開→検証） |

## Tests

この変更にはユニットテスト可能な本番コードが**存在しない**——GitHub Actionsワークフローと
Gradle設定の分岐のみ。実装計画ガイドに従い、テストコードで検証できないケースはE2E項目として記載する。

### Test Cases

なし（Kotlin/.NETの本番コード追加なし）。

### E2E Tests

| # | Item | Verification Method |
|---|------|---------------------|
| 1 | `verifyIdePaths` が指定ディレクトリを正しく検証対象にする | ローカルで `./gradlew --no-configuration-cache verifyPlugin -PverifyIdePaths=<手動DL済みRider 2026.2 EAP9パス>` を実行し、`build/reports/pluginVerifier/` にRD-262.8665.68向けレポートが生成されることを確認 |
| 2 | `build.yml` の `verify` ジョブが無変更であること | プロパティ**無し**で `./gradlew --no-configuration-cache verifyPlugin` を実行し、従来通りローカルのRiderビルドを使い成功することを確認 |
| 3 | `resolve` ジョブのバージョン解決ロジック（jqフィルタ） | ローカルで実際のJetBrains APIに対して同等のcurl/jqコマンドを実行し、`pluginUntilBuild=261.*` の場合に253〜261範囲の release+eap が、untilBuild未設定の場合に262系EAPまで含めて正しく解決されることを確認 |
| 4 | E2Eワークフロー実行（resolve→matrix verify） | ブランチをpushし `workflow_dispatch` でトリガー。resolveジョブが対象バージョンを解決し、verifyジョブがmatrixで並列実行され、各セルでダウンロード→検証→アーティファクトアップロードが完了することを確認 |

## Development Workflow

### Step 1: `build.gradle.kts` のフック追加 + ローカル検証

1. `pluginVerification.ides` の `verifyIdePaths` プロパティ分岐を適用する（§Design 1）。
2. E2E #1 をローカルで実行——手動ダウンロード済みのRider 2026.2 EAP9に対して検証が実行されることを確認する。
3. E2E #2 を実行——プロパティ無しの場合に引き続き `local` が使われることを確認する。
4. `build.gradle.kts` をコミットする。

### Step 2: ワークフローの追加

1. §Design 2 に従い `.github/workflows/compatibility-verification.yml` を作成する（resolve + matrix verify）。
2. E2E #3 でjqフィルタのロジックをローカル検証する（bounded / unbounded 両方のケース）。
3. YAMLを検証する（`actionlint`）。
4. ワークフローをコミットする。

### Step 3: E2E検証

1. ブランチをpushし、E2E #4（`gh workflow run compatibility-verification.yml --ref <branch>`）を実行する。
   Actions実行結果を確認する：resolveジョブの出力、verify matrixの各セル、アーティファクトのアップロード。

### Step 4: Finalize

1. このプランファイルを更新する（このファイル自体）
2. `## Implementation Notes` に設計変更の経緯と判断を記載する
3. CHANGELOGへのエントリ追加は行わない（ユーザー指示）

## Implementation Notes

### 設計変更の全体像

当初案（`recommended()` を使う単一ジョブ）はローカルで一度成功したが、その後
`pluginUntilBuild` を撤廃して2026.2のEAPも範囲に含めた際に依存解決が失敗し、詳細調査の結果
**Rider向けの `recommended()`/`ide()`/`create()` によるIDE解決がEAPチャンネルで根本的に壊れている**
（`useInstaller=true` はEAPのファイル名規則に非対応、`useInstaller=false` は `riderRD` というMaven座標が
存在せずRELEASEですら解決不可）ことが判明した。これを受けて、Gradle Pluginの依存解決を経由せず
CI側で直接ダウンロード→`local()`指定する方式に全面的に設計変更した。

### 副次的な発見（2026.2互換性そのものについて）

調査の過程で、手動ダウンロードしたRider 2026.2 EAP9（RD-262.8665.68）に対して実際に `verifyPlugin` を
実行し、**4件の実際の互換性問題**を確認できた——すべて `rdgen`（`rdGenVersion=2025.3.1`）が生成する
Rdモデルコードが、2026.2に同梱されたRdフレームワークの `RdId.mix(...)` / `identify(...)` API変更に
対応できていないことに起因する（Kotlin value classの名前マングリングサフィックスの相違から判明）。
これは過去にMarketplace提出後に発覚し廃版にした非互換性の正体と考えられる。

`rdGenVersion` を2026.2.5に上げれば新API向けにKotlin側は生成し直せることを確認したが、
**C#側生成コードが呼ぶ新API（`IIdentities.Mix`）がコンパイル対象のRider SDK（2025.3.3）のRd.NET
アセンブリに存在せず**、`compileDotNet` が失敗する。つまり2026.2本対応には rdgen だけでなく
コンパイル対象のRider SDKバージョン自体の引き上げ（および rd-gen 2026.x系のDSL変更——`sources()`/
`hashFolder` 廃止——への追従）が必要であり、本ワークフロー修正のスコープを超える別タスクとなる。
この2026.2本対応タスクは本ドキュメントの範囲外とし、別途計画する。

- Design decisions:
  - resolve/verifyの2ジョブ構成 + matrix fan-out を採用（ユーザー要望）。1ジョブに戻すよりも
    各バージョンの成否が独立して見えるメリットがある。
  - バージョン解決はGradle（`printProductsReleases`等）を使わず、JetBrains公式の
    `data.services.jetbrains.com` APIを直接 `curl`+`jq` で叩く方式にした——Marketplaceの検証が
    参照するのと同じ一次ソースであり、かつダウンロードURLも同時に取得できるため。
  - ダウンロードはLinux向け`tar.gz`固定（プラグイン検証はJARの静的解析でOS非依存、macOSの`.dmg`は
    マウントの手間があるため回避）。
  - `verifyIdePaths` プロパティ名はカンマ区切りで複数パスを受け付ける汎用設計にした
    （ローカル開発で複数バージョンを同時に指定したい場合にも対応できる）。
- Deviations（当初の計画からの変更点）:
  - 当初の `verifyRecommended` + `recommended()` 設計は破棄した（上記の理由により）。
  - `jlumbroso/free-disk-space` ステップは引き続き不要と判断（`local()`方式でも実測ディスク使用量は
    小さく、GitHub-hostedランナーのデフォルト空き容量で十分）。
  - CHANGELOGへのエントリ追加は行わない（ユーザーから明示的な指示）。
- Tradeoffs:
  - Gradle経由でのIDE依存解決（キャッシュや設定キャッシュとの統合）を諦め、生のcurl/tarによる
    手動ダウンロードに切り替えた。Gradleのビルドキャッシュの恩恵は受けられなくなるが、
    唯一動作する経路であるため許容。
- Test changes: N/A（テストコードなし。CIワークフロー + Gradle設定のみの変更）。
- Open questions / follow-ups:
  - 2026.2本対応（rdgen 2026.x系 + コンパイル対象Rider SDK引き上げ + rd-gen新DSL追従）は別タスクとして
    計画する必要がある。
  - Plugin Verifier IDEダウンロードのキャッシュ（actions/cacheでtar.gzやIDEディレクトリをキャッシュする等）
    は、初版のシンプルさを優先し追加していない。実行時間・コストが問題になれば見直す。
