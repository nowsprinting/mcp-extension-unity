# Release sequence

## Stable channel release

1. CHANGELOG.md has been committed and pushed in advance
2. Open [Releases](https://github.com/nowsprinting/mcp-extension-unity/releases)
3. Click **Edit** on draft release
4. **Create new tag** to next version (`v`+semver)
5. Fix **Release title** to match
6. Click **Publish release**

> [!NOTE]\
> A suffix-less tag (`v2.0.0`) publishes to the **`default`** (Stable) channel.\
> After publishing, the release workflow opens a `Changelog update - <version>` PR that moves the
> `[Unreleased]` entries under the new version heading. Review and **merge** it.

## EAP channel release

1. CHANGELOG.md has been committed and pushed in advance
2. Open [Releases](https://github.com/nowsprinting/mcp-extension-unity/releases)
3. Click **Edit** on draft release
4. **Create new tag** to next version with a channel suffix (e.g. `v2.0.0-eap.1`)
5. Fix **Release title** to match
6. Select **Pre-release** from the "Release label" options
7. Click **Publish release**

> [!NOTE]\
> The tag's channel suffix selects the Marketplace channel: `v2.0.0-eap.1` publishes to the **`eap`**
> channel. The release workflow copies the tag into `pluginVersion`, and `build.gradle.kts` derives the
> channel from the text between `-` and the first `.`.\
> The release workflow **skips** the changelog PR for EAP releases (any tag containing `-`), so
> `[Unreleased]` stays intact and keeps accumulating across EAP iterations until the next Stable release.
