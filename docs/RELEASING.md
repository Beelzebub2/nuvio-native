# Starlit releases

Starlit uses **Semantic Versioning** (`vMAJOR.MINOR.PATCH`) for its Windows desktop and Android releases. Do not increment the patch version for a significant feature merely because it is the next release: choose the bump from user-visible compatibility and scope.

| Change | Release | Example |
| --- | --- | --- |
| Breaking changes requiring users or integrations to migrate | **MAJOR** | `1.1.0` → `2.0.0` |
| New compatible functionality, new account/sync features, substantial UX improvements | **MINOR** | `1.0.48` → `1.1.0` |
| Compatible bug fixes, security patches and small corrections | **PATCH** | `1.1.0` → `1.1.1` |

The release tag is always `vX.Y.Z`, the release title is `Starlit X.Y.Z`, and the newest release notes go **first** in `docs/RELEASE-NOTES.md` under exactly one `## Starlit X.Y.Z` heading. Describe the changes users will notice and any migration or compatibility caveats. Never include unmerged work in the notes.

## Version sources and build numbers

- `composeApp/Configuration/DesktopVersion.properties` is the authoritative Starlit version for **both Windows and Android**: `VERSION_NAME=X.Y.Z` and `VERSION_CODE=MAJOR*10000 + MINOR*100 + PATCH` (each of `MINOR` and `PATCH` is 0–99). The numeric code must increase monotonically for Android upgrades.
- `iosApp/Configuration/Version.xcconfig` tracks the separate upstream/iOS base version. Do **not** change it for a Starlit Windows/Android release unless separately shipping that iOS build.
- Use `node .github/release-policy.mjs next minor` (or `major`/`patch`) to see the next version and code; update the version file using `scripts/set-version.sh --desktop X.Y.Z --desktop-code CODE` (or equivalent exact edits on Windows).
- `node .github/release-policy.mjs verify vX.Y.Z` validates the version, code, tag, notes and monotonicity. The release workflow and PR notes check both enforce this automatically.

## Publication procedure

1. Merge only reviewed and passing feature/fix PRs into the `Dev` branch. Never tag draft or failing changes; preserve unrelated worktree changes.
2. Select the bump based on the included commits **since the last published release**, update `DesktopVersion.properties`, prepend complete release notes and run `node --test .github/release-notes.test.mjs .github/release-policy.test.mjs` plus `node .github/release-policy.mjs verify vX.Y.Z`.
3. Run focused tests and required CI (`android-check`, source/history security and the release-notes check). Merge the release-preparation PR only when required checks are green.
4. From the exact merged `Dev` commit, create an **annotated** `vX.Y.Z` tag and push that tag once. Do not retag or move an existing published version.
5. `.github/workflows/windows-release.yml` builds and tests Windows, builds the Android APKs, signs the APKs, verifies checksums and publishes the GitHub Release **only after all jobs succeed**. If it fails, fix the cause and use a new patch version/tag; never overwrite existing published installers.
6. Verify the release page includes the Windows installer, signed Android APKs and `SHA256SUMS.txt` and that an in-app upgrade recognizes the new version. Confirm at least one actual account sync on desktop and Android for releases that touch synchronization.

The release workflow is the sole publisher; do not run `gh release create` in parallel with its tag-triggered workflow. A PR merge by itself is not a published release.
