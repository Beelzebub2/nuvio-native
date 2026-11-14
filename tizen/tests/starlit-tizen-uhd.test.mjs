import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { getTizenCapabilities, readUhdPanelSupported } from "../js/platform/tizen/tizenCapabilities.js";
import { shouldPreferTizenUhdHls } from "../js/core/player/tizenUhdPlaybackPolicy.js";
import { chooseSamsungAudioFallbackTrack } from "../js/core/player/tizenAudioFallbackPolicy.js";
import {
  createSamsungPlaybackProfile,
  describePlaysBest,
  getPlaybackWarnings
} from "../js/core/streams/tvPlaybackHints.js";
import { parseStreamTechnicalSummary } from "../js/core/streams/streamTechnicalSummary.js";

test("Samsung AV1 availability follows 2019 Tizen 5.0 vs 2020 Tizen 5.5", () => {
  const tv2019 = createSamsungPlaybackProfile({ tizenMajorVersion: 5, tizenVersion: "5.0" });
  const tv2020 = createSamsungPlaybackProfile({ tizenMajorVersion: 5, tizenVersion: "5.5" });
  const tv2021 = createSamsungPlaybackProfile({ tizenMajorVersion: 6, tizenVersion: "6.0" });
  assert.equal(tv2019.av1, false);
  assert.equal(tv2020.av1, true);
  assert.equal(tv2021.av1, true);
  assert.equal(createSamsungPlaybackProfile({ tizenMajorVersion: 5 }).av1, false);
  assert.doesNotMatch(describePlaysBest(tv2019), /AV1/);
  assert.match(describePlaysBest(tv2020), /AV1/);
  const av1 = parseStreamTechnicalSummary({ title: "Sample.2160p.AV1.WEB-DL" });
  assert.deepEqual(getPlaybackWarnings(av1, tv2019).map((w) => w.id), ["video"]);
  assert.deepEqual(getPlaybackWarnings(av1, tv2020).map((w) => w.id), []);
});

test("UHD capability warnings do not confuse an unknown panel with FHD", () => {
  const source = parseStreamTechnicalSummary({ title: "Sample.2160p.HEVC.WEB-DL" });
  const noUhd = createSamsungPlaybackProfile({ tizenVersion: "5.0", uhdPanelSupported: false });
  const unknown = createSamsungPlaybackProfile({ tizenVersion: "5.0", uhdPanelSupported: null });
  assert.deepEqual(getPlaybackWarnings(source, noUhd).map((w) => w.id), ["uhd"]);
  assert.deepEqual(getPlaybackWarnings(source, unknown), []);
  assert.deepEqual(getPlaybackWarnings(source, null), []);
});

test("ProductInfo capability probing never treats permission errors as FHD", () => {
  assert.equal(readUhdPanelSupported({}), null);
  assert.equal(readUhdPanelSupported({ webapis: { productinfo: { isUdPanelSupported: () => true } } }), true);
  assert.equal(readUhdPanelSupported({ webapis: { productinfo: { isUdPanelSupported: () => false } } }), false);
  assert.equal(
    readUhdPanelSupported({ webapis: { productinfo: { isUdPanelSupported() { throw new Error("SecurityError"); } } } }),
    null
  );
  const runtime = {
    navigator: { userAgent: "Tizen 5.0; Chrome/63.0.3239.0" },
    webapis: { productinfo: { isUdPanelSupported: () => true } }
  };
  assert.equal(getTizenCapabilities(runtime).uhdPanelSupported, true);
});

test("native UHD HLS preference is restricted to confirmed early UHD TVs and UHD sources", () => {
  const baseline = {
    isTizen: true,
    isHls: true,
    hasAvPlay: true,
    uhdPanelSupported: true,
    tizenVersion: "5.0",
    chromiumMajorVersion: 63,
    sourceResolution: "4k"
  };
  assert.equal(shouldPreferTizenUhdHls(baseline), true);
  assert.equal(shouldPreferTizenUhdHls({ ...baseline, tizenVersion: "5.5" }), true);
  assert.equal(shouldPreferTizenUhdHls({ ...baseline, sourceResolution: "3840x2160" }), true);
  for (const changed of [
    { isTizen: false }, { isHls: false }, { hasAvPlay: false },
    { uhdPanelSupported: false }, { uhdPanelSupported: null },
    { tizenVersion: "6.0" }, { sourceResolution: "1080p" },
    { sourceResolution: "" }
  ]) {
    assert.equal(shouldPreferTizenUhdHls({ ...baseline, ...changed }), false, JSON.stringify(changed));
  }
  assert.equal(shouldPreferTizenUhdHls({ ...baseline, tizenVersion: "", chromiumMajorVersion: 69 }), true);
  assert.equal(shouldPreferTizenUhdHls({ ...baseline, tizenVersion: "", chromiumMajorVersion: 94 }), false);
});

test("controller engine candidates preserve normal HLS but put AVPlay first for known UHD", () => {
  const source = readFileSync(new URL("../js/core/player/playerControllerMethods-12-get-playback-engine-candidates.js", import.meta.url), "utf8")
    .replace(/^import \* as internals from .*;\r?\n/gm, "")
    .replace(/^export /gm, "");
  const capabilities = { tizenVersion: "5.0", chromiumMajorVersion: 63, uhdPanelSupported: true };
  const internals = {
    Platform: { isTizen: () => true },
    TizenCapabilities: { get: () => capabilities },
    shouldPreferTizenUhdHls
  };
  const methods = vm.runInNewContext(`${source}\ncreatePlayerControllerMethods12()`, { internals, URL });
  const controller = Object.assign(methods, {
    getPlatformAvplayEngineName: () => "avplay",
    guessMediaMimeType: () => "application/vnd.apple.mpegurl",
    isLikelyHlsMimeType: (value) => String(value || "").includes("mpegurl"),
    isTizenHlsSource: () => true,
    isLivePlaybackItemType: () => false,
    canUseAvPlay: () => true,
    canUseHlsJs: () => true,
    canUseDashJs: () => false,
    shouldPreferTvNativePipeline: () => false,
    canPlayNatively: () => false,
    currentStreamResolution: "4k"
  });
  const candidates = () => Array.from(controller.getPlaybackEngineCandidates(
    "https://example.org/stream.m3u8",
    "application/vnd.apple.mpegurl"
  ));
  assert.deepEqual(candidates(), ["avplay", "hls.js"]);
  controller.currentStreamResolution = "1080p";
  assert.deepEqual(candidates(), ["hls.js", "avplay"]);
  controller.currentStreamResolution = "4k";
  capabilities.uhdPanelSupported = null;
  assert.deepEqual(candidates(), ["hls.js", "avplay"]);
});

test("audio fallback only substitutes known-decodable tracks for unsupported Samsung defaults", () => {
  const tracks = [
    { avplayTrackIndex: 0, codec: "DTS-HD MA", language: "eng" },
    { avplayTrackIndex: 1, codec: "AC-3", language: "spa" },
    { avplayTrackIndex: 2, codec: "E-AC-3", language: "eng" },
    { avplayTrackIndex: 3, codec: "AAC", language: "eng" }
  ];
  assert.equal(chooseSamsungAudioFallbackTrack(tracks, 0, { tizenMajorVersion: 5 }), 2);
  assert.equal(chooseSamsungAudioFallbackTrack(tracks, 0, { tizenMajorVersion: 3 }), -1);
  assert.equal(chooseSamsungAudioFallbackTrack(tracks, 2, { tizenMajorVersion: 5 }), -1);
  assert.equal(chooseSamsungAudioFallbackTrack(tracks.slice(0, 1), 0, { tizenMajorVersion: 5 }), -1);
  assert.equal(chooseSamsungAudioFallbackTrack([
    { avplayTrackIndex: 0, codec: "TrueHD", language: "eng" },
    { avplayTrackIndex: 1, codec: "", language: "eng" },
    { avplayTrackIndex: 2, codec: "AAC", language: "eng" }
  ], 0, { tizenMajorVersion: 5 }), 2);
  assert.equal(chooseSamsungAudioFallbackTrack([
    { avplayTrackIndex: 0, codec: "DTS", language: "eng" },
    { avplayTrackIndex: 1, codec: "", language: "eng" }
  ], 0, { tizenMajorVersion: 5 }), -1);
});
