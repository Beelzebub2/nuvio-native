import test from "node:test";
import assert from "node:assert/strict";
import { isStaleDirectionalRepeat } from "./remoteInputBudget.js";

test("first press, OK, Back and events without timestamps are never discarded", () => {
  const clocks = { monotonicNow: 2_000 };
  for (const [event, code] of [
    [{ repeat: false, timeStamp: 1_000 }, 40],
    [{ repeat: true, timeStamp: 1_000 }, 13],
    [{ repeat: true, timeStamp: 1_000 }, 10009],
    [{ repeat: true, timeStamp: 0 }, 37],
    [{ repeat: true }, 38]
  ]) {
    assert.equal(isStaleDirectionalRepeat(event, code, clocks), false);
  }
});

test("only outdated held D-pad repeats are rejected", () => {
  const event = { repeat: true, timeStamp: 1_000 };
  assert.equal(isStaleDirectionalRepeat(event, 37, { monotonicNow: 1_100 }), false);
  assert.equal(isStaleDirectionalRepeat(event, 38, { monotonicNow: 1_239 }), false);
  assert.equal(isStaleDirectionalRepeat(event, 39, { monotonicNow: 1_240 }), true);
  assert.equal(isStaleDirectionalRepeat(event, 40, { monotonicNow: 2_000 }), true);
  assert.equal(isStaleDirectionalRepeat(event, 40, { monotonicNow: 35_001 }), false);
  assert.equal(isStaleDirectionalRepeat(event, 40, { monotonicNow: 500 }), false);
});

test("epoch event timestamps are compared with the epoch clock", () => {
  const timestamp = 1_794_605_000_000;
  const clocks = { epochNow: timestamp + 320, monotonicNow: 12 };
  assert.equal(isStaleDirectionalRepeat({ repeat: true, timeStamp: timestamp }, 40, clocks), true);
  assert.equal(isStaleDirectionalRepeat({ repeat: true, timeStamp: timestamp }, 40, { ...clocks, epochNow: timestamp + 42 }), false);
});
