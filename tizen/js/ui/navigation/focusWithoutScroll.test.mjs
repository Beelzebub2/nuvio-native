import test from "node:test";
import assert from "node:assert/strict";
import {
  focusWithoutScroll,
  resetFocusWithoutScrollSupportForTests
} from "./focusWithoutScroll.js";

function withFakeBrowser(documentRef, windowRef, run) {
  const originalDocument = globalThis.document;
  const originalWindow = globalThis.window;
  resetFocusWithoutScrollSupportForTests();
  try {
    globalThis.document = documentRef;
    globalThis.window = windowRef;
    run();
  } finally {
    resetFocusWithoutScrollSupportForTests();
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
    if (originalWindow === undefined) delete globalThis.window;
    else globalThis.window = originalWindow;
  }
}

function ancestor(parentElement, left, top) {
  return {
    parentElement,
    scrollLeft: left,
    scrollTop: top,
    // Layout measurements are forbidden on the critical focus path.
    get scrollWidth() { throw new Error("unexpected scrollWidth layout read"); },
    get clientWidth() { throw new Error("unexpected clientWidth layout read"); },
    get scrollHeight() { throw new Error("unexpected scrollHeight layout read"); },
    get clientHeight() { throw new Error("unexpected clientHeight layout read"); }
  };
}

test("legacy TV focus restores scroll position without forcing ancestor layout", () => {
  const outer = ancestor(null, 7, 13);
  const inner = ancestor(outer, 31, 51);
  const windowRef = {
    scrollX: 12,
    scrollY: 23,
    scrollTo(x, y) {
      this.scrollX = x;
      this.scrollY = y;
    }
  };
  const documentRef = {
    activeElement: null,
    // Legacy Chromium ignores FocusOptions, including its feature probe.
    createElement: () => ({ focus() {} })
  };
  let focused = 0;
  const target = {
    parentElement: inner,
    focus() {
      focused += 1;
      inner.scrollLeft = 999;
      inner.scrollTop = 999;
      outer.scrollLeft = 888;
      outer.scrollTop = 888;
      windowRef.scrollTo(200, 300);
    }
  };
  withFakeBrowser(documentRef, windowRef, () => {
    assert.doesNotThrow(() => focusWithoutScroll(target));
    assert.equal(focused, 1);
    assert.deepEqual([inner.scrollLeft, inner.scrollTop, outer.scrollLeft, outer.scrollTop], [31, 51, 7, 13]);
    assert.deepEqual([windowRef.scrollX, windowRef.scrollY], [12, 23]);
  });
});

test("already-focused remote target does not refocus or inspect layout", () => {
  let focused = 0;
  const target = { focus() { focused += 1; } };
  withFakeBrowser({ activeElement: target, createElement() { throw new Error("unexpected probe"); } }, {}, () => {
    assert.equal(focusWithoutScroll(target), undefined);
    assert.equal(focused, 0);
  });
});

test("modern TV focus uses the native preventScroll option without ancestor inspection", () => {
  const documentRef = {
    activeElement: null,
    createElement: () => ({
      focus(options) {
        void options.preventScroll;
      }
    })
  };
  const target = {
    parentElement: ancestor(null, 1, 2),
    focus(options) {
      assert.equal(options.preventScroll, true);
    }
  };
  withFakeBrowser(documentRef, {}, () => assert.doesNotThrow(() => focusWithoutScroll(target)));
});
