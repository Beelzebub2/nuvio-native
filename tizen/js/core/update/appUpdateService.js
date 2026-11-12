// The newest native release often has no Tizen asset. Inspect a bounded batch
// of stable releases rather than treating GitHub's latest overall tag as the
// newest version available for this TV.
export const TIZEN_RELEASES_URL = "https://api.github.com/repos/Beelzebub2/nuvio-native/releases?per_page=50";
const TIZEN_RELEASE_URL_PREFIX = "https://github.com/Beelzebub2/nuvio-native/releases/tag/";
const DEFAULT_TIMEOUT_MS = 8000;
const DEFAULT_RETRY_DELAY_MS = 2000;
const DEFAULT_MAX_ATTEMPTS = 2;
export function normalizeAppVersion(raw) {
  return String(raw || "")
    .trim()
    .replace(/^[vV]/, "");
}
export function parseAppVersionParts(raw) {
  const normalized = normalizeAppVersion(raw);
  if (!normalized) {
    return null;
  }
  const parts = normalized
    .split(/[.\-_]/)
    .filter(Boolean)
    .map((token) => {
      const match = String(token).match(/^\d+/);
      return match ? Number.parseInt(match[0], 10) : null;
    })
    .filter((part) => Number.isFinite(part));
  return parts.length > 0 ? parts : null;
}
export function isRemoteAppVersionNewer(remote, local) {
  const remoteParts = parseAppVersionParts(remote);
  const localParts = parseAppVersionParts(local);
  if (!remoteParts || !localParts) {
    const normalizedRemote = normalizeAppVersion(remote);
    const normalizedLocal = normalizeAppVersion(local);
    return Boolean(normalizedRemote && normalizedLocal && normalizedRemote !== normalizedLocal);
  }
  const length = Math.max(remoteParts.length, localParts.length);
  for (let index = 0; index < length; index += 1) {
    const remotePart = remoteParts[index] || 0;
    const localPart = localParts[index] || 0;
    if (remotePart !== localPart) {
      return remotePart > localPart;
    }
  }
  return false;
}
function releaseTag(release) {
  return String(release?.tag_name || release?.name || "").trim();
}
export function parseLatestRelease(release) {
  if (!release || release.draft || release.prerelease) {
    return null;
  }
  const tag = releaseTag(release);
  const version = /^v(\d+\.\d+\.\d+)$/.exec(tag)?.[1];
  const assetName = version ? `Starlit-Tizen-${version}.wgt` : "";
  if (
    !version ||
    !Array.isArray(release.assets) ||
    !release.assets.some((asset) => asset.name === assetName) ||
    !release.assets.some((asset) => asset.name === "SHA256SUMS-Tizen")
  ) {
    return null;
  }
  return {
    tag,
    title: String(release.name || tag).trim() || tag,
    notes: String(release.body || "").trim(),
    releaseUrl: TIZEN_RELEASE_URL_PREFIX + encodeURIComponent(tag),
    assetName
  };
}
export function selectLatestTizenRelease(releases, currentVersion) {
  if (!Array.isArray(releases)) return null;
  const candidates = releases
    .map(parseLatestRelease)
    .filter((release) => release && isRemoteAppVersionNewer(release.tag, currentVersion));
  // GitHub usually orders releases by publication time, but a later published
  // backport should never trigger a downgrade from a newer Tizen package.
  candidates.sort((a, b) =>
    isRemoteAppVersionNewer(a.tag, b.tag) ? -1 : isRemoteAppVersionNewer(b.tag, a.tag) ? 1 : 0
  );
  return candidates[0] || null;
}
export async function getLatestAppUpdate({
  currentVersion,
  fetchImpl = globalThis.fetch,
  timeoutMs = DEFAULT_TIMEOUT_MS
} = {}) {
  if (typeof fetchImpl !== "function") {
    throw new Error("Fetch is unavailable");
  }
  const supportsAbort = typeof globalThis.AbortController === "function";
  const controller = supportsAbort ? new AbortController() : null;
  let timeoutId = null;
  try {
    const request = fetchImpl(TIZEN_RELEASES_URL, {
      method: "GET",
      headers: {
        Accept: "application/vnd.github+json"
      },
      cache: "no-store",
      signal: controller?.signal
    });
    const result = await Promise.race([
      Promise.resolve(request).then(async (response) => {
        if (!response?.ok) {
          const error = new Error(`GitHub release check failed: HTTP ${response?.status || 0}`);
          error.status = Number(response?.status || 0);
          throw error;
        }
        return response.json();
      }),
      new Promise((_, reject) => {
        timeoutId = setTimeout(
          () => {
            controller?.abort();
            reject(new Error("GitHub release check timed out"));
          },
          Math.max(1, Number(timeoutMs) || DEFAULT_TIMEOUT_MS)
        );
      })
    ]);
    return selectLatestTizenRelease(result, currentVersion);
  } finally {
    if (timeoutId !== null) {
      clearTimeout(timeoutId);
    }
  }
}
function isRetryableUpdateCheckError(error) {
  const status = Number(error?.status || 0);
  return status === 0 || status === 408 || status === 429 || status >= 500;
}
export async function getLatestAppUpdateWithRetry({
  currentVersion,
  fetchImpl = globalThis.fetch,
  timeoutMs = DEFAULT_TIMEOUT_MS,
  retryDelayMs = DEFAULT_RETRY_DELAY_MS,
  maxAttempts = DEFAULT_MAX_ATTEMPTS
} = {}) {
  const attempts = Math.max(1, Number(maxAttempts) || DEFAULT_MAX_ATTEMPTS);
  let lastError = null;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      return await getLatestAppUpdate({ currentVersion, fetchImpl, timeoutMs });
    } catch (error) {
      lastError = error;
      if (attempt >= attempts || !isRetryableUpdateCheckError(error)) {
        throw error;
      }
      await new Promise((resolve) => setTimeout(resolve, Math.max(0, Number(retryDelayMs) || 0)));
    }
  }
  throw lastError || new Error("App update check failed");
}
