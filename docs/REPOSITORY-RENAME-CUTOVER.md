# GitHub repository rename: cutover checklist

**Status: GITHUB REPOSITORY RENAMED 2026-11-13; post-cutover release,
website deployment and installed-device acceptance remain distinct checks.**

Completed move: `Beelzebub2/nuvio-native` to `Beelzebub2/starlit`.
The application repository uses `Dev` as its default branch. Do not create a
new repository at the old slug: GitHub redirects require it
to remain unused.

## Verified GitHub rename state

- The existing GitHub repository was renamed through its repository settings
  API, not recreated or transferred. GitHub repository ID remains
  `1404793587` and the default branch remains `Dev`.
- GitHub REST lookups by both the old and new names return the same repository
  identity. The old and new Git remote URLs both resolve the same `Dev` SHA.
- App/TV rename compatibility [PR #292](https://github.com/Beelzebub2/starlit/pull/292)
  was already merged. Windows/Android fallback first shipped in v1.11.1.
- The previously tagged v1.24.0 release did **not** publish installers because
  the Windows elevation argument-validation test failed. It must not be
  represented as a published download. Follow the separately verified
  recovery release and updater checks.
- The production website's previous build still needs its canonical-link
  release deployment verified. GitHub code merges are not deployments.
- Real-world Linux AppImageUpdate and Samsung TV in-place update behavior
  have not been physically verified as a consequence of renaming. Keep manual
  update paths available and retain old GitHub redirects.

## Compatibility available before cutover

- Windows desktop and Full Android updater bridging was included in the
  published **v1.11.1** release. It accepts either official release path and
  retries only the explicit new GitHub API path after a redirect/404. Builds
  that skipped the compatibility update may need a one-time manual installer.
- Samsung TV release discovery retries the new release API only after the old
  API redirects or returns 404. It never follows arbitrary redirect URLs.
  QR links and checksum-verified PC-assisted WGT downloads accept either exact
  official release path. The generated Apps2Samsung feed and WGT metadata use
  the renamed slug automatically for builds made in the renamed GitHub repo.
- Linux AppImage release metadata is derived from `GITHUB_REPOSITORY` at
  package build time, so **new** AppImages built after the rename use the new
  slug. Existing AppImages retain their embedded old-slug update information;
  test a real old-version update after cutover and provide a manual replacement
  if AppImageUpdate does not follow the GitHub rename.
- The public website source release-feed loader and browser-side feed
  validation accept both official names. The canonical website build defaults
  to `Beelzebub2/starlit` once its own source and deployment are updated.

## Original pre-rename acceptance checklist (historical)

1. Recheck the current `Dev` head, open PRs, required CI, published release
   assets and default-branch protection. Merge the compatibility changes
   and publish a **normal release under the old name** with the TV bridge.
2. Verify unauthenticated stable release queries, release asset downloads,
   SHA-256 manifests, Windows/Android update checks, Samsung package discovery,
   and the website's latest-release feed. Do not call a build device-verified
   without installing and checking it on a real device.
3. Give installed clients the opportunity to upgrade. Do not assume old
   pre-bridge builds are covered by GitHub redirects; preserve a manual
   installer path and existing application data.
4. Verify that `Beelzebub2/starlit` is available. Review GitHub Actions,
   branch rules, repository secrets/variables, webhooks, GitHub Apps, third-
   party automation and any scripts that include the old slug.
5. Confirm a rollback window and a known-good release. Do not change
   application package identities, signers, login callback schemes, or
   persisted data directories as part of the repository rename.

## Post-rename acceptance checklist

1. Confirm `https://github.com/Beelzebub2/starlit`, the default `Dev` branch,
   PRs, issues, tags, releases, and old GitHub HTML/Git URL redirects.
3. Probe old and new GitHub REST release endpoints and installer/WGT asset
   downloads. Validate checksums, not only HTTP status codes. Verify a normal
   new release is built from the renamed repo with the usual CI pipelines.
4. Test in-app Windows and Full Android updating; test Samsung TV discovery,
   Apps2Samsung in-place update and WGT signing on a real compatible TV; test
   legacy Linux AppImageUpdate and a freshly built AppImage. Use a manual
   installer/WGT/AppImage for users whose older updater cannot transition.
5. Update clone/push remotes, the public README and support/license links, and
   `tizen/release/apps2samsung-community.json` to the new canonical slug.
   Preserve the TV signing certificate and package IDs.
6. Refresh the website release snapshot, build/deploy the public site with
   `STARLIT_PUBLIC_REPO=Beelzebub2/starlit`, and verify /, /pt/, /download/,
   /releases.json, /account/ and their security headers at the real HTTPS
   origin. Follow the website's existing deployment and rollback process.
7. Recheck CI, webhooks and integrations that might use an exact repository
   name rather than GitHub's redirect. Keep the old slug unclaimed.

If a critical updater or release route fails, pause new publication, preserve
the known-good binaries and provide the verified manual update route while
fixing the compatibility path. Do not delete accounts or local libraries, and
do not create a second repository to simulate the old URL.
