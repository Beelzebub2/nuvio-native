import test from "node:test";
import assert from "node:assert/strict";
globalThis.localStorage ||= { getItem: () => null, setItem() {}, removeItem() {} };
const { createHomeScreenMethods03 } = await import("./homeScreenMethods-03-is-scroll-animation-active.js");

test("sidebar focus lookups avoid scanning the Home card tree", () => {
  let rootQueries = 0;
  let scopedQueries = 0;
  let focused = false;
  const sidebar = {
    querySelector(selector) {
      assert.equal(selector, ".focusable.focused");
      scopedQueries++;
      return focused ? { id: "nav" } : null;
    }
  };
  const activeRoots = [sidebar];
  const container = {
    contains(root) { return activeRoots.includes(root); },
    querySelectorAll(selector) {
      assert.equal(selector, ".home-sidebar, .modern-sidebar-panel");
      rootQueries++;
      return [...activeRoots];
    },
    querySelector() { assert.fail("A sidebar focus check must not query all Home rows"); }
  };
  const screen = { ...createHomeScreenMethods03(), container };
  assert.equal(screen.isSidebarFocusActive(), false);
  focused = true;
  assert.equal(screen.isSidebarFocusActive(), true);
  assert.equal(rootQueries, 1, "reuse sidebar roots while mounted");
  assert.equal(scopedQueries, 2);
  const replacement = { querySelector: () => null };
  activeRoots.splice(0, 1, replacement);
  assert.equal(screen.isSidebarFocusActive(), false);
  assert.equal(rootQueries, 2, "refresh roots after a sidebar is replaced");
  screen.container = null;
  assert.equal(screen.isSidebarFocusActive(), false);
});
