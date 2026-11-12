import test from "node:test";
import assert from "node:assert/strict";

const values = new Map();
globalThis.localStorage = { getItem: key => values.get(key) ?? null,
  setItem: (key, value) => values.set(key, String(value)), removeItem: key => values.delete(key) };
const { AuthManager } = await import("../js/core/auth/authManager.js");
const { AuthState } = await import("../js/core/auth/authState.js");
const { ProfileManager } = await import("../js/core/profile/profileManager.js");
const { SupabaseApi } = await import("../js/data/remote/supabase/supabaseApi.js");
const { watchedItemsRepository } = await import("../js/data/repository/watchedItemsRepository.js");
const { WatchedItemsStore } = await import("../js/data/local/watchedItemsStore.js");
const { LocalStore } = await import("../js/core/storage/localStore.js");
const { WatchedItemsSyncService } = await import("../js/core/profile/watchedItemsSyncService.js");
AuthManager.setState(AuthState.AUTHENTICATED);
ProfileManager.getActiveProfileId = () => "1";
watchedItemsRepository.getAll = async (_limit, profile) => WatchedItemsStore.listForProfile(profile);
watchedItemsRepository.replaceAll = async (items, profile) => WatchedItemsStore.replaceForProfile(profile, items);
const film = { contentId: "tt-synthetic-film", contentType: "movie", title: "Film", watchedAt: 1 };
const pending = profile => LocalStore.get("watchedItemsSyncState", {})[String(profile)]?.pendingDeletedItems || [];

test("guest removals never enqueue an account deletion", async () => {
  values.clear();
  AuthManager.setState(AuthState.SIGNED_OUT);
  SupabaseApi.rpc = async () => assert.fail("guest removal must not call the server");
  try {
    assert.equal(await WatchedItemsSyncService.deleteItems([film], 1), false);
    assert.deepEqual(pending(1), []);
  } finally {
    AuthManager.setState(AuthState.AUTHENTICATED);
  }
});

test("offline watched removals persist per profile and retry before pulling", async () => {
  values.clear();
  const calls = [];
  let offline = true;
  SupabaseApi.rpc = async (name, body) => {
    calls.push([name, body.p_profile_id]);
    if (name === "sync_delete_watched_items" && offline) throw new Error("synthetic offline");
    return [];
  };
  assert.equal(await WatchedItemsSyncService.deleteItems([film], 1), false);
  assert.equal(pending(1).length, 1);
  assert.deepEqual(await WatchedItemsSyncService.pull(1), []);
  assert.equal(calls.some(([name]) => name === "sync_pull_watched_items"), false);
  await WatchedItemsSyncService.pull(2);
  assert.equal(pending(1).length, 1);
  offline = false;
  await WatchedItemsSyncService.pull(1);
  assert.deepEqual(calls.slice(-2), [["sync_delete_watched_items", 1], ["sync_pull_watched_items", 1]]);
  assert.deepEqual(pending(1), []);
});

test("empty pushes retry deletes and local re-marks cancel them", async () => {
  values.clear();
  SupabaseApi.rpc = async () => { throw new Error("synthetic offline"); };
  await WatchedItemsSyncService.deleteItems([film], 1);
  const calls = [];
  SupabaseApi.rpc = async name => { calls.push(name); return []; };
  assert.equal(await WatchedItemsSyncService.push(1), true);
  assert.deepEqual(calls, ["sync_delete_watched_items"]);

  SupabaseApi.rpc = async () => { throw new Error("synthetic offline"); };
  await WatchedItemsSyncService.deleteItems([film], 1);
  WatchedItemsStore.upsert(film, 1);
  calls.length = 0;
  SupabaseApi.rpc = async name => { calls.push(name); return []; };
  await WatchedItemsSyncService.push(1);
  assert.deepEqual(pending(1), []);
  assert.deepEqual(calls, ["sync_push_watched_items"]);
});

test("an acknowledgement cannot discard a newer removal and pulls preserve concurrent local marks", async () => {
  values.clear();
  let finish, started;
  const waiting = new Promise(done => { started = done; });
  let deletes = 0;
  SupabaseApi.rpc = async name => {
    if (name === "sync_delete_watched_items" && ++deletes === 1) {
      started(); return new Promise(done => { finish = done; });
    }
    return [];
  };
  const first = WatchedItemsSyncService.deleteItems([film], 1);
  await waiting;
  const newer = WatchedItemsSyncService.deleteItems([film], 1);
  finish([]);
  await Promise.all([first, newer]);
  assert.equal(deletes, 2);
  assert.deepEqual(pending(1), []);

  let finishPull, pullStarted;
  const pulling = new Promise(done => { pullStarted = done; });
  SupabaseApi.rpc = () => { pullStarted(); return new Promise(done => { finishPull = done; }); };
  const pull = WatchedItemsSyncService.pull(1);
  await pulling;
  WatchedItemsStore.upsert(film, 1);
  finishPull([]);
  assert.equal((await pull)[0].contentId, film.contentId);
  assert.equal(WatchedItemsStore.listForProfile(1)[0].contentId, film.contentId);
});

test("a stale initial snapshot cannot cancel a concurrent delete in a pull or push", async () => {
  for (const operation of ["pull", "push"]) {
    values.clear();
    WatchedItemsStore.upsert(film, 1);
    let finish, started;
    const waiting = new Promise(done => { started = done; });
    const originalGetAll = watchedItemsRepository.getAll;
    watchedItemsRepository.getAll = async () => {
      started(); return new Promise(done => { finish = done; });
    };
    let deletes = 0;
    SupabaseApi.rpc = async name => {
      if (name === "sync_delete_watched_items") { deletes++; return []; }
      if (name === "sync_push_watched_items") assert.fail("must not push a stale local mark");
      return [{ content_id: film.contentId, content_type: "movie", watched_at: 1 }];
    };
    try {
      const syncing = WatchedItemsSyncService[operation](1);
      await waiting;
      WatchedItemsStore.remove(film.contentId, 1);
      const deleting = WatchedItemsSyncService.deleteItems([film], 1);
      finish([film]);
      await syncing;
      await deleting;
      assert.equal(deletes, 1);
      assert.deepEqual(WatchedItemsStore.listForProfile(1), []);
      assert.deepEqual(pending(1), []);
    } finally {
      watchedItemsRepository.getAll = originalGetAll;
    }
  }
});
