import test from "node:test";
import assert from "node:assert/strict";
const storage=new Map();globalThis.localStorage={getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,String(v)),removeItem:k=>storage.delete(k)};
const resolver=await import("../js/core/p2p/tizenStreamingServerResolver.js");
const {getTizenCapabilities}=await import("../js/platform/tizen/tizenCapabilities.js");
const {TorrentSettingsStore,TORRENT_CACHE_SIZES}=await import("../js/data/local/torrentSettingsStore.js");
test("multifile source preserves explicit index and tracker order",()=>{
 assert.equal(resolver.selectFileIdx({fileIdx:3},{guessedFileIdx:0}),3);
 assert.equal(resolver.selectFileIdx({},{guessedFileIdx:2}),2);
 const hash="a".repeat(40);assert.equal(resolver.extractInfoHashFromMagnet(`magnet:?xt=urn:btih:${hash}`),hash);
 assert.equal(resolver.normalizeInfoHash("../../settings"),"");
 assert.match(resolver.buildPlaybackUrl("http://127.0.0.1:2710",hash,3,["tracker:https://tracker.example"]),new RegExp(`/${hash}/3\\?tr=`));
});
test("Tizen 4 is direct only, 5 enables engine candidate, 6 permits optional plugins",()=>{
 const runtime=v=>({__NUVIO_PLATFORM__:"tizen",__NUVIO_TIZEN_VERSION__:v,navigator:{userAgent:`Tizen ${v}`}});
 assert.equal(getTizenCapabilities(runtime("4.0")).supportsP2p,false);
 assert.equal(getTizenCapabilities(runtime("5.5")).supportsP2p,true);
 assert.equal(getTizenCapabilities(runtime("5.5")).tizenPluginVersionSupported,false);
 assert.equal(getTizenCapabilities(runtime("6.0")).tizenPluginVersionSupported,true);
});
test("TV cache settings cannot enable persistent disk caching",()=>{
 assert.deepEqual(TORRENT_CACHE_SIZES,{NONE:0});TorrentSettingsStore.set({torrentCacheSize:"GB_10"});assert.equal(TorrentSettingsStore.get().torrentCacheSize,"NONE");
});

test("encoded and base32 BTIH magnets normalize to twenty-byte hex hashes",()=>{
 assert.equal(resolver.extractInfoHashFromMagnet("magnet:?xt=urn%3Abtih%3AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),"0".repeat(40));
 assert.equal(resolver.extractInfoHashFromMagnet("magnet:?XT=URN:BTIH:"+"7".repeat(32)),"f".repeat(40));
 assert.equal(resolver.extractInfoHashFromMagnet("magnet:?xt=urn:btmh:1220"+"a".repeat(64)),"");
 for(const value of ["A".repeat(31),"A".repeat(33),"!".repeat(32)])assert.equal(resolver.normalizeInfoHash(value),"");
});

test("software subtitle extraction fails closed without probing an unrelated localhost service",async()=>{const {TizenEngineFsService}=await import("../js/platform/tizen/tizenEngineFsService.js");const previous=globalThis.fetch;globalThis.fetch=()=>{throw new Error("must not probe");};try{assert.equal((await TizenEngineFsService.ensureStarted({purpose:"subtitle"})).status,"unsupported");}finally{globalThis.fetch=previous;}});
