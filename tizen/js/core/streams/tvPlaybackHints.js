import { Platform } from "../../platform/index.js";
import { TizenCapabilities } from "../../platform/tizen/tizenCapabilities.js";
// Which sources this TV is likely to play as advertised.
//
// Samsung TVs share a few hard limits across model years, so the table below
// is deliberately conservative: it only flags formats Samsung does not ship a
// decoder or display mode for. It is a hint shown next to a source, never a
// filter, and it stays silent on platforms it knows nothing about.
//
// NOT DEVICE-VERIFIED per model year: Samsung does not expose a per-codec
// capability query to web apps, so refine this as models are checked.
//
//   Dolby Vision   no Samsung TV supports it; the HDR10 base layer plays.
//   Dolby TrueHD   not decoded by the TV (needs a receiver over eARC).
//   DTS family     dropped from 2018 models (Tizen 4) onwards.
const DTS_REMOVED_FROM_TIZEN_MAJOR = 4;
// AV1 first appears in Samsung's 2020 model year (Tizen 5.5), not 2019 (5.0).
const AV1_FROM_TIZEN_MAJOR = 6;
/** Preview and test hook: set to "samsung" to render the Samsung hints anywhere. */
function readProfileOverride() {
  const override = String(globalThis.__STARLIT_TV_PLAYBACK_PROFILE__ || "").toLowerCase();
  return override === "samsung" ? { tizenMajorVersion: 6 } : null;
}
export function createSamsungPlaybackProfile({ tizenMajorVersion = 0, tizenVersion = "", uhdPanelSupported = null } = {}) {
  const version = String(tizenVersion || "").trim();
  const major = Number(tizenMajorVersion) || Number(version.split(".")[0]) || 0;
  const minor = Number((version.match(/^5\.(\d+)/) || [])[1] || 0);
  const supportsAv1 = major >= AV1_FROM_TIZEN_MAJOR || (major === 5 && minor >= 5);
  return Object.freeze({
    platform: "samsung",
    dolbyVision: false,
    hdr10: true,
    hdr10Plus: true,
    trueHd: false,
    // An unknown version is treated like a current TV: flag, never hide.
    dts: major > 0 && major < DTS_REMOVED_FROM_TIZEN_MAJOR,
    av1: supportsAv1,
    hevc: true,
    // null means the TV does not expose the ProductInfo capability, not FHD.
    uhdPanelSupported
  });
}
/** The playback profile for this device, or null when nothing is known. */
export function getTvPlaybackProfile() {
  const override = readProfileOverride();
  if (override) {
    return createSamsungPlaybackProfile(override);
  }
  if (!Platform.isTizen()) {
    return null;
  }
  const capabilities = TizenCapabilities.get();
  return createSamsungPlaybackProfile({
    tizenMajorVersion: capabilities.tizenMajorVersion,
    tizenVersion: capabilities.tizenVersion,
    uhdPanelSupported: capabilities.uhdPanelSupported
  });
}
/**
 * Warnings for one source: [{ id, labelKey, label }].
 * `summary` comes from parseStreamTechnicalSummary().
 */
export function getPlaybackWarnings(summary, profile = getTvPlaybackProfile()) {
  if (!summary || !profile) {
    return [];
  }
  const warnings = [];
  if (summary.dynamicRangeId === "dv" && !profile.dolbyVision) {
    warnings.push({
      id: "dolby-vision",
      labelKey: "sources_warning_plays_as_hdr10",
      label: "Plays as HDR10"
    });
  }
  if (summary.videoCodecId === "av1" && !profile.av1) {
    warnings.push({
      id: "video",
      labelKey: "sources_warning_video_may_not_play",
      label: "Video may not play"
    });
  }
  if (summary.resolutionId === "4k" && profile.uhdPanelSupported === false) {
    warnings.push({
      id: "uhd",
      labelKey: "sources_warning_4k_not_supported",
      label: "4K is not supported by this TV"
    });
  }
  const audio = summary.audioCodecId;
  const unsupportedAudio =
    (audio === "truehd" && !profile.trueHd) ||
    ((audio === "dts" || audio === "dts-hd" || audio === "dts-x") && !profile.dts);
  if (unsupportedAudio) {
    warnings.push({
      id: "audio",
      labelKey: "sources_warning_audio_may_not_play",
      label: "Audio may not play"
    });
  }
  return warnings;
}
/** "HEVC, AV1 · HDR10, HDR10+ · Dolby Digital Plus" for the Sources side panel. */
export function describePlaysBest(profile = getTvPlaybackProfile()) {
  if (!profile) {
    return "";
  }
  const video = ["HEVC", profile.av1 ? "AV1" : "H.264"];
  const range = [profile.hdr10 ? "HDR10" : "", profile.hdr10Plus ? "HDR10+" : ""].filter(Boolean);
  const audio = ["Dolby Digital Plus", profile.dts ? "DTS" : ""].filter(Boolean);
  return [video.join(", "), range.join(", "), audio.join(", ")].filter(Boolean).join(" · ");
}
