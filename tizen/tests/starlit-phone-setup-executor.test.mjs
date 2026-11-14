import test from "node:test";
import assert from "node:assert/strict";

const store = new Map();
globalThis.localStorage = {
  getItem: key => store.get(key) || null,
  setItem: (key, value) => store.set(key, String(value)),
  removeItem: key => store.delete(key)
};
const { createTvSetupExecutor } = await import("../js/core/phoneSetup/tvSetupActions.js");
const { addonRepository } = await import("../js/data/repository/addonRepository.js");
const { PluginManager } = await import("../js/core/player/pluginManager.js");
const { PluginStore } = await import("../js/data/local/pluginStore.js");
const { ProfileManager } = await import("../js/core/profile/profileManager.js");
const { LibrarySyncService } = await import("../js/core/profile/librarySyncService.js");

function withFakeTV(run, { executable = false } = {}) {
  const originals = [];
  const replace = (object, key, value) => {
    originals.push([object, key, object[key]]);
    object[key] = value;
  };
  let active = "1";
  let canEdit = true;
  let syncCalls = 0;
  let installedAddons = [];
  let repositories = [];
  let providers = [];
  let targetUpdated = "";
  let switchOnPluginAdd = false;
  const enabled = new Map();
  const pluginToggles = [];
  const fetched = [];
  replace(ProfileManager, "getActiveProfileId", () => active);
  replace(addonRepository, "canEdit", () => canEdit);
  replace(addonRepository, "getInstalledAddonUrls", () => [...installedAddons]);
  replace(addonRepository, "getAddonEnabledStates", () =>
    Object.fromEntries([...enabled.entries()]));
  replace(addonRepository, "isAddonEnabled", url => enabled.get(url) !== false);
  replace(addonRepository, "addAddon", async url => {
    installedAddons.push(url);
    enabled.set(url, true);
    return true;
  });
  replace(addonRepository, "removeAddon", async url => {
    installedAddons = installedAddons.filter(x => x !== url);
    enabled.delete(url);
    return true;
  });
  replace(addonRepository, "setAddonEnabledStates", updates => {
    updates.forEach(item => enabled.set(item.url, item.enabled));
    return true;
  });
  replace(addonRepository, "notifyAddonsChanged", () => {});
  replace(addonRepository, "invalidateInstalledAddonsCache", () => {});
  replace(LibrarySyncService, "push", async () => { syncCalls++; });
  replace(PluginManager, "getSummary", () => ({
    runtime: { executable, reason: executable ? "" : "Unsupported Tizen runtime" },
    readOnly:false, repositories:[...repositories], scrapers:[...providers]
  }));
  replace(PluginManager, "listRepositories", () => [...repositories]);
  replace(PluginManager, "listScrapers", () => [...providers]);
  replace(PluginManager, "addRepository", async url => {
    const repo = { id:"repo1", url, name:"Owned Fixture", enabled:true };
    repositories.push(repo);
    providers.push({ id:"repo1:provider1", repositoryId:"repo1", name:"Authorized provider", enabled:true });
    if (switchOnPluginAdd) active = "2";
    return repo;
  });
  replace(PluginStore, "update", (mutator, targetProfile) => {
    targetUpdated = String(targetProfile);
    const next = mutator({ repositories:[...repositories], scrapers:[...providers], syncDirty:true });
    repositories = next.repositories;
    providers = next.scrapers;
    pluginToggles.push(["pinned", targetUpdated, repositories[0].enabled, providers[0].enabled]);
    return next;
  });
  replace(PluginManager, "removeRepository", async id => {
    repositories = repositories.filter(repo => repo.id !== id);
    providers = providers.filter(item => item.repositoryId !== id);
    return true;
  });
  replace(PluginManager, "setAllScrapersEnabled", (id, flag) => {
    providers = providers.map(item => item.repositoryId === id ? { ...item, enabled:flag } : item);
    pluginToggles.push(["all", id, flag]);
    return true;
  });
  replace(PluginManager, "setRepositoryEnabled", (id, flag) => {
    repositories = repositories.map(repo => repo.id === id ? { ...repo, enabled:flag } : repo);
    pluginToggles.push(["repo", id, flag]);
    return true;
  });
  replace(PluginManager, "setScraperEnabled", (id, flag) => {
    providers = providers.map(item => item.id === id ? { ...item, enabled:flag } : item);
    return true;
  });
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async url => {
    fetched.push(url);
    return {
      ok:true,
      headers:{ get:()=>null },
      text:async () => JSON.stringify(url.includes("addon")
        ? { id:"org.test", name:"Authorized Add-on", resources:["stream"] }
        : { name:"Owned Fixture", scrapers:[{ name:"Authorized provider", id:"provider1" }] })
    };
  };
  const env = {
    get active() { return active; }, set active(x) { active = x; },
    get canEdit() { return canEdit; }, set canEdit(x) { canEdit = x; },
    get syncCalls() { return syncCalls; },
    get installedAddons() { return [...installedAddons]; },
    get pluginToggles() { return [...pluginToggles]; },
    get targetUpdated() { return targetUpdated; },
    set switchOnPluginAdd(value) { switchOnPluginAdd = Boolean(value); },
    get repositories() { return [...repositories]; },
    get providers() { return [...providers]; },
    get fetched() { return [...fetched]; }
  };
  return Promise.resolve().then(() => run(env)).finally(() => {
    for (const [object, key, original] of originals.reverse()) object[key] = original;
    globalThis.fetch = originalFetch;
  });
}

test("phone addon actions require preview and fresh TV revision, preserve profile scoping", async () => {
  await withFakeTV(async env => {
    const executor = createTvSetupExecutor("1");
    const url = "https://addons.example.org/manifest.json";
    const initial = executor.state();
    assert.equal(initial.canEditAddons, true);
    await assert.rejects(() => executor.execute({
      id:1, op:"installAddon", url, revision:initial.revision
    }), /Preview this repository/);
    const preview = await executor.execute({
      id:2, op:"previewAddon", url, revision:initial.revision
    });
    assert.equal(preview.detail.name, "Authorized Add-on");
    const installed = await executor.execute({
      id:3, op:"installAddon", url, revision:executor.state().revision
    });
    assert.match(installed.message, /Installed/);
    assert.deepEqual(env.installedAddons, [url]);
    assert.equal(env.syncCalls, 1);
    assert.equal(env.fetched.length, 2, "manifest fetched again before install");
    const state = executor.state();
    assert.equal(state.addons[0].id, "addon:0");
    await assert.rejects(() => executor.execute({
      id:4, op:"setAddonEnabled", target:"addon:0", enabled:false, revision:initial.revision
    }), /List changed/);
    await executor.execute({
      id:5, op:"setAddonEnabled", target:"addon:0", enabled:false, revision:state.revision
    });
    assert.equal(executor.state().addons[0].enabled, false);
    const revision = executor.state().revision;
    env.active = "2";
    await assert.rejects(() => executor.execute({
      id:6, op:"removeAddon", target:"addon:0", revision
    }), /profile changed/);
  });
});

test("plugin capability gate rejects JS install on older TVs", async () => {
  await withFakeTV(async () => {
    const executor = createTvSetupExecutor("1");
    const state = executor.state();
    assert.equal(state.pluginExecutable, false);
    await assert.rejects(() => executor.execute({
      id:1, op:"previewPlugin", url:"https://plugins.example.org/manifest.json", revision:state.revision
    }), /unsupported/);
  }, { executable:false });
});

test("supported TV installs repositories with providers off until explicitly enabled", async () => {
  await withFakeTV(async env => {
    const executor = createTvSetupExecutor("1");
    const url = "https://plugins.example.org/manifest.json";
    await executor.execute({ id:1, op:"previewPlugin", url, revision:executor.state().revision });
    await executor.execute({ id:2, op:"installPlugin", url, revision:executor.state().revision });
    assert.deepEqual(env.pluginToggles, [["pinned","1",false,false]]);
    assert.equal(executor.state().repositories[0].enabled, false);
    assert.equal(executor.state().providers[0].enabled, false);
    await executor.execute({
      id:3, op:"setRepositoryEnabled", target:"repo:0", enabled:true,
      revision:executor.state().revision
    });
    assert.equal(executor.state().repositories[0].enabled, true);
  }, { executable:true });
});

test("plugin providers stay disabled if profile changes during asynchronous install", async () => {
  await withFakeTV(async env => {
    const executor = createTvSetupExecutor("1");
    const url = "https://plugins.example.org/manifest.json";
    await executor.execute({
      id:1, op:"previewPlugin", url, revision:executor.state().revision
    });
    env.switchOnPluginAdd = true;
    await assert.rejects(() => executor.execute({
      id:2, op:"installPlugin", url, revision:executor.state().revision
    }), /profile changed/);
    assert.equal(env.active, "2");
    assert.equal(env.targetUpdated, "1", "disable the original profile, not the newly active one");
    assert.equal(env.repositories[0].enabled, false);
    assert.equal(env.providers[0].enabled, false);
  }, { executable:true });
});
