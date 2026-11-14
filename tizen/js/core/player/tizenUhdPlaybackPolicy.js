// 2019 and 2020 Samsung UHD TVs have a native UHD AVPlay decoder, but their
// browser Media Source Extensions path is limited to Full HD. Only change the
// engine preference when the selected source explicitly advertises UHD and
// the TV confirms UHD panel support. Unknown sources retain the existing
// hls.js-first behavior (important for providers with AVPlay HLS failures).
export function shouldPreferTizenUhdHls({
  isTizen = false,
  isHls = false,
  hasAvPlay = false,
  uhdPanelSupported = null,
  tizenVersion = "",
  chromiumMajorVersion = 0,
  sourceResolution = ""
} = {}) {
  if (!isTizen || !isHls || !hasAvPlay || uhdPanelSupported !== true) {
    return false;
  }
  const resolution = String(sourceResolution || "").trim().toLowerCase();
  if (!/^(?:4k|uhd|2160p?|3840\s*[x×]\s*2160)$/.test(resolution)) {
    return false;
  }
  const versionMatch = String(tizenVersion || "").trim().match(/^(\d+)(?:\.(\d+))?/);
  const major = Number(versionMatch?.[1] || 0);
  const chromium = Number(chromiumMajorVersion) || 0;
  // The MSE UHD limit is documented for Tizen 5.0 and 5.5. Use the
  // equivalent Chromium 63/69 generation only when Tizen version is unknown.
  return major === 5 || (major === 0 && chromium >= 63 && chromium <= 69);
}
