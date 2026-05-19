# Rider 対応バージョン上限を 2026.1 に制限

## Context

Rider 2026.2 では plugin API に破壊的変更が入り、本プラグインは互換性を失う見込み。
現在の `plugin.xml` (生成物) には `until-build` が未設定で、未来の Rider バージョンに対しても
「対応プラグイン」として表示されてしまう。

そこで `untilBuild = 261.*` を設定し、Rider 2026.1.x までを対応範囲とする。
2026.2 (build 262) 以降では JetBrains Marketplace / IDE のプラグイン互換性チェックに
よりインストール不可になり、ユーザーの誤インストールを防げる。

## JetBrains ビルド番号対応表

| Rider バージョン | build 番号 |
|------------------|-----------|
| 2025.3 (現 `sinceBuild`) | 253 |
| 2026.1 (新 `untilBuild`) | 261 |
| 2026.2 (非対応)          | 262 |

`untilBuild = "261.*"` は 261 系の全パッチ (2026.1.x) を含み、262 (2026.2.0) を除外する。

## 変更対象ファイル

### 1. `gradle.properties`

`pluginSinceBuild=253` の直後に上限プロパティを追加する。

```
pluginSinceBuild=253
pluginUntilBuild=261.*
```

### 2. `build.gradle.kts`

`intellijPlatform.pluginConfiguration.ideaVersion` ブロック (現在 line 77-79) に
`untilBuild` を追加する。

変更前:
```kotlin
ideaVersion {
    sinceBuild = providers.gradleProperty("pluginSinceBuild")
}
```

変更後:
```kotlin
ideaVersion {
    sinceBuild = providers.gradleProperty("pluginSinceBuild")
    untilBuild = providers.gradleProperty("pluginUntilBuild")
}
```

### 3. `CHANGELOG.md`

`[Unreleased]` セクションに `Changed` 項を追加する。

```markdown
## [Unreleased]

### Changed

- Limit supported Rider version to 2026.1.x (`until-build = 261.*`); Rider 2026.2 introduces breaking plugin API changes
```

## 影響範囲の確認

- **`pluginVerification.ides` (build.gradle.kts line 106-111)**: 現在 `local(...)` で
  ビルドに使用する Rider 2025.3.3 のみを検証対象としており、2026.1 を含めていない。
  本変更は `until-build` のメタデータのみで、互換性検証ターゲット自体は変えないため、
  ここは更新不要。
- **`plugin.xml` (src/main/resources/META-INF/plugin.xml)**: `<idea-version>` 要素は
  記述していない (Gradle プラグインがビルド時に注入する) ため、編集不要。

## Verification

### ビルド出力の確認

1. プラグインをビルドする:
   ```bash
   JAVA_HOME=~/Library/Java/JavaVirtualMachines/jbr-25.0.2/Contents/Home \
     ./gradlew --no-configuration-cache buildPlugin
   ```
2. 生成された ZIP 内 `META-INF/plugin.xml` に下記の行が含まれることを確認:
   ```xml
   <idea-version since-build="253" until-build="261.*"/>
   ```
   コマンド例:
   ```bash
   unzip -p build/distributions/*.zip '*/META-INF/plugin.xml' | grep idea-version
   ```

### 既存ユニットテストの回帰確認

設定変更のみだがビルドスクリプトに変更が入るため、念のためテストを通す:

```bash
JAVA_HOME=~/Library/Java/JavaVirtualMachines/jbr-25.0.2/Contents/Home \
  ./gradlew --no-configuration-cache test
```

## E2E Tests

| # | Item | Verification Method |
|---|------|---------------------|
| 1 | Rider 2025.3.x にビルド ZIP をインストールできる | Rider → Settings → Plugins → Install Plugin from Disk で導入し、起動・MCP ツール一覧表示まで確認 |
| 2 | (将来) Rider 2026.2 EAP に対し互換性なしと表示される | Rider 2026.2 EAP が公開され次第、Plugins → Install Plugin from Disk で「This plugin is not compatible with the current version of the IDE」相当のエラーが出ることを確認 |

## Development Workflow

本変更はビルド設定とドキュメントのみのため、Step 1〜4 は以下に集約する。

### Step 1: 設定変更

1. `gradle.properties` に `pluginUntilBuild=261.*` を追加。
2. `build.gradle.kts` の `ideaVersion` ブロックに `untilBuild` 行を追加。
3. `CHANGELOG.md` の `[Unreleased]` に `Changed` 項を追加。

### Step 2: ビルド & 検証

1. `./gradlew --no-configuration-cache buildPlugin` を実行。
2. 生成 ZIP 内 `plugin.xml` に `until-build="261.*"` が含まれることを確認。
3. `./gradlew --no-configuration-cache test` でユニットテストが通ることを確認。

### Step 3: コミット

ブランチ `chore/limit_rider261` 上でコミットメッセージ例:

```
chore: limit supported Rider version to 2026.1 (until-build=261.*)

Rider 2026.2 introduces breaking plugin API changes that this plugin
cannot satisfy. Set the upper bound so the IDE rejects installation
on incompatible builds and prevents silent runtime failures.
```
