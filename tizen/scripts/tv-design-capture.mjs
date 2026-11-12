// Design-acceptance captures for the Starlit TV interface.
//
// Renders the real app in Chromium against a local, generated add-on fixture
// (a series with seasons, a movie row, sources with mixed codecs, saved watch
// progress) and screenshots each redesigned screen. Use it to compare a build
// with the design renders and to check the acceptance matrix:
//
//   node scripts/tv-design-capture.mjs                       # 1920x1080
//   node scripts/tv-design-capture.mjs --matrix              # every acceptance size
//   node scripts/tv-design-capture.mjs --sizes=2560x1080 --screens=home,sources
//   node scripts/tv-design-capture.mjs --theme=GOLD --interface=large --overscan=0.05
//   node scripts/tv-design-capture.mjs --legacy              # no flex gap / grid (Tizen 4)
//
// Everything shown is made-up sample content; no network is used unless
// --fonts lets the Inter web font load. Chromium cannot stand in for Samsung
// AVPlay or a real remote: this checks layout, not the device.
import { createServer } from "node:http";
import { readFile, mkdir, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { build } from "esbuild";
import { chromium } from "@playwright/test";
const root = fileURLToPath(new URL("..", import.meta.url));
const outDir = path.join(root, "test-results", "design");
export const ACCEPTANCE_SIZES = Object.freeze([
  "1280x720",
  "1920x1080",
  "3840x2160",
  "2560x1080",
  "3440x1440",
  "5120x1440"
]);
const ALL_SCREENS = Object.freeze([
  "home",
  "details",
  "sources",
  "player",
  "search",
  "settings",
  "signin"
]);
function readOptions(argv) {
  const options = {
    sizes: ["1920x1080"],
    screens: [...ALL_SCREENS],
    theme: "ARCTIC_BLUE",
    interfaceSize: "auto",
    overscan: 0,
    textSize: "default",
    reduceMotion: false,
    legacy: false,
    fonts: false,
    outline: false,
    tag: ""
  };
  for (const arg of argv) {
    const [name, value = ""] = arg.replace(/^--/, "").split("=");
    if (name === "matrix") options.sizes = [...ACCEPTANCE_SIZES];
    else if (name === "sizes") options.sizes = value.split(",").filter(Boolean);
    else if (name === "screens") options.screens = value.split(",").filter(Boolean);
    else if (name === "theme") options.theme = value.toUpperCase();
    else if (name === "interface") options.interfaceSize = value;
    else if (name === "overscan") options.overscan = Number(value) || 0;
    else if (name === "text") options.textSize = value;
    else if (name === "reduce-motion") options.reduceMotion = true;
    else if (name === "legacy") options.legacy = true;
    else if (name === "fonts") options.fonts = true;
    else if (name === "outline") options.outline = true;
    else if (name === "tag") options.tag = value;
    else throw new Error(`Unknown option: ${arg}`);
  }
  return options;
}
// ---- Generated sample content -------------------------------------------
const PALETTE = [
  ["#1B3C55", "#F2B66D"],
  ["#221838", "#C9B8FF"],
  ["#3A2418", "#E86F4C"],
  ["#20322A", "#D9E3C2"],
  ["#2A2F3A", "#8FD1E8"],
  ["#2D2A4A", "#F0C35A"],
  ["#3B1F2B", "#F28FA6"],
  ["#3A2A12", "#FFB547"],
  ["#1C2B22", "#9CC9A4"],
  ["#152A3A", "#7FC4E0"]
];
function art(index, width, height) {
  const [ground, disc] = PALETTE[index % PALETTE.length];
  const radius = Math.round(Math.min(width, height) * 0.24);
  const cx = Math.round(width * (0.35 + ((index * 7) % 5) * 0.09));
  const cy = Math.round(height * 0.42);
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">` +
    `<rect width="${width}" height="${height}" fill="${ground}"/>` +
    `<circle cx="${cx}" cy="${cy}" r="${radius}" fill="${disc}"/>` +
    `<rect y="${Math.round(height * 0.66)}" width="${width}" height="${Math.round(height * 0.34)}" fill="#000" opacity="0.32"/>` +
    "</svg>"
  );
}
const MOVIES = [
  "Kestrel",
  "Minor Keys",
  "Paper Lanterns",
  "Ironwood",
  "Low Tide",
  "The Ninth Floor",
  "Winter Garden",
  "Firebreak",
  "Glass Harbor",
  "Northbound"
];
const SERIES = ["Hollow Coast", "The Quiet Orbit", "Salt & Signal", "Hollowmere", "Into the Hollow"];
const EPISODES = ["Low Water", "Spring Tide", "The Keeper", "The Undertow", "Breakwater", "Slack Water"];
function createFixture(origin) {
  const image = (index, kind) => `${origin}/fixture/art/${kind}-${index}.svg`;
  const movie = (index) => ({
    id: `sample-movie-${index}`,
    type: "movie",
    name: MOVIES[index % MOVIES.length],
    poster: image(index + 5, "poster"),
    background: image(index + 5, "wide"),
    description: "A generated sample title used to check the television layout.",
    releaseInfo: String(2017 + (index % 8)),
    imdbRating: (6.4 + (index % 5) * 0.4).toFixed(1),
    genres: ["Drama", "Mystery"],
    runtime: `${92 + index * 3} min`
  });
  const series = (index) => ({
    id: `sample-series-${index}`,
    type: "series",
    name: SERIES[index % SERIES.length],
    poster: image(index, "poster"),
    background: image(index, "wide"),
    description:
      "A tide-chart cartographer returns to the island town she left as a teenager, where the lighthouse has gone dark and the shoreline keeps moving a little further inland every night.",
    releaseInfo: "2025",
    imdbRating: "8.1",
    genres: ["Mystery", "Thriller"],
    runtime: "48 min",
    videos: [1, 2, 3].flatMap((season) =>
      EPISODES.map((title, episodeIndex) => ({
        id: `sample-series-${index}:${season}:${episodeIndex + 1}`,
        title,
        name: title,
        season,
        episode: episodeIndex + 1,
        released: `202${2 + season}-03-${String(episodeIndex + 3).padStart(2, "0")}T00:00:00.000Z`,
        overview: "Sample episode synopsis for layout checks.",
        thumbnail: image(index + episodeIndex + season, "wide")
      }))
    )
  });
  const streams = [
    {
      name: "Debrid add-on\n4K HDR10+",
      title: "Hollow.Coast.S02E04.2160p.WEB-DL.HDR10+.DDP5.1.Atmos.HEVC\n💾 11.6 GB ⚡ cached",
      url: `${origin}/media/synthetic-direct.mp4?source=direct-1`,
      behaviorHints: { videoSize: 11.6 * 1024 ** 3 }
    },
    {
      name: "Torrent add-on\n4K DV",
      title: "Hollow.Coast.S02E04.2160p.WEB-DL.DV.DDP5.1.HEVC\n👤 214 💾 9.8 GB",
      url: `${origin}/media/synthetic-direct.mp4?source=p2p-1`,
      behaviorHints: { videoSize: 9.8 * 1024 ** 3 }
    },
    {
      name: "Torrent add-on\n4K HDR",
      title: "Hollow.Coast.S02E04.2160p.BluRay.REMUX.HDR.TrueHD.7.1.Atmos.HEVC\n👤 41 💾 38.1 GB",
      url: `${origin}/media/synthetic-direct.mp4?source=p2p-2`,
      behaviorHints: { videoSize: 38.1 * 1024 ** 3 }
    },
    {
      name: "Debrid add-on\n1080p",
      title: "Hollow.Coast.S02E04.1080p.WEB-DL.DDP5.1.H.264\n💾 3.1 GB ⚡ cached",
      url: `${origin}/media/synthetic-direct.mp4?source=direct-2`,
      behaviorHints: { videoSize: 3.1 * 1024 ** 3 }
    },
    {
      name: "Torrent add-on\n1080p",
      title: "Hollow.Coast.S02E04.1080p.WEBRip.AAC2.0.x265\n👤 88 💾 1.2 GB",
      url: `${origin}/media/synthetic-direct.mp4?source=p2p-3`,
      behaviorHints: { videoSize: 1.2 * 1024 ** 3 }
    },
    {
      name: "Direct links add-on\n720p",
      title: "Hollow.Coast.S02E04.720p.HDTV.AAC2.0.x264\n💾 640 MB",
      url: `${origin}/media/synthetic-direct.mp4?source=direct-3`,
      behaviorHints: { videoSize: 640 * 1024 ** 2 }
    },
    {
      name: "Torrent add-on\n1080p",
      title: "Hollow.Coast.S02E04.1080p.BluRay.DTS-HD.MA.5.1.x264\n👤 17 💾 8.4 GB",
      url: `${origin}/media/synthetic-direct.mp4?source=p2p-4`,
      behaviorHints: { videoSize: 8.4 * 1024 ** 3 }
    }
  ];
  return {
    manifest: {
      id: "org.example.starlit.design-fixture",
      version: "1.0.0",
      name: "Sample add-on",
      description: "Generated sample content for layout checks",
      types: ["movie", "series"],
      resources: ["catalog", "meta", "stream"],
      catalogs: [
        { id: "popular", type: "movie", name: "Popular", extra: [{ name: "search", isRequired: false }] },
        { id: "popular", type: "series", name: "Popular", extra: [{ name: "search", isRequired: false }] }
      ]
    },
    resolve(pathname, searchQuery) {
      const matches = (name) => !searchQuery || name.toLowerCase().includes(searchQuery);
      if (pathname.startsWith("/fixture/catalog/movie")) {
        return { metas: MOVIES.map((_, index) => movie(index)).filter((item) => matches(item.name)) };
      }
      if (pathname.startsWith("/fixture/catalog/series")) {
        return { metas: SERIES.map((_, index) => series(index)).filter((item) => matches(item.name)) };
      }
      const meta = /^\/fixture\/meta\/(movie|series)\/sample-(?:movie|series)-(\d+)\.json$/.exec(pathname);
      if (meta) {
        return { meta: meta[1] === "movie" ? movie(Number(meta[2])) : series(Number(meta[2])) };
      }
      if (pathname.startsWith("/fixture/stream/")) {
        return { streams };
      }
      return null;
    }
  };
}
// ---- Local server ---------------------------------------------------------
const MIME = {
  ".html": "text/html",
  ".js": "text/javascript",
  ".css": "text/css",
  ".json": "application/json",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".wasm": "application/wasm",
  ".mp4": "video/mp4",
  ".ttf": "font/ttf"
};
async function startServer() {
  let fixture;
  let origin = "";
  const server = createServer(async (request, response) => {
    try {
      const url = new URL(request.url, "http://local");
      const pathname = decodeURIComponent(url.pathname);
      const artMatch = /^\/fixture\/art\/(poster|wide)-(\d+)\.svg$/.exec(pathname);
      if (artMatch) {
        const wide = artMatch[1] === "wide";
        response.setHeader("Content-Type", "image/svg+xml");
        response.end(art(Number(artMatch[2]), wide ? 1280 : 600, wide ? 720 : 900));
        return;
      }
      if (pathname === "/fixture/manifest.json") {
        response.setHeader("Content-Type", "application/json");
        response.end(JSON.stringify(fixture.manifest));
        return;
      }
      const search = /search=([^/.&]+)/.exec(pathname);
      const payload = fixture.resolve(pathname, search ? search[1].toLowerCase() : "");
      if (payload) {
        response.setHeader("Content-Type", "application/json");
        response.end(JSON.stringify(payload));
        return;
      }
      const media = pathname.startsWith("/media/");
      const base = path.join(root, media ? "tests/fixtures" : "dist");
      const relative = media ? pathname.slice(7) : pathname === "/" ? "index.html" : pathname.slice(1);
      const target = path.resolve(base, relative);
      if (!target.startsWith(base + path.sep) && target !== base) throw new Error("Invalid path");
      const bytes = await readFile(
        relative === "app.bundle.js" ? path.join(root, ".cache/design-capture.js") : target
      );
      response.setHeader("Content-Type", MIME[path.extname(target)] || "application/octet-stream");
      response.setHeader("Accept-Ranges", "bytes");
      const range = /^bytes=(\d+)-(\d*)$/.exec(request.headers.range || "");
      if (range) {
        const start = Number(range[1]);
        const end = range[2] ? Math.min(Number(range[2]), bytes.length - 1) : bytes.length - 1;
        response.writeHead(206, {
          "Content-Range": `bytes ${start}-${end}/${bytes.length}`,
          "Content-Length": end - start + 1
        });
        response.end(bytes.subarray(start, end + 1));
      } else {
        response.setHeader("Content-Length", bytes.length);
        response.end(bytes);
      }
    } catch (_) {
      response.writeHead(404);
      response.end();
    }
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  origin = `http://127.0.0.1:${server.address().port}`;
  fixture = createFixture(origin);
  return { server, origin };
}
// ---- DOM outline (debugging aid) -----------------------------------------
function outlineInPage({ selector, maxDepth }) {
  const start = document.querySelector(selector);
  if (!start) return `(missing ${selector})`;
  const lines = [];
  const walk = (node, depth) => {
    if (depth > maxDepth) return;
    const style = getComputedStyle(node);
    if (style.display === "none") return;
    const rect = node.getBoundingClientRect();
    const classes =
      typeof node.className === "string" && node.className.trim()
        ? `.${node.className.trim().split(/\s+/).join(".")}`
        : "";
    const text = Array.from(node.childNodes)
      .filter((child) => child.nodeType === 3)
      .map((child) => child.textContent.trim())
      .filter(Boolean)
      .join(" ")
      .slice(0, 40);
    const attributes = ["data-action", "data-section", "data-key", "data-index", "data-season", "role"]
      .filter((name) => node.hasAttribute(name))
      .map((name) => `${name}=${String(node.getAttribute(name)).slice(0, 22)}`)
      .join(" ");
    lines.push(
      `${"  ".repeat(depth)}${node.tagName.toLowerCase()}${node.id ? `#${node.id}` : ""}${classes} ` +
        `[${Math.round(rect.width)}x${Math.round(rect.height)}@${Math.round(rect.left)},${Math.round(rect.top)}] ` +
        `fs=${style.fontSize}${attributes ? ` {${attributes}}` : ""}${text ? ` "${text}"` : ""}`
    );
    let previous = "";
    let repeated = 0;
    Array.from(node.children).forEach((child) => {
      const signature = `${child.tagName}|${typeof child.className === "string" ? child.className.replace(/\b(focused|selected|active)\b/g, "").trim() : ""}`;
      if (signature === previous) {
        repeated += 1;
        if (repeated >= 2) return;
      } else {
        if (repeated >= 2) lines.push(`${"  ".repeat(depth + 1)}… +${repeated - 1} more`);
        repeated = 0;
      }
      previous = signature;
      walk(child, depth + 1);
    });
    if (repeated >= 2) lines.push(`${"  ".repeat(depth + 1)}… +${repeated - 1} more`);
  };
  walk(start, 0);
  return lines.join("\n");
}
// ---- Capture ---------------------------------------------------------------
async function main() {
  const options = readOptions(process.argv.slice(2));
  await mkdir(path.join(root, ".cache"), { recursive: true });
  await mkdir(outDir, { recursive: true });
  await build({
    stdin: {
      contents: `
        import "./js/app.js";
        import { Router } from "./js/ui/navigation/router.js";
        import { ExperienceModeStore } from "./js/data/local/experienceModeStore.js";
        import { addonRepository } from "./js/data/repository/addonRepository.js";
        import { watchProgressRepository } from "./js/data/repository/watchProgressRepository.js";
        import { ThemeStore, accentColorForTheme } from "./js/data/local/themeStore.js";
        import { ThemeManager } from "./js/ui/theme/themeManager.js";
        import { DisplaySettingsStore } from "./js/data/local/displaySettingsStore.js";
        ExperienceModeStore.set({ mode: "ADVANCED", addonSetupSkipped: true });
        globalThis.__starlitDesign = {
          Router, addonRepository, watchProgressRepository, ThemeStore, accentColorForTheme, ThemeManager, DisplaySettingsStore
        };
      `,
      resolveDir: root
    },
    outfile: path.join(root, ".cache/design-capture.js"),
    bundle: true,
    format: "iife",
    define: { __NUVIO_APP_VERSION__: '"0.1.0"' },
    target: "chrome63",
    logLevel: "error"
  });
  const { server, origin } = await startServer();
  const browser = await chromium.launch({
    headless: true,
    args: ["--autoplay-policy=no-user-gesture-required"]
  });
  const report = [];
  try {
    for (const size of options.sizes) {
      const [width, height] = size.split("x").map(Number);
      const context = await browser.newContext({ viewport: { width, height } });
      const page = await context.newPage();
      const errors = [];
      page.on("pageerror", (error) => errors.push(error.message));
      await page.route("https://**/*", (route) => {
        const host = new URL(route.request().url()).hostname;
        if (options.fonts && /^fonts\.(googleapis|gstatic)\.com$/.test(host)) {
          return route.continue();
        }
        return route.abort();
      });
      await page.addInitScript((seed) => {
        localStorage.setItem("skipAuthQrGate", "true");
        localStorage.setItem("activeProfileId", '"1"');
        localStorage.setItem("rememberLastProfile", "true");
        localStorage.setItem("hasEverSelectedProfile", "true");
        localStorage.setItem("tvDisplaySettings", JSON.stringify(seed.display));
        // Chromium is not a Samsung TV: opt in to its playback hints so the
        // "Plays as HDR10" / "Audio may not play" markers can be checked.
        window.__STARLIT_TV_PLAYBACK_PROFILE__ = "samsung";
        if (seed.legacy) {
          // Keep the "no flex gap / no grid" fallbacks on, as Tizen 4 would.
          const keep = () => {
            ["no-flex-gap", "no-css-grid", "no-css-math", "no-aspect-ratio"].forEach((name) => {
              document.documentElement.classList.add(name);
              if (document.body) document.body.classList.add(name);
            });
          };
          document.addEventListener("DOMContentLoaded", keep);
          setInterval(keep, 200);
        }
      }, {
        legacy: options.legacy,
        display: {
          interfaceSize: options.interfaceSize,
          overscan: options.overscan,
          textSize: options.textSize,
          reduceMotion: options.reduceMotion
        }
      });
      await page.goto(origin);
      await page.waitForFunction(
        () => window.__starlitDesign?.Router?.navigate && document.getElementById("home")
      );
      await page.evaluate(
        async ({ url, theme }) => {
          const design = window.__starlitDesign;
          // The store derives the theme from the accent, so pass the theme's own.
          design.ThemeStore.set({ themeName: theme, accentColor: design.accentColorForTheme(theme) });
          design.ThemeManager.apply();
          await design.addonRepository.addAddon(url);
          const now = Date.now();
          const progress = [
            ["sample-series-0", "series", 2, 4, 0.52],
            ["sample-series-1", "series", 1, 2, 0.3],
            ["sample-series-2", "series", 1, 5, 0.82],
            ["sample-movie-9", "movie", null, null, 0.15],
            ["sample-movie-8", "movie", null, null, 0.6]
          ];
          for (let index = 0; index < progress.length; index += 1) {
            const [contentId, contentType, season, episode, ratio] = progress[index];
            const durationMs = 48 * 60 * 1000;
            await design.watchProgressRepository.saveProgress(
              {
                contentId,
                contentType,
                videoId: season ? `${contentId}:${season}:${episode}` : contentId,
                season,
                episode,
                positionMs: Math.round(durationMs * ratio),
                durationMs,
                updatedAt: now - index * 60000
              },
              { syncRemote: false }
            );
          }
          await design.Router.navigate("home", { forceReload: true });
        },
        { url: `${origin}/fixture`, theme: options.theme }
      );
      const prefix = `${size}${options.tag ? `-${options.tag}` : ""}`;
      const capture = async (name, selector) => {
        await page.waitForTimeout(900);
        await page.evaluate(() => document.fonts.ready);
        // Guests fall back to the default theme whenever access is re-checked;
        // keep the requested palette for the capture.
        await page.evaluate(() => window.__starlitDesign.ThemeManager.apply());
        await page.waitForTimeout(150);
        const file = path.join(outDir, `${prefix}-${name}.png`);
        await page.screenshot({ path: file });
        const entry = {
          size,
          screen: name,
          focused: await page.locator(".focused").count(),
          pageErrors: [...errors]
        };
        if (options.outline) {
          entry.outline = await page.evaluate(outlineInPage, { selector, maxDepth: 11 });
          await writeFile(path.join(outDir, `${prefix}-${name}.txt`), entry.outline);
        }
        report.push(entry);
      };
      const wants = (name) => options.screens.includes(name);
      const navigate = (route, params) =>
        page.evaluate(({ route, params }) => window.__starlitDesign.Router.navigate(route, params), {
          route,
          params
        });
      if (wants("home")) {
        await page.waitForFunction(
          () => document.querySelector("#home")?.textContent.includes("Kestrel"),
          { timeout: 20000 }
        );
        await capture("home", "#home");
      }
      if (wants("details")) {
        await navigate("detail", { itemId: "sample-series-0", itemType: "series" });
        await page.waitForFunction(() =>
          document.querySelector("#detail")?.textContent.includes("Hollow Coast")
        );
        await capture("details", "#detail");
        await page.keyboard.press("ArrowDown");
        await page.keyboard.press("ArrowDown");
        await capture("details-episodes", "#detail");
      }
      if (wants("sources")) {
        await navigate("stream", {
          itemId: "sample-series-0",
          itemType: "series",
          videoId: "sample-series-0:2:4",
          itemTitle: "Hollow Coast",
          season: 2,
          episode: 4,
          episodeTitle: "The Undertow"
        });
        await page.waitForFunction(() => document.querySelector("#stream .stream-route-card"));
        await capture("sources", "#stream");
      }
      if (wants("search")) {
        await navigate("search", {});
        await capture("search-empty", "#search");
        // Type with the on-screen keyboard and the D-pad, as on a TV remote.
        await page.waitForFunction(() => document.querySelector("#search .focusable.focused")?.id === "searchInput");
        await page.keyboard.press("ArrowDown");
        await page.waitForFunction(() => document.querySelector("#search .search-key.focused")?.dataset.key === "a");
        const layout = "abcdefghijklmnopqrstuvwxyz1234567890";
        let row = 0;
        let col = 0;
        for (const character of "hollow") {
          const index = layout.indexOf(character);
          const targetRow = Math.floor(index / 6);
          const targetCol = index % 6;
          while (row < targetRow) { await page.keyboard.press("ArrowDown"); row += 1; }
          while (row > targetRow) { await page.keyboard.press("ArrowUp"); row -= 1; }
          while (col < targetCol) { await page.keyboard.press("ArrowRight"); col += 1; }
          while (col > targetCol) { await page.keyboard.press("ArrowLeft"); col -= 1; }
          await page.keyboard.press("Enter");
        }
        const typed = await page.evaluate(() => document.querySelector("#searchInput")?.value || "");
        if (typed !== "hollow") {
          throw new Error(`On-screen keyboard typed "${typed}" instead of "hollow"`);
        }
        await page.waitForTimeout(1500);
        await capture("search", "#search");
      }
      if (wants("settings")) {
        await navigate("settings", { section: "display" });
        await capture("settings", "#settings");
      }
      if (wants("signin")) {
        await navigate("account", {});
        // Offline there is no sign-in server: show the card with a sample
        // device code (the QR encodes a placeholder, not a real sign-in link).
        await page.evaluate(() => {
          const screen = window.__starlitDesign.Router.getCurrentScreen();
          screen.stopIntervals?.();
          // The sample build has no server with TV sign-in: show that mode.
          screen.supportsQrAuth = true;
          screen.supportsEmailAuth = true;
          screen.useQrLogin = true;
          screen.useEmailLogin = false;
          screen.render();
          screen.renderQr?.({
            loginUrl: "https://example.invalid/activate?code=K7QX-4MDP",
            verificationUri: "https://example.invalid/activate",
            displayCode: "K7QX-4MDP",
            expiresAt: Date.now() + 582000
          });
          screen.setStatus?.("");
        });
        await capture("signin", "#account");
      }
      if (wants("player")) {
        await navigate("player", {
          itemId: "sample-series-0",
          itemType: "series",
          playerTitle: "Hollow Coast",
          playerEpisodeTitle: "The Undertow",
          season: 2,
          episode: 4,
          streamUrl: `${origin}/media/synthetic-direct.mp4`,
          mimeType: "video/mp4"
        });
        await page.waitForFunction(() => document.querySelector("#videoPlayer")?.currentTime > 0.5);
        await page.keyboard.press("ArrowDown");
        await capture("player", "#player");
        await page.keyboard.press("Escape");
        await page.waitForTimeout(300);
      }
      if (errors.length) {
        console.error(`[${size}] page errors:\n  ${errors.join("\n  ")}`);
      }
      await context.close();
    }
    await writeFile(path.join(outDir, "report.json"), JSON.stringify(report, null, 2));
    const failed = report.filter((entry) => entry.pageErrors.length);
    console.log(
      `Design captures: ${report.length} in ${path.relative(root, outDir)}` +
        (failed.length ? ` (${failed.length} with page errors)` : "")
    );
    if (failed.length) process.exitCode = 1;
  } finally {
    await browser.close();
    await new Promise((resolve) => server.close(resolve));
  }
}
await main();
