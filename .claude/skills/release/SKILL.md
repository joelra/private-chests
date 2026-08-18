---
name: release
description: Cut a Private Chests release - finalize the CHANGELOG in end-user voice, verify preflight, tag vX.Y.Z, and let the release workflow build and publish the per-version jars to a GitHub release. Use when the user asks to release, publish, or ship a version.
---

# Private Chests release process

Releases are driven by tags: pushing `vX.Y.Z` triggers `.github/workflows/release.yml`,
which builds and game-tests every Minecraft version in clean CI, extracts that
version's CHANGELOG section as the release notes, and publishes a GitHub release
with all three runtime jars (`private-chests-X.Y.Z+{1.21.11,26.1.1,26.2}.jar`).
Your job is everything the workflow can't judge: the changelog wording, preflight
sanity, and the tag itself.

## 1. Preflight — all must hold before anything else

- On `main`, working tree clean, synced with `origin/main` (`git fetch` + compare).
- CI green on the `main` HEAD commit (`gh run list --branch main --limit 1`).
- Stonecutter state canonical: `./gradlew "Reset active project"` then `git diff --exit-code`
  (the VCS/active version is 26.2).
- `mod_version` in `gradle.properties` equals the version being released
  (the release workflow hard-fails on mismatch). If it needs bumping, that bump is
  part of the release commit.
- `CHANGELOG.md` has a `## <version>` section (see step 2).

## 2. Finalize the CHANGELOG entry — end-user voice

The section becomes the public release notes verbatim. Audience: **server admins and
players**, not contributors. Rules:

- Lead with what changed in-game or for administration: protection fixes, new config
  options, supported Minecraft versions, which jar to download.
- Say "locked chests can no longer be filled through droppers", not
  "added DropperBlockMixin".
- Name issue numbers for fixes (`#5`, `#6`) — they link automatically on GitHub.
- Include a **Compatibility notes** block: Minecraft version per jar
  (`+1.21.11` needs Java 21+, `+26.1.1` and `+26.2` need Java 25+), and
  config/data migration impact (usually "existing configs and locks work
  unchanged"; call out anything like the automatic dimension migration of
  pre-1.4.0 lock data).
- Internal changes (build system, tests, refactors) are dropped entirely — the
  changelog is end-user facing only. No "Internals" sections.
- Replace any `(unreleased)` marker with the release date:
  `## X.Y.Z — YYYY-MM-DD`.

Show the rewritten entry to the user and get approval before committing.

Also sweep `README.md` for staleness against this release: supported-version
badge, requirements (per-jar Java versions), install instructions (jar
naming/selection), command docs, and anything describing behavior that changed
this cycle.

## 3. Commit and verify

- Commit the changelog (and any version bump) to `main` as `release: vX.Y.Z`.
- Push, then wait for the CI build on that commit to go green before tagging —
  never tag an unverified commit.

## 4. Tag — this is the release trigger

```
git tag vX.Y.Z
git push origin vX.Y.Z
```

## 5. Watch and verify

- Watch the Release workflow run (`gh run watch`).
- When it finishes, verify the release page: correct notes, exactly three jars
  (one per Minecraft version, no `-sources` jars), tag marked Latest.
- Link the release to the user.

## 6. Post-release

- This mod publishes to GitHub releases only (no Modrinth/CurseForge presence
  yet — if that changes, add the publication steps here).
- Closed issues referenced in the notes get a comment only if the fix needs
  user action (e.g. new config); otherwise the auto-close from the PR suffices.
- The next feature commit bumps `mod_version` (this repo does not use -SNAPSHOT
  dev versions; version bumps land with the first change of the next cycle).
