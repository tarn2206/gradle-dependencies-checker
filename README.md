Gradle Dependency Updates
========================================

[![Java CI with Gradle](https://github.com/tarn2206/gradle-dependencies-checker/actions/workflows/gradle.yml/badge.svg)](https://github.com/tarn2206/gradle-dependencies-checker/actions/workflows/gradle.yml)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/fcd2dd6c76ab4cdfba496acbbbeda796)](https://app.codacy.com/gh/tarn2206/gradle-dependencies-checker/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)

An IntelliJ IDEA plugin that surfaces available version updates and known vulnerabilities
for every dependency in your Gradle project — in a dedicated tool window and directly in
your editor.

![Tool window showing dependency updates](docs/screenshot.png)

Features
--------

### Tool window

A **Dependency Updates** tool window (right-hand side) lists every first-level dependency
per module. Outdated deps show `current → latest`; catalog-managed entries display their
accessor (`libs.guava`, `libs.plugins.springboot`). Plugins declared in a version catalog
appear under their own "Plugins" node.

### Version catalog support

Full support for `gradle/libs.versions.toml`, including `[versions]`, `[libraries]`, and
`[plugins]` sections. Handles inline versions, `version.ref`, rich versions (`strictly`,
`require`, `prefer`), and shorthand forms.

### One-click apply update

Right-click any outdated dependency in the tool window → **Apply Update**. Works with
`build.gradle`, `build.gradle.kts`, and `libs.versions.toml`. When a shared `version.ref`
would move multiple libraries or plugins, a confirmation dialog lists everything that
will change. Every update is a single undo — Ctrl+Z reverts cleanly. File formatting,
quote style, and whitespace are preserved.

### Vulnerability (CVE) flagging

Every dependency is checked against the [OSV.dev](https://osv.dev/) database. Affected
deps show a colored severity badge (`⚠ 3 CVEs (CRITICAL)`). Right-click →
**Open Vulnerability Report** opens the OSV.dev page for the coordinate. Results are
cached for 6 hours on disk to avoid re-querying clean deps every session. Can be
disabled in settings.

### Editor integration

Inspections, quick-fixes, and gutter icons appear directly in your editor.

- **`libs.versions.toml`** — outdated entries and vulnerable artifacts are underlined.
  Alt+Enter offers "Update to X" and "Open vulnerability report" quick-fixes. Version
  keys used by multiple libraries get a single marker on their `[versions]` entry
  rather than one per user.
- **`build.gradle`** (Groovy DSL) — inline coordinate literals like
  `'org.foo:bar:1.0'` are inspected and fixable via Alt+Enter or the gutter icon.
- **`build.gradle.kts`** (Kotlin DSL) — same behavior for
  `"org.foo:bar:1.0"` literals.

Interpolated versions (`"$var"`, `"${expr}"`) are safely ignored to avoid false positives.
BOM-managed entries (no version to edit) are skipped. Library entries with `version.ref` do
show icons — the click routes through to the shared `[versions]` key, and a confirmation
dialog fires if the update would move multiple libraries together.

### Auto-refresh

The tool window and editor decorations refresh automatically after Gradle sync completes
and when any `*.versions.toml` file changes on disk. Debounced by 2 seconds to coalesce
bursts. Toggle in settings.

### Sort

Dependencies in the tool window can be sorted three ways, via the sort action in the
tool window title bar:

- **By severity** (default) — vulnerable first (ordered by CVE severity), then outdated,
  then errored, then clean. Alphabetical within each bucket. Anything that needs your
  attention is at the top.
- **Alphabetical** — sorted by `group:name`.
- **Declaration order** — the order Gradle returned them, roughly matching your
  `build.gradle`.

Sort applies to library dependencies within each module. Sub-project nodes and the
"Plugins" node keep their positions.

### Filter

Toggle **Show Only Upgradable** in the tool window title bar to hide dependencies without
an available update. Applied incrementally as checks complete — modules start empty and
fill in as updates are discovered, rather than showing every dep and then hiding some.

Vulnerable deps without an available update stay hidden under this filter (the filter is
strictly about upgradability). Turn the toggle off to see everything again.

### Expand / Collapse all

Buttons in the tool window title bar. Operate on the currently visible tree, so they
respect any active filter.

### Jump to source

Double-click any row in the tool window to open its declaration in the editor. Right-click
→ **Jump to Source** does the same.

- Catalog library or plugin entries → `libs.versions.toml` at the version line. For
  `version.ref` entries, this is the shared `[versions]` key where the actual version
  literal lives.
- Inline coordinate literals in `build.gradle` / `build.gradle.kts` → the exact coord
  position in the file.
- Module tree nodes → top of that module's build file.

### Private repository support

Configure additional Maven repositories in the plugin settings. HTTP basic-auth
credentials can be embedded in URLs (`https://user:token@repo.example.com`). Gradle Plugin
Portal is included by default. The plugin honors IntelliJ's Gradle JDK, Gradle User Home,
and environment variables configured in **Settings → Build → Gradle**, so credentialed
private repos (GitHub Packages, etc.) that work for normal sync also work here.

Settings
--------

Open the tool window and click the ⚙ icon in its title bar.

- **Maven Repository**: the list of repositories to query. Toggle active state; reorder
  by priority. Include credentials in the URL for private repos.
- **Ignore unstable version**: skip pre-releases when computing the "latest" version.
  The comma-separated pattern list controls which qualifiers are considered unstable
  (default: `alpha, beta, -M, incubator, rc, snapshot`).
- **Check for vulnerabilities**: enables the OSV.dev queries and CVE badges.
- **Auto-refresh on Gradle sync or catalog file changes**: enables the automatic refresh
  behavior described above.

Usage
-----

1. Open a Gradle project.
2. Open the **Dependency Updates** tool window (right side) — first open triggers a scan.
3. Wait for the tree to populate. Outdated deps and CVEs appear as they're checked.
4. Right-click any row for actions, or edit your build files directly and use Alt+Enter
   on the underlined coordinates.

The plugin never modifies files without an explicit user action.

Plugin Download page
--------------------

<https://plugins.jetbrains.com/plugin/14243-check-for-dependency-updates>