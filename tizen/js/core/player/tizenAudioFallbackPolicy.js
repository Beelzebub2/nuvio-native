import { getAudioCodecFamily, getAuthoritativeAudioCodecValue } from "./audioTrackCodecMetadata.js";

const SUPPORTED_FALLBACK_CODECS = new Set(["aac", "eac3", "ac-3"]);
// 2018-and-newer Samsung TVs do not decode DTS; TrueHD cannot be decoded by
// the TV itself. A known AAC/AC-3/E-AC-3 alternative avoids silent playback.
export function chooseSamsungAudioFallbackTrack(tracks = [], selectedIndex = -1, { tizenMajorVersion = 0 } = {}) {
  if (Number(tizenMajorVersion) < 4 || !Array.isArray(tracks) || tracks.length < 2) {
    return -1;
  }
  const current = tracks.find((track) => Number(track.avplayTrackIndex) === Number(selectedIndex));
  if (!current) return -1;
  const codecFamily = getAudioCodecFamily(getAuthoritativeAudioCodecValue(current));
  if (codecFamily !== "dts" && codecFamily !== "truehd") return -1;
  const options = tracks.filter((track) =>
    Number(track.avplayTrackIndex) !== Number(selectedIndex) &&
    SUPPORTED_FALLBACK_CODECS.has(getAudioCodecFamily(getAuthoritativeAudioCodecValue(track)))
  );
  if (!options.length) return -1;
  const language = String(current.language || "").trim().toLowerCase();
  // Prefer same-language audio; use another known-decodable track only if
  // nothing suitable exists. Never infer a codec from the track number.
  const chosen = options.find((track) => String(track.language || "").trim().toLowerCase() === language) || options[0];
  const index = Number(chosen.avplayTrackIndex);
  return Number.isFinite(index) && index >= 0 ? index : -1;
}
