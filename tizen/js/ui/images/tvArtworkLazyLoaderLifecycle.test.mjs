import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";
// The lazy loader reaches the worker pool and the session lifecycle through
// imports, so the real hydration/release code is evaluated against stubs. The
// blob ownership rules are what these tests guard: a stale or released url
// must never be painted, and a live one must be handed back exactly once.
const source = (await readFile(new URL("./tvArtworkLazyLoader.js", import.meta.url), "utf8"))
  .replace(/^import [\s\S]*?";$/m, "")
  .replace(/^import [^;]+;$/gm, "")
  .replace(/^export /gm, "");
const TRANSPARENT_PIXEL = "data:image/gif;base64,R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs=";
function buildContext({ rescaler = true } = {}) {
  const pending = [];
  const released = [];
  const context = vm.createContext({
    console,
    URL,
    setTimeout,
    clearTimeout,
    getHomeArtworkObjectFit: () => "cover",
    isRescalableArtworkFit: (fit) => fit === "cover" || fit === "fill",
    releaseArtwork: (url) => released.push(url),
    rescaleArtwork: (url, options) =>
      new Promise((resolve) => {
        pending.push({ url, options, resolve });
      }),
    resetArtworkRescaler: () => {},
    shouldRescaleHomeArtwork: () => rescaler,
    getTvRuntimePerformanceProfile: () => ({ isTvRuntime: true }),
    getTvArtworkPixelRatio: () => 1,
    registerSessionTeardownHandler: () => {}
  });
  context.globalThis = context;
  vm.runInContext(
    `${source}\nglobalThis.api = { hydrate, releaseImage, setArtworkSource, prepareImage, startTvArtworkLazyLoader, stopTvArtworkLazyLoader };`,
    context
  );
  return { context, pending, released };
}
function makeImage(source) {
  const attributes = new Map([["src", source]]);
  return {
    nodeType: 1,
    tagName: "IMG",
    dataset: { artworkSrc: source },
    isConnected: true,
    classList: { contains: () => false },
    closest: () => null,
    matches: () => true,
    getAttribute: (name) => (attributes.has(name) ? attributes.get(name) : null),
    hasAttribute: (name) => attributes.has(name),
    setAttribute: (name, value) => attributes.set(name, value),
    removeAttribute: (name) => attributes.delete(name),
    getBoundingClientRect: () => ({
      top: 0,
      bottom: 338,
      left: 0,
      right: 220,
      width: 220,
      height: 338
    }),
    get src() {
      return attributes.get("src") || "";
    },
    set src(value) {
      attributes.set("src", value);
    }
  };
}
const flush = () => new Promise((resolve) => setTimeout(resolve, 0));
test("a live rescale paints the blob and records its ownership", async () => {
  const { context, pending } = buildContext();
  const image = makeImage("https://image.tmdb.org/t/p/w500/a.jpg");
  context.api.prepareImage(image);
  assert.equal(pending.length, 1);
  pending[0].resolve("blob:live");
  await flush();
  assert.equal(image.src, "blob:live");
  assert.equal(image.dataset.artworkObjectUrl, "blob:live");
  assert.equal(image.dataset.artworkPending, undefined);
});
test("a rescale that finishes after the card is gone releases its blob", async () => {
  const { context, pending, released } = buildContext();
  const image = makeImage("https://image.tmdb.org/t/p/w500/b.jpg");
  context.api.prepareImage(image);
  image.isConnected = false;
  pending[0].resolve("blob:stale");
  await flush();
  assert.deepEqual(released, ["blob:stale"]);
  assert.equal(image.src, TRANSPARENT_PIXEL, "the stale url must never be painted");
  assert.equal(image.dataset.artworkObjectUrl, undefined);
});
test("a rescale that finishes after the source changed releases its blob", async () => {
  const { context, pending, released } = buildContext();
  const image = makeImage("https://image.tmdb.org/t/p/w500/c.jpg");
  context.api.prepareImage(image);
  image.dataset.artworkSrc = "https://image.tmdb.org/t/p/w500/new.jpg";
  pending[0].resolve("blob:old");
  await flush();
  assert.deepEqual(released, ["blob:old"]);
  assert.equal(image.dataset.artworkObjectUrl, undefined);
});
test("releasing a painted image hands the blob back exactly once", () => {
  const { context, released } = buildContext();
  const image = makeImage("https://image.tmdb.org/t/p/w500/d.jpg");
  context.api.setArtworkSource(image, "blob:painted");
  context.api.releaseImage(image);
  context.api.releaseImage(image);
  assert.deepEqual(released, ["blob:painted"]);
  assert.equal(image.dataset.artworkObjectUrl, undefined);
});
test("Tizen 5 without OffscreenCanvas defers non-Home posters until visible", () => {
  const { context, pending } = buildContext({ rescaler: false });
  const sourceUrl = "https://image.tmdb.org/t/p/w500/offscreen.jpg";
  const image = makeImage(sourceUrl);
  image.getBoundingClientRect = () => ({
    top: 1500,
    bottom: 1838,
    left: 0,
    right: 220,
    width: 220,
    height: 338
  });
  const observed = [];
  context.innerWidth = 1920;
  context.innerHeight = 1080;
  context.IntersectionObserver = class {
    constructor(callback) {
      this.callback = callback;
      context.artworkObserver = this;
    }
    observe(node) { observed.push(node); }
    unobserve(node) { observed.splice(observed.indexOf(node), 1); }
    disconnect() { observed.length = 0; }
  };
  context.MutationObserver = class {
    observe() {}
    disconnect() {}
  };
  context.document = {
    body: {
      nodeType: 1,
      querySelectorAll: () => [image],
      closest: () => null
    }
  };
  assert.equal(context.api.startTvArtworkLazyLoader(), true);
  assert.equal(image.src, TRANSPARENT_PIXEL, "offscreen poster must not decode at mount");
  assert.equal(observed.length, 1);
  assert.equal(pending.length, 0, "no worker/canvas work on legacy Tizen");
  context.artworkObserver.callback([{ target: image, isIntersecting: true }]);
  assert.equal(image.src, sourceUrl);
  assert.equal(image.dataset.artworkPending, undefined);
  assert.equal(pending.length, 0);
  context.api.stopTvArtworkLazyLoader();
});
test("Tizen 5 without OffscreenCanvas hydrates a visible poster immediately", () => {
  const { context, pending } = buildContext({ rescaler: false });
  const sourceUrl = "https://image.tmdb.org/t/p/w500/visible.jpg";
  const image = makeImage(sourceUrl);
  context.innerWidth = 1920;
  context.innerHeight = 1080;
  context.IntersectionObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  context.document = {
    body: {
      nodeType: 1,
      querySelectorAll: () => [image],
      closest: () => null
    }
  };
  assert.equal(context.api.startTvArtworkLazyLoader(), true);
  assert.equal(image.src, sourceUrl, "visible poster must not wait for an observer callback");
  assert.equal(image.dataset.artworkPending, undefined);
  assert.equal(pending.length, 0);
  context.api.stopTvArtworkLazyLoader();
});
test("TV without an image worker or IntersectionObserver keeps the native image path", () => {
  const { context } = buildContext({ rescaler: false });
  context.document = { body: { nodeType: 1 } };
  assert.equal(context.api.startTvArtworkLazyLoader(), false);
});
