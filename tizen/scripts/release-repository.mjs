// Keep existing release links working until the GitHub repository is renamed.
// The Actions-provided GITHUB_REPOSITORY switches automatically at cutover.
export const LEGACY_RELEASE_REPO = "Beelzebub2/nuvio-native";
export const RENAMED_RELEASE_REPO = "Beelzebub2/starlit";

export function releaseRepoForBuild(githubRepository = process.env.GITHUB_REPOSITORY) {
  return githubRepository === RENAMED_RELEASE_REPO
    ? RENAMED_RELEASE_REPO
    : LEGACY_RELEASE_REPO;
}
