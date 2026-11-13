import test from "node:test";
import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import vm from "node:vm";
import postcss from "postcss";
import {
  canvasRectToNative,
  computeTvCanvas,
  computeTvLayout,
  nativeRectToCanvas
} from "../js/platform/tvViewport.js";
import {
  evaluatePxExpression,
  evaluateReferenceMediaQuery,
  referenceCanvasPlugin,
  toReferenceCanvasValue
} from "../scripts/referenceCanvasCss.mjs";
import { normalizeDisplaySettings } from "../js/data/local/displaySettingsStore.js";
import {
  applySearchKeyboardKey,
  getSearchKeyboardRows,
  moveInSearchKeyboard
} from "../js/ui/screens/search/searchKeyboard.js";
import {
  describeStreamSummary,
  parseStreamTechnicalSummary
} from "../js/core/streams/streamTechnicalSummary.js";
import {
  createSamsungPlaybackProfile,
  describePlaysBest,
  getPlaybackWarnings
} from "../js/core/streams/tvPlaybackHints.js";

const ACCEPTANCE = {
  "1280x720": 1920,
  "1920x1080": 1920,
  "3840x2160": 1920,
  "2560x1080": 2560,
  "3440x1440": 2580,
  "5120x1440": 3840
};

test("reference canvas is 1080 tall and only widens for wider-than-16:9 panels", () => {
  for (const [size, width] of Object.entries(ACCEPTANCE)) {
    const [screenWidth, screenHeight] = size.split("x").map(Number);
    assert.deepEqual(computeTvLayout(screenWidth, screenHeight), { width, height: 1080 }, size);
  }
  assert.deepEqual(computeTvLayout(0, 0), { width: 1920, height: 1080 });
});

test("index.html requests the same layout viewport before the app boots", async () => {
  const html = await readFile(new URL("../index.html", import.meta.url), "utf8");
  const script = /<script>\s*(\(function configureTizenLaunch\(\)[\s\S]*?\}\)\(\);)\s*<\/script>/.exec(html);
  assert(script, "inline Tizen launch script");
  for (const [size, width] of Object.entries(ACCEPTANCE)) {
    const [screenWidth, screenHeight] = size.split("x").map(Number);
    let content = "";
    const meta = { setAttribute: (_name, value) => (content = value) };
    const window = {
      location: { search: "" },
      navigator: { userAgent: "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0)" },
      screen: { width: screenWidth, height: screenHeight }
    };
    const context = vm.createContext({
      window,
      globalThis: window,
      document: { querySelector: () => meta, createElement: () => meta, head: { appendChild() {} } }
    });
    vm.runInContext(script[1], context);
    assert.match(content, new RegExp(`^width=${width}, height=1080,`), size);
  }
});

test("canvas geometry: device scale, interface size and screen edges", () => {
  const exact = computeTvCanvas({ viewportWidth: 1920, viewportHeight: 1080 });
  assert.equal(exact.scale, 1);
  assert.equal(exact.isTransformed, false);
  assert.equal(exact.appWidth, 1920);

  const hd = computeTvCanvas({ viewportWidth: 1280, viewportHeight: 720 });
  assert.equal(hd.appWidth, 1920);
  assert.ok(Math.abs(hd.scale - 2 / 3) < 1e-9);

  const wide = computeTvCanvas({ viewportWidth: 2560, viewportHeight: 1080 });
  assert.equal(wide.appWidth, 2560);
  assert.equal(wide.isWide, true);

  // Large text-and-controls: a smaller canvas drawn bigger.
  const large = computeTvCanvas({ viewportWidth: 1920, viewportHeight: 1080, interfaceSize: 1.15 });
  assert.equal(large.appWidth, Math.round(1920 / 1.15));
  assert.equal(large.appHeight, Math.round(1080 / 1.15));
  assert.ok(Math.abs(large.scale - 1.15) < 1e-9);

  // Screen edges shrink the picture and centre it; the layout stays 1920.
  const inset = computeTvCanvas({ viewportWidth: 1920, viewportHeight: 1080, overscan: 0.05 });
  assert.equal(inset.appWidth, 1920);
  assert.ok(Math.abs(inset.scale - 0.9) < 1e-9);
  assert.ok(Math.abs(inset.offsetX - 96) < 1e-9);
  assert.ok(Math.abs(inset.offsetY - 54) < 1e-9);

  // A window narrower than 16:9 letterboxes instead of squeezing the canvas.
  const tall = computeTvCanvas({ viewportWidth: 1440, viewportHeight: 1080 });
  assert.equal(tall.appWidth, 1920);
  assert.ok(tall.offsetY > 0);
});

test("AVPlay rectangles map between the canvas and the 1920x1080 native space", () => {
  const plain = { ...computeTvCanvas({ viewportWidth: 1920, viewportHeight: 1080 }), viewportWidth: 1920, viewportHeight: 1080 };
  assert.deepEqual(canvasRectToNative({ x: 0, y: 0, width: 1920, height: 1080 }, undefined, plain), {
    x: 0,
    y: 0,
    width: 1920,
    height: 1080
  });
  const mini = { x: 1280, y: 32, width: 614, height: 345 };
  const native = canvasRectToNative(mini, undefined, plain);
  assert.deepEqual(native, mini);

  // With screen edges on, a mini window moves inward with the picture, while
  // a full-canvas rectangle still means "whole panel".
  const inset = {
    ...computeTvCanvas({ viewportWidth: 1920, viewportHeight: 1080, overscan: 0.05 }),
    viewportWidth: 1920,
    viewportHeight: 1080
  };
  assert.deepEqual(canvasRectToNative({ x: 0, y: 0, width: 1920, height: 1080 }, undefined, inset), {
    x: 0,
    y: 0,
    width: 1920,
    height: 1080
  });
  const insetNative = canvasRectToNative(mini, undefined, inset);
  assert.ok(Math.abs(insetNative.x - (1280 * 0.9 + 96)) < 1e-6);
  assert.ok(Math.abs(insetNative.width - 614 * 0.9) < 1e-6);
  const back = nativeRectToCanvas(insetNative, undefined, inset);
  for (const key of ["x", "y", "width", "height"]) {
    assert.ok(Math.abs(back[key] - mini[key]) < 1e-6, key);
  }
});

test("build pass resolves window-relative lengths against 1920x1080", () => {
  assert.equal(toReferenceCanvasValue("min(1.67vw, 32px)", "font-size"), "32px");
  assert.equal(toReferenceCanvasValue("clamp(28px, 3vw, 56px)", "--gutter"), "56px");
  assert.equal(toReferenceCanvasValue("5vw", "left"), "96px");
  assert.equal(toReferenceCanvasValue("100vw", "width"), "var(--tv-layout-width, 1920px)");
  assert.equal(toReferenceCanvasValue("60vw", "width"), "calc(var(--tv-layout-width, 1920px) * 0.6)");
  assert.equal(
    toReferenceCanvasValue("calc(100vh - 96px)", "height"),
    "calc(var(--tv-layout-height, 1080px) - 96px)"
  );
  assert.equal(toReferenceCanvasValue("translateY(-8vh)", "transform"), "translateY(-86.4px)");
  assert.equal(toReferenceCanvasValue("url(a.png)", "background"), "url(a.png)");
  assert.equal(evaluatePxExpression("calc(2 * (10px + 4px) / 4)"), 7);
  assert.equal(evaluatePxExpression("calc(10px + 5%)"), null);
  assert.equal(evaluateReferenceMediaQuery("(min-width: 1520px)"), true);
  assert.equal(evaluateReferenceMediaQuery("(min-width: 2000px)"), false);
  assert.equal(evaluateReferenceMediaQuery("(max-width: 1280px), (max-height: 760px)"), false);
  assert.equal(evaluateReferenceMediaQuery("(prefers-reduced-motion: reduce)"), null);
});

test("no stylesheet keeps a viewport unit or width media query after the build pass", async () => {
  const cssDir = new URL("../css/", import.meta.url);
  const files = (await readdir(cssDir)).filter((file) => file.endsWith(".css"));
  for (const file of files) {
    const source = await readFile(new URL(file, cssDir), "utf8");
    const { css: processed } = await postcss([referenceCanvasPlugin()]).process(source, { from: undefined });
    const css = processed.replace(/\/\*[\s\S]*?\*\//g, "");
    assert.doesNotMatch(css, /\d(?:vw|vh)\b/, `${file} still uses vw/vh`);
    assert.doesNotMatch(css, /@media[^{]*(?:min|max)-(?:width|height)/, `${file} still has a size query`);
  }
});

test("display settings normalise to supported values", () => {
  assert.deepEqual(normalizeDisplaySettings(null), {
    interfaceSize: "auto",
    overscan: 0,
    textSize: "default",
    reduceMotion: false
  });
  assert.deepEqual(
    normalizeDisplaySettings({ interfaceSize: "LARGE", overscan: 0.04, textSize: "xlarge", reduceMotion: true }),
    { interfaceSize: "large", overscan: 0.05, textSize: "xlarge", reduceMotion: true }
  );
  assert.equal(normalizeDisplaySettings({ overscan: "bogus" }).overscan, 0);
  assert.equal(normalizeDisplaySettings({ interfaceSize: "huge" }).interfaceSize, "auto");
});

test("on-screen keyboard: six per row, actions, and D-pad exits", () => {
  const rows = getSearchKeyboardRows();
  assert.equal(rows.length, 7);
  assert.ok(rows.slice(0, 6).every((row) => row.length === 6));
  assert.deepEqual(rows[6], ["space", "backspace", "clear"]);

  assert.equal(applySearchKeyboardKey("hollo", "w"), "hollow");
  assert.equal(applySearchKeyboardKey("", "space"), "");
  assert.equal(applySearchKeyboardKey("a ", "space"), "a ");
  assert.equal(applySearchKeyboardKey("ab", "space"), "ab ");
  assert.equal(applySearchKeyboardKey("ab😀", "backspace"), "ab");
  assert.equal(applySearchKeyboardKey("abc", "clear"), "");

  assert.equal(moveInSearchKeyboard(rows, 0, 0, "up"), "header");
  assert.equal(moveInSearchKeyboard(rows, 2, 0, "left"), "sidebar");
  assert.equal(moveInSearchKeyboard(rows, 2, 5, "right"), "results");
  assert.deepEqual(moveInSearchKeyboard(rows, 0, 3, "down"), { row: 1, col: 3 });
  // Into the action row: columns 0-2 -> Space, 3 -> Delete, 4-5 -> Clear.
  assert.deepEqual(moveInSearchKeyboard(rows, 5, 1, "down"), { row: 6, col: 0 });
  assert.deepEqual(moveInSearchKeyboard(rows, 5, 3, "down"), { row: 6, col: 1 });
  assert.deepEqual(moveInSearchKeyboard(rows, 5, 5, "down"), { row: 6, col: 2 });
  assert.deepEqual(moveInSearchKeyboard(rows, 6, 0, "up"), { row: 5, col: 1 });
  assert.deepEqual(moveInSearchKeyboard(rows, 6, 2, "up"), { row: 5, col: 4 });
  assert.equal(moveInSearchKeyboard(rows, 6, 1, "down"), null);
  assert.equal(moveInSearchKeyboard(rows, 6, 2, "right"), "results");
});

test("source summaries read resolution, range, audio and codec from release names", () => {
  const dv = parseStreamTechnicalSummary({
    name: "Torrent add-on\n4K DV",
    title: "Show.S02E04.2160p.WEB-DL.DV.DDP5.1.HEVC\n👤 214 💾 9.8 GB",
    infoHash: "a".repeat(40)
  });
  assert.equal(dv.resolution, "4K");
  assert.equal(dv.dynamicRangeId, "dv");
  assert.equal(dv.audio, "DD+ 5.1");
  assert.equal(dv.videoCodec, "HEVC");
  assert.equal(dv.peers, 214);
  assert.equal(dv.availability, "peers");
  assert.equal(dv.sizeText, "9.8 GB");
  assert.equal(describeStreamSummary(dv), "WEB-DL · Dolby Vision · DD+ 5.1 · HEVC");

  const remux = parseStreamTechnicalSummary({
    title: "Show.2160p.BluRay.REMUX.HDR.TrueHD.7.1.Atmos.HEVC",
    url: "https://example.invalid/a.mkv"
  });
  assert.equal(remux.sourceType, "Remux");
  assert.equal(remux.audio, "TrueHD 7.1 Atmos");
  assert.equal(remux.availability, "direct");

  const cached = parseStreamTechnicalSummary({
    title: "Show.1080p.WEB-DL.DDP5.1.H.264 ⚡ cached",
    url: "https://example.invalid/b.mp4",
    behaviorHints: { videoSize: 3.1 * 1024 ** 3 }
  });
  assert.equal(cached.resolution, "1080p");
  assert.equal(cached.dynamicRange, "SDR");
  assert.equal(cached.videoCodec, "H.264");
  assert.equal(cached.availability, "instant");
  assert.equal(cached.sizeText, "3.1 GB");

  // Unrecognised text stays empty instead of guessing.
  const unknown = parseStreamTechnicalSummary({ title: "Sample stream" });
  assert.equal(describeStreamSummary(unknown), "");
  assert.equal(unknown.resolution, "");
});

test("Samsung playback hints flag Dolby Vision, TrueHD and DTS only", () => {
  const current = createSamsungPlaybackProfile({ tizenMajorVersion: 6 });
  const warnings = (title) =>
    getPlaybackWarnings(parseStreamTechnicalSummary({ title }), current).map((warning) => warning.id);
  assert.deepEqual(warnings("Show.2160p.WEB-DL.DV.DDP5.1.HEVC"), ["dolby-vision"]);
  assert.deepEqual(warnings("Show.2160p.REMUX.HDR.TrueHD.7.1.Atmos.HEVC"), ["audio"]);
  assert.deepEqual(warnings("Show.1080p.BluRay.DTS-HD.MA.5.1.x264"), ["audio"]);
  assert.deepEqual(warnings("Show.2160p.WEB-DL.HDR10+.DDP5.1.Atmos.HEVC"), []);
  assert.deepEqual(warnings("Show.1080p.WEBRip.AAC2.0.x265"), []);
  // Off Samsung (no profile) nothing is flagged.
  assert.deepEqual(getPlaybackWarnings(parseStreamTechnicalSummary({ title: "x.DV.TrueHD" }), null), []);
  assert.match(describePlaysBest(current), /HEVC, AV1 · HDR10, HDR10\+ · Dolby Digital Plus/);
  assert.match(describePlaysBest(createSamsungPlaybackProfile({ tizenMajorVersion: 4 })), /H\.264/);
});
