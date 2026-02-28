# Plan: ビルドバージョンをgit commit hashにする

## Context

ローカルビルドやCI(build.yml)で生成されるプラグインのバージョンとファイル名が、`gradle.properties`の固定値(`1.0.0`)になっている。
開発中のビルド成果物を区別しやすくするため、デフォルトのバージョンをgit commit short hashにしたい。
リリース時(`release.yml`)は現状通りtagから取得したバージョン番号を使う。

## Approach

`build.gradle.kts` で `version` のデフォルトをgit short hashにし、リリース時は `-PbuildVersion=X.Y.Z` で明示的に上書きする。

- `pluginVersion` (gradle.properties): changelog/channel用に残す（変更なし）
- `buildVersion` (新規Gradleプロパティ): 指定時はそれを `version` に使う
- 未指定時: `git rev-parse --short HEAD` の出力を `version` に使う

## Changes

### 1. `build.gradle.kts` (L15)

**Before:**
```kotlin
version = providers.gradleProperty("pluginVersion").get()
```

**After:**
```kotlin
val gitShortHash = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
}.standardOutput.asText.map { it.trim() }

version = providers.gradleProperty("buildVersion").orElse(gitShortHash).get()
```

- `buildVersion` プロパティが渡されればそれを使用（リリース用）
- 未指定なら `git rev-parse --short HEAD` の結果を使用（ローカル・CI build用）
- `pluginVersion` は `changeNotes`(L90) と `channels`(L117) で引き続き使用 → 変更不要

### 2. `.github/workflows/release.yml`

`publishPlugin` コマンドに `-PbuildVersion=$VERSION` を追加。

**L51 Before:**
```yaml
run: JAVA_HOME=$JAVA_HOME_21_X64 ./gradlew --no-configuration-cache publishPlugin
```

**L51 After:**
```yaml
run: JAVA_HOME=$JAVA_HOME_21_X64 ./gradlew --no-configuration-cache publishPlugin -PbuildVersion=$VERSION
```

- `sed` による `pluginVersion` 更新は維持（changelog patchingとchannel決定に必要）
- `buildVersion` でアーティファクトのバージョン（ZIPファイル名）を制御

### 3. `.github/workflows/build.yml`

変更不要。`buildVersion` 未指定 → git commit hashが自動使用される。

- `Export Properties` ステップ: `./gradlew properties` の `version:` 出力がcommit hashになる
- `releaseDraft`: `v<commit-hash>` タグでドラフトリリースを作成

## Verification

1. **ローカルビルド**: `./gradlew --no-configuration-cache buildPlugin` → `build/distributions/mcp-extension-unity-<hash>.zip` が生成されること
2. **`./gradlew properties`**: `version: <7桁hash>` が出力されること
3. **リリースビルド**: `./gradlew --no-configuration-cache buildPlugin -PbuildVersion=1.0.0` → `build/distributions/mcp-extension-unity-1.0.0.zip` が生成されること
4. **テスト**: `./gradlew --no-configuration-cache test` が通ること
