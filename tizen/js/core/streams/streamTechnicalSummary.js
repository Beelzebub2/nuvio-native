// What a source actually is, read from the text an add-on publishes for it
// (name, title, description, file name). Add-ons have no common schema, so this
// is tolerant pattern matching: anything it cannot recognise stays empty and
// the caller falls back to the add-on's own wording.
const RESOLUTIONS = [
  { id: "4k", label: "4K", pattern: /\b(?:2160p?|4k|uhd)\b/i },
  { id: "1440p", label: "1440p", pattern: /\b1440p\b/i },
  { id: "1080p", label: "1080p", pattern: /\b(?:1080[pi]|fhd)\b/i },
  { id: "720p", label: "720p", pattern: /\b720p\b/i },
  { id: "480p", label: "480p", pattern: /\b(?:480p|576p|sd)\b/i }
];
const DYNAMIC_RANGES = [
  { id: "dv", label: "DV", name: "Dolby Vision", pattern: /\b(?:dv|dovi|dolby[ ._-]?vision)\b/i },
  { id: "hdr10plus", label: "HDR10+", name: "HDR10+", pattern: /\bhdr10(?:\+|plus)/i },
  { id: "hdr10", label: "HDR10", name: "HDR10", pattern: /\bhdr10\b/i },
  { id: "hlg", label: "HLG", name: "HLG", pattern: /\bhlg\b/i },
  { id: "hdr", label: "HDR", name: "HDR", pattern: /\bhdr\b/i }
];
const SOURCE_TYPES = [
  { label: "Remux", pattern: /\bremux\b/i },
  { label: "BluRay", pattern: /\b(?:blu[ ._-]?ray|bdrip|brrip|bd(?:25|50))\b/i },
  { label: "WEB-DL", pattern: /\bweb[ ._-]?dl\b/i },
  { label: "WEBRip", pattern: /\bweb[ ._-]?rip\b/i },
  { label: "HDTV", pattern: /\bhdtv\b/i },
  { label: "DVD", pattern: /\bdvd(?:rip)?\b/i },
  { label: "CAM", pattern: /\b(?:cam|hdcam|telesync|hdts)\b/i },
  { label: "WEB", pattern: /\bweb\b/i }
];
const AUDIO_CODECS = [
  { id: "truehd", label: "TrueHD", pattern: /\btrue[ ._-]?hd\b/i },
  { id: "dts-hd", label: "DTS-HD MA", pattern: /\bdts[ ._-]?hd(?:[ ._-]?ma)?\b/i },
  { id: "dts-x", label: "DTS:X", pattern: /\bdts[ ._:-]?x\b/i },
  { id: "dts", label: "DTS", pattern: /\bdts\b/i },
  { id: "eac3", label: "DD+", pattern: /\b(?:ddp|dd\+|e[ ._-]?ac[ ._-]?3|dolby[ ._-]?digital[ ._-]?plus)/i },
  { id: "ac3", label: "DD", pattern: /\b(?:ac[ ._-]?3|dd(?=[ ._-]?[257][ ._]?[01])|dolby[ ._-]?digital)\b/i },
  { id: "aac", label: "AAC", pattern: /\baac/i },
  { id: "flac", label: "FLAC", pattern: /\bflac\b/i },
  { id: "opus", label: "Opus", pattern: /\bopus\b/i },
  { id: "mp3", label: "MP3", pattern: /\bmp3\b/i }
];
const VIDEO_CODECS = [
  { id: "hevc", label: "HEVC", pattern: /\b(?:hevc|[hx][ ._]?265)\b/i },
  { id: "av1", label: "AV1", pattern: /\bav1\b/i },
  { id: "avc", label: "H.264", pattern: /\b(?:avc|[hx][ ._]?264)\b/i },
  { id: "vp9", label: "VP9", pattern: /\bvp9\b/i }
];
const SIZE_PATTERN = /(\d+(?:[.,]\d+)?)\s?(tb|gb|mb|gib|mib)\b/i;
const PEER_PATTERNS = [/\u{1F464}\s*(\d+)/u, /\b(?:seed(?:er)?s?|peers?)\s*[:=]?\s*(\d+)/i, /\bS:\s*(\d+)/];
const CACHED_PATTERN = /⚡|\bcached\b|\binstant\b|\[(?:RD|AD|PM|TB|DL|ED)\+\]/i;
function firstMatch(table, text) {
  return table.find((entry) => entry.pattern.test(text)) || null;
}
function collectText(stream) {
  return [
    stream?.name,
    stream?.title,
    stream?.description,
    stream?.behaviorHints?.filename,
    stream?.raw?.behaviorHints?.filename,
    stream?.quality
  ]
    .filter((value) => typeof value === "string" && value.trim())
    .join(" \n ");
}
function readChannels(text, codecMatchIndex) {
  // Only the layout written right after the codec ("DDP5.1", "AAC 2.0",
  // "TrueHD.7.1"): anywhere else a "5.1" is as likely to be a size or version.
  if (codecMatchIndex < 0) {
    return "";
  }
  const tail = text.slice(codecMatchIndex, codecMatchIndex + 24);
  // No lookbehind: Tizen 4 (Chromium 56) cannot parse it.
  const match = /(?:^|\D)([1257])[ ._]?([01])(?!\d)/.exec(tail);
  return match ? `${match[1]}.${match[2]}` : "";
}
export function formatStreamSize(bytes) {
  const size = Number(bytes);
  if (!Number.isFinite(size) || size <= 0) {
    return "";
  }
  const gigabytes = size / 1024 ** 3;
  if (gigabytes >= 1) {
    return `${gigabytes >= 100 ? Math.round(gigabytes) : Math.round(gigabytes * 10) / 10} GB`;
  }
  return `${Math.max(1, Math.round(size / 1024 ** 2))} MB`;
}
function readSize(stream, text) {
  const hinted = Number(stream?.behaviorHints?.videoSize || stream?.raw?.behaviorHints?.videoSize);
  if (Number.isFinite(hinted) && hinted > 0) {
    return { bytes: hinted, text: formatStreamSize(hinted) };
  }
  const match = SIZE_PATTERN.exec(text);
  if (!match) {
    return { bytes: 0, text: "" };
  }
  const amount = Number(match[1].replace(",", "."));
  const unit = match[2].toLowerCase();
  const factor = unit.startsWith("t") ? 1024 ** 4 : unit.startsWith("g") ? 1024 ** 3 : 1024 ** 2;
  const bytes = amount * factor;
  return { bytes, text: formatStreamSize(bytes) };
}
function readPeers(text) {
  for (const pattern of PEER_PATTERNS) {
    const match = pattern.exec(text);
    if (match) {
      const peers = Number(match[1]);
      if (Number.isFinite(peers)) return peers;
    }
  }
  return null;
}
/**
 * Technical facts about one source.
 *
 * availability: "instant" (already cached by a debrid service), "peers"
 * (torrent, see `peers`), "direct" (plain link) or "" when unknown.
 */
export function parseStreamTechnicalSummary(stream = {}) {
  const text = collectText(stream);
  const resolution = firstMatch(RESOLUTIONS, text);
  const dynamicRange = firstMatch(DYNAMIC_RANGES, text);
  const sourceType = firstMatch(SOURCE_TYPES, text);
  const audioCodec = firstMatch(AUDIO_CODECS, text);
  const videoCodec = firstMatch(VIDEO_CODECS, text);
  const audioIndex = audioCodec ? text.search(audioCodec.pattern) : -1;
  const channels = audioCodec ? readChannels(text, audioIndex) : "";
  const atmos = /\batmos\b/i.test(text);
  const size = readSize(stream, text);
  const peers = readPeers(text);
  const isTorrent = Boolean(stream?.infoHash) || peers !== null;
  const cached =
    /cached|instant/i.test(String(stream?.debridCacheStatus || "")) || CACHED_PATTERN.test(text);
  const availability = cached ? "instant" : isTorrent ? "peers" : stream?.url ? "direct" : "";
  return {
    resolutionId: resolution ? resolution.id : "",
    resolution: resolution ? resolution.label : "",
    dynamicRangeId: dynamicRange ? dynamicRange.id : "",
    dynamicRange: dynamicRange ? dynamicRange.label : resolution ? "SDR" : "",
    dynamicRangeName: dynamicRange ? dynamicRange.name : "",
    sourceType: sourceType ? sourceType.label : "",
    audioCodecId: audioCodec ? audioCodec.id : "",
    audio: audioCodec
      ? [audioCodec.label, channels, atmos ? "Atmos" : ""].filter(Boolean).join(" ")
      : "",
    videoCodecId: videoCodec ? videoCodec.id : "",
    videoCodec: videoCodec ? videoCodec.label : "",
    sizeBytes: size.bytes,
    sizeText: size.text,
    peers,
    availability
  };
}
/** "WEB-DL · HDR10+ · DD+ 5.1 Atmos · HEVC", or "" when nothing was recognised. */
export function describeStreamSummary(summary) {
  if (!summary) {
    return "";
  }
  const range = summary.dynamicRangeName || "";
  return [summary.sourceType, range, summary.audio, summary.videoCodec].filter(Boolean).join(" · ");
}
