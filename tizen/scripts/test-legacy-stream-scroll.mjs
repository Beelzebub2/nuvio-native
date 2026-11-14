import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const css = readFileSync(new URL("../css/components-50.css", import.meta.url), "utf8");
const rule = css.match(/\.legacy-webos \.stream-route-list\.manual-scroll\s*>\s*\*\s*\{([^}]+)\}/);
assert(rule, "Legacy webOS manual scroller style must exist");
assert.match(rule[1], /transform:\s*translateY\(/);
assert.match(rule[1], /transition:\s*none\s*!important\s*;/);
console.log("Legacy webOS stream list keeps instant manual scrolling");
