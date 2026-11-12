import test from "node:test";import assert from "node:assert/strict";
const values=new Map();globalThis.localStorage={getItem:k=>values.get(k)||null,setItem:(k,v)=>values.set(k,v),removeItem:k=>values.delete(k)};
const {malPlaybackTarget}=await import("../js/core/profile/starlitMalTracking.js");
test("MAL updates require explicit mapped IDs, valid episodes and reached watched threshold",()=>{const progress={contentId:"mal:1234",episode:2,positionMs:85000,durationMs:100000};assert.deepEqual(malPlaybackTarget(progress),{id:1234,episode:2});for(const patch of [{contentId:"tt1234"},{positionMs:1000},{episode:0},{episode:1.5},{durationMs:0},{positionMs:NaN}])assert.equal(malPlaybackTarget({...progress,...patch}),null);});
