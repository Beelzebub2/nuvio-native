import assert from "node:assert/strict";
import { test } from "node:test";
import { readFile, readdir } from "node:fs/promises";
import { ScreenUtils } from "../js/ui/navigation/screen.js";
import { Router } from "../js/ui/navigation/routerState.js";

const savedStorage = globalThis.localStorage;
if (!savedStorage) {
  globalThis.localStorage = {
    getItem: () => null,
    setItem() {},
    removeItem() {}
  };
}

const [{ ExperienceModeSelectionScreen }, { EssentialAddonSetupScreen }, { LicensesAttributionsScreen }] =
  await Promise.all([
    import("../js/ui/screens/onboarding/experienceModeSelectionScreen.js"),
    import("../js/ui/screens/onboarding/essentialAddonSetupScreen.js"),
    import("../js/ui/screens/settings/licensesAttributionsScreen.js")
  ]);

if (!savedStorage) {
  delete globalThis.localStorage;
}

class FakeFocusable {
  constructor(index) {
    this.dataset = { index: String(index) };
    this.classes = new Set(["focusable"]);
    this.clicks = 0;
    this.classList = {
      contains: (name) => this.classes.has(name),
      add: (name) => this.classes.add(name),
      remove: (name) => this.classes.delete(name)
    };
  }
  focus() {
    globalThis.document.activeElement = this;
  }
  click() {
    this.clicks += 1;
  }
}

function createHarness(count) {
  const registered = [];
  const removed = [];
  const container = {
    style: {},
    childNodes: [],
    innerHTML: "",
    nodes: [],
    setNodes(size) {
      this.nodes = Array.from({ length: size }, (_, i) => new FakeFocusable(i));
    },
    querySelector(selector) {
      if (selector === ".focusable.focused") {
        return this.nodes.find((node) => node.classList.contains("focused")) || null;
      }
      if (selector === ".focusable") return this.nodes[0] || null;
      return null;
    },
    querySelectorAll(selector) {
      return selector === ".focusable" ? this.nodes : [];
    },
    addEventListener: (name) => registered.push(name),
    removeEventListener: (name) => removed.push(name)
  };
  container.setNodes(count);
  const oldDocument = globalThis.document;
  const oldElement = globalThis.HTMLElement;
  const listeners = [];
  globalThis.HTMLElement = FakeFocusable;
  globalThis.document = {
    body: { classList: { contains: () => false } },
    getElementById: () => container,
    addEventListener: (name) => listeners.push(name),
    activeElement: null
  };
  return {
    container,
    registered,
    removed,
    listeners,
    restore() {
      if (oldDocument === undefined) delete globalThis.document;
      else globalThis.document = oldDocument;
      if (oldElement === undefined) delete globalThis.HTMLElement;
      else globalThis.HTMLElement = oldElement;
    }
  };
}

function press(code, key, repeat = false) {
  const event = { keyCode: code, key, repeat, prevented: false };
  event.preventDefault = () => { event.prevented = true; };
  return event;
}

test("mode and layout choices advance one card per Samsung remote press", async () => {
  const h = createHarness(2);
  const screen = ExperienceModeSelectionScreen;
  try {
    await screen.mount();
    assert.deepEqual(h.listeners, [], "FocusEngine owns document keydown");
    assert.deepEqual(h.registered, ["click"]);
    assert.equal(h.container.querySelector(".focusable.focused"), h.container.nodes[0]);

    const right = press(39, "Right");
    await screen.onKeyDown(right);
    assert.equal(right.prevented, true);
    assert.equal(h.container.querySelector(".focusable.focused"), h.container.nodes[1]);

    screen.step = "layout";
    h.container.setNodes(3);
    ScreenUtils.setInitialFocus(h.container);
    await screen.onKeyDown(press(39, "ArrowRight"));
    assert.equal(h.container.querySelector(".focusable.focused"), h.container.nodes[1],
      "one remote press must not skip the middle layout");

    const select = press(13, "Return");
    await screen.onKeyDown(select);
    await screen.onKeyDown(press(13, "Return", true));
    assert.equal(select.prevented, true);
    assert.equal(h.container.nodes[1].clicks, 1, "held Enter must not activate twice");
  } finally {
    screen.cleanup();
    assert.deepEqual(h.removed, ["click"]);
    h.restore();
  }
});

test("Samsung Back returns from layout choice to mode choice without exiting", () => {
  const h = createHarness(3);
  const screen = ExperienceModeSelectionScreen;
  const originalRender = screen.render;
  let rendered = 0;
  try {
    screen.container = h.container;
    screen.step = "layout";
    screen.render = () => {
      rendered += 1;
      h.container.setNodes(2);
    };
    assert.equal(screen.consumeBackRequest(), true);
    assert.equal(screen.step, "mode");
    assert.equal(rendered, 1);
    assert.equal(h.container.querySelector(".focusable.focused"), h.container.nodes[0]);
    assert.equal(screen.consumeBackRequest(), false);
  } finally {
    screen.render = originalRender;
    h.restore();
  }
});

test("add-on setup uses one remote dispatcher and ignores Enter repeats", async () => {
  const h = createHarness(2);
  const screen = EssentialAddonSetupScreen;
  try {
    await screen.mount();
    assert.deepEqual(h.listeners, [], "must not register a second document key listener");
    assert.deepEqual(h.registered, ["click"]);
    screen.onKeyDown(press(39, "Right"));
    assert.equal(h.container.querySelector(".focusable.focused"), h.container.nodes[1]);
    screen.onKeyDown(press(13, "Return"));
    screen.onKeyDown(press(13, "Return", true));
    assert.equal(h.container.nodes[1].clicks, 1);
    screen.onKeyDown(press(37, "Left"));
    assert.equal(h.container.querySelector(".focusable.focused"), h.container.nodes[0]);
  } finally {
    screen.cleanup();
    assert.deepEqual(h.removed, ["click"]);
    h.restore();
  }
});

test("Samsung Back from add-on setup returns to mode selection once", async () => {
  const h = createHarness(2);
  const screen = EssentialAddonSetupScreen;
  const previousNavigate = Router.navigate;
  const calls = [];
  try {
    await screen.mount();
    Router.navigate = async (...args) => { calls.push(args); };
    assert.equal(screen.consumeBackRequest(), true);
    assert.equal(screen.consumeBackRequest(), true, "repeat Back remains consumed");
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(calls.length, 1);
    assert.equal(calls[0][0], "experienceModeSelection");
    assert.deepEqual(calls[0][2], {
      replaceHistory: true,
      skipStackPush: true
    });
    assert.equal(screen.returningToMode, false);
  } finally {
    Router.navigate = previousNavigate;
    screen.cleanup();
    h.restore();
  }
});

test("licenses remote navigation crosses sections without repeating or skipping rows", async () => {
  const h = createHarness(4);
  const screen = LicensesAttributionsScreen;
  try {
    // Original section-local indexes repeat when another section starts.
    h.container.nodes[1].dataset.index = "0";
    h.container.nodes[2].dataset.index = "1";
    h.container.nodes[3].dataset.index = "0";
    await screen.mount();
    assert.deepEqual(h.listeners, [], "FocusEngine alone handles document keydown");
    assert.deepEqual(h.registered, ["click"]);
    assert.deepEqual(h.container.nodes.map((node) => node.dataset.index),
      ["0", "1", "2", "3"], "license focus indexes span all sections");
    screen.onKeyDown(press(40, "Down"));
    assert.equal(h.container.querySelector(".focusable.focused"), h.container.nodes[1]);
    screen.onKeyDown(press(40, "ArrowDown"));
    assert.equal(h.container.querySelector(".focusable.focused"), h.container.nodes[2]);
    screen.onKeyDown(press(40, "ArrowDown"));
    assert.equal(h.container.querySelector(".focusable.focused"), h.container.nodes[3]);
    screen.onKeyDown(press(38, "Up"));
    assert.equal(h.container.querySelector(".focusable.focused"), h.container.nodes[2]);
    await screen.onKeyDown(press(13, "Return"));
    await screen.onKeyDown(press(13, "Return", true));
    assert.equal(h.container.nodes[2].clicks, 1);
  } finally {
    screen.cleanup();
    assert.deepEqual(h.removed, ["click"]);
    h.restore();
  }
});

test("all locale onboarding titles consistently use the Starlit product name", async () => {
  const root = new URL("../res/", import.meta.url);
  const locales = (await readdir(root, { withFileTypes: true }))
    .filter((entry) => entry.isDirectory() && entry.name.startsWith("values"));
  assert.equal(locales.length, 38, "cover every packaged language catalog");
  for (const locale of locales) {
    const xml = await readFile(new URL(`${locale.name}/strings.xml`, root), "utf8");
    for (const key of ["experience_mode_choose_title", "layout_selection_welcome"]) {
      const match = new RegExp(`<string name="${key}">([^<]*)</string>`).exec(xml);
      assert(match, `${locale.name} must translate ${key}`);
      assert(match[1].includes("Starlit"), `${locale.name}: ${key} should use the correct brand`);
      assert(!/Nuvio|நூவியோ|நுவியோ/i.test(match[1]), `${locale.name}: upstream brand remains`);
    }
  }
});
