import test from "node:test";import assert from "node:assert/strict";
const values=new Map();globalThis.localStorage={getItem:k=>values.get(k)||null,setItem:(k,v)=>values.set(k,String(v)),removeItem:k=>values.delete(k)};
const {AuthManager}=await import("../js/core/auth/authManager.js");const {AuthState}=await import("../js/core/auth/authState.js");const {ProfileManager}=await import("../js/core/profile/profileManager.js");const {SupabaseApi}=await import("../js/data/remote/supabase/supabaseApi.js");const {addonRepository}=await import("../js/data/repository/addonRepository.js");const {LibrarySyncService}=await import("../js/core/profile/librarySyncService.js");
let active="1";ProfileManager.getActiveProfileId=()=>active;AuthManager.setState(AuthState.AUTHENTICATED);const remote=new Map();
SupabaseApi.rpc=async(name,body)=>{assert.equal(name,"sync_push_addons");remote.set(String(body.p_profile_id),body.p_addons);return[];};SupabaseApi.select=async(_table,query)=>remote.get(/profile_id=eq\.(\d+)/.exec(query)[1])||[];
test("two profiles roundtrip add-ons and deleting the last remote row clears only that profile",async()=>{
 await addonRepository.addAddon("https://one.example/manifest.json");await LibrarySyncService.push();active="2";await addonRepository.addAddon("https://two.example/manifest.json");await LibrarySyncService.push();remote.set("1",[]);active="1";assert.deepEqual(await LibrarySyncService.pull(),[]);active="2";assert.equal((await LibrarySyncService.pull()).length,1);
});
test("a local add during an in-flight pull survives an empty snapshot",async()=>{
 active="1";let resolve;SupabaseApi.select=()=>new Promise(done=>{resolve=done;});const pulling=LibrarySyncService.pull();await Promise.resolve();await addonRepository.addAddon("https://new.example/manifest.json");resolve([]);await pulling;assert.equal(addonRepository.getInstalledAddonUrls().length,1);
});
