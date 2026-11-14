import { addonRepository } from "../../data/repository/addonRepository.js";
import { LibrarySyncService } from "../profile/librarySyncService.js";
import { ProfileManager } from "../profile/profileManager.js";
import { PluginManager } from "../player/pluginManager.js";
import { isVideoEasyScraper } from "../player/pluginModels.js";
import { PluginStore, getEffectivePluginProfileId } from "../../data/local/pluginStore.js";

// A paired phone may request only well-typed actions. The television remains
// authoritative for permissions and installation; the relay never fetches or
// executes third-party manifests and never sees credentials or account tokens.
export function validateExtensionUrl(input, { addon = false } = {}) {
  if (typeof input !== "string" || input.length > 4096)
    throw new Error("Invalid repository address");
  const raw = input.trim();
  const url = new URL(raw);
  if (url.protocol !== "https:" || !url.hostname || url.username || url.password ||
      url.hash || url.port || url.hostname.includes(":") ||
      /^(localhost|.*\.(?:local|localhost|internal|lan|home))$/i.test(url.hostname) ||
      /^\d+(?:\.\d+){3}$/.test(url.hostname))
    throw new Error("Only public HTTPS addresses are supported");
  if (addon && !url.pathname.toLowerCase().endsWith("/manifest.json"))
    url.pathname = url.pathname.replace(/\/$/, "") + "/manifest.json";
  return url.href;
}

export async function readExtensionManifest(url, { addon = false, fetchImpl = fetch } = {}) {
  const normalized = validateExtensionUrl(url, { addon });
  let timer = null;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error("Repository did not respond in time")), 12000);
  });
  try {
    const response = await Promise.race([
      fetchImpl(normalized, { credentials: "omit", redirect: "error", cache: "no-store" }),
      timeout
    ]);
    if (!response?.ok) throw new Error("Repository manifest unavailable");
    const declaredSize = Number(response.headers?.get?.("content-length") || 0);
    if (declaredSize > 262144) throw new Error("Manifest exceeds size limit");
    const text = await Promise.race([response.text(), timeout]);
    if (text.length > 262144) throw new Error("Manifest exceeds size limit");
    const manifest = JSON.parse(text);
    if (!manifest || typeof manifest !== "object" || Array.isArray(manifest) ||
        typeof manifest.name !== "string" || !manifest.name.trim() ||
        (addon ? !Array.isArray(manifest.resources) || !manifest.id : !Array.isArray(manifest.scrapers)))
      throw new Error("Invalid repository manifest");
    return {
      url: normalized, name: manifest.name.slice(0, 100),
      description: typeof manifest.description === "string" ? manifest.description.slice(0, 250) : "",
      items: (addon ? manifest.resources : manifest.scrapers)
        .slice(0, 128).map(item => typeof item === "string" ? item : item?.name || item?.id || "")
        .map(item => String(item).slice(0, 80))
    };
  } finally {
    clearTimeout(timer);
  }
}

const nameFromUrl = raw => {
  try { return new URL(raw).hostname; } catch { return "Add-on"; }
};

export function tvSetupState(revision = 0) {
  const addons = addonRepository.getInstalledAddonUrls();
  const summary = PluginManager.getSummary();
  const capability = summary.runtime || {};
  return {
    profile: String(ProfileManager.getActiveProfileId()),
    canEditAddons: addonRepository.canEdit(),
    canEditPlugins: !summary.readOnly,
    pluginExecutable: capability.executable === true,
    pluginReason: capability.executable ? "" : String(capability.reason || "Plugin runtime unavailable"),
    addons: addons.map((url, index) => ({
      id: "addon:" + index, name: nameFromUrl(url),
      enabled: addonRepository.isAddonEnabled(url)
    })),
    repositories: (summary.repositories || []).map((repo, index) => ({
      id: "repo:" + index, name: String(repo.name || "Plugin repository").slice(0, 80),
      enabled: repo.enabled !== false, type: repo.type
    })),
    providers: (summary.scrapers || []).map((provider, index) => ({
      id: "provider:" + index, name: String(provider.name || "Provider").slice(0, 80),
      enabled: provider.enabled !== false
    })),
    revision
  };
}
function targetIndex(target, prefix, length) {
  const match = new RegExp("^" + prefix + ":(0|[1-9][0-9]{0,3})$").exec(String(target || ""));
  if (!match || Number(match[1]) >= length) throw new Error("Item has changed. Refresh the phone.");
  return Number(match[1]);
}

export function createTvSetupExecutor(profileId) {
  const previewed = new Map();
  let revision = 0;
  let snapshot = "";
  const current = () => {
    if (String(ProfileManager.getActiveProfileId()) !== String(profileId))
      throw new Error("TV profile changed; pair again");
    const state = tvSetupState(revision);
    const fingerprint = JSON.stringify({
      a: addonRepository.getInstalledAddonUrls(),
      e: addonRepository.getAddonEnabledStates(),
      r: PluginManager.listRepositories().map(x => [x.id, x.url, x.enabled]),
      p: PluginManager.listScrapers().map(x => [x.id, x.enabled])
    });
    if (snapshot && snapshot !== fingerprint) revision++;
    snapshot = fingerprint;
    state.revision = revision;
    return state;
  };
  return {
    state: current,
    async execute(command) {
      const state = current();
      if (!command || !Number.isSafeInteger(command.id))
        throw new Error("Invalid command");
      if (command.revision !== state.revision)
        throw new Error("List changed. Refresh the phone.");
      const op = String(command.op || "");
      const addonEdit = new Set(["installAddon", "removeAddon", "setAddonEnabled", "refresh"]);
      const pluginEdit = new Set(["installPlugin", "removePlugin", "setRepositoryEnabled", "setProviderEnabled"]);
      if (addonEdit.has(op) && !state.canEditAddons) throw new Error("This profile is read-only");
      if (pluginEdit.has(op) && !state.canEditPlugins) throw new Error("This profile is read-only");
      if ((op.endsWith("Plugin") || op === "setRepositoryEnabled" || op === "setProviderEnabled") &&
          !state.pluginExecutable) throw new Error("Plugins are unsupported on this TV");
      if (op === "previewAddon" || op === "previewPlugin") {
        const addon = op === "previewAddon";
        if (!addon && !state.pluginExecutable) throw new Error("Plugins are unsupported on this TV");
        const result = await readExtensionManifest(command.url, { addon });
        previewed.set(addon ? "addon" : "plugin", {
          url: result.url, timestamp: Date.now()
        });
        return { message: "Repository ready for review", detail: result };
      }
      if (op === "installAddon" || op === "installPlugin") {
        const addon = op === "installAddon";
        const url = validateExtensionUrl(command.url, { addon });
        const prior = previewed.get(addon ? "addon" : "plugin");
        if (!prior || prior.url !== url || Date.now() - prior.timestamp > 5 * 60_000)
          throw new Error("Preview this repository before installing");
        // Re-fetch and validate on the TV instead of trusting a preview sent
        // by the phone, and prevent a profile switch across network awaits.
        await readExtensionManifest(url, { addon });
        current();
        if (addon) {
          await addonRepository.addAddon(url);
          await LibrarySyncService.push();
        } else {
          const targetPluginProfile = getEffectivePluginProfileId(profileId);
          const repo = await PluginManager.addRepository(url);
          // addRepository may finish after the user switches profiles. Never
          // leave downloaded third-party providers enabled by default in the
          // original profile, even when this pairing can no longer continue.
          // Update the captured effective profile atomically, before any
          // active-profile check or further awaited work.
          PluginStore.update((latest) => ({
            ...latest,
            repositories: latest.repositories.map((entry) =>
              entry.id === repo.id ? { ...entry, enabled: false } : entry),
            scrapers: latest.scrapers.map((entry) =>
              entry.repositoryId === repo.id ? { ...entry, enabled: false } : entry),
            syncDirty: true
          }), targetPluginProfile);
          current();
        }
        previewed.delete(addon ? "addon" : "plugin");
        revision++;
        return { message: "Installed on TV" };
      }
      if (op === "refresh") {
        addonRepository.invalidateInstalledAddonsCache();
        revision++;
        return { message: "TV list refreshed" };
      }
      if (op === "removeAddon" || op === "setAddonEnabled") {
        const urls = addonRepository.getInstalledAddonUrls();
        const url = urls[targetIndex(command.target, "addon", urls.length)];
        if (op === "removeAddon") await addonRepository.removeAddon(url);
        else {
          if (typeof command.enabled !== "boolean") throw new Error("Invalid state");
          addonRepository.setAddonEnabledStates([{ url, enabled: command.enabled }]);
          addonRepository.notifyAddonsChanged("settings");
        }
        await LibrarySyncService.push();
      } else if (op === "removePlugin" || op === "setRepositoryEnabled") {
        const repos = PluginManager.listRepositories();
        const repo = repos[targetIndex(command.target, "repo", repos.length)];
        if (op === "removePlugin") await PluginManager.removeRepository(repo.id);
        else {
          if (typeof command.enabled !== "boolean") throw new Error("Invalid state");
          PluginManager.setRepositoryEnabled(repo.id, command.enabled);
        }
      } else if (op === "setProviderEnabled") {
        const providers = PluginManager.listScrapers();
        const provider = providers[targetIndex(command.target, "provider", providers.length)];
        if (typeof command.enabled !== "boolean") throw new Error("Invalid state");
        // Preserve the existing TV's manual confirmation path for providers
        // that are known to be unstable on some content.
        if (command.enabled && isVideoEasyScraper(provider.id, provider.name, provider.filename))
          throw new Error("Enable this provider on the TV after reviewing its warning");
        if (!PluginManager.setScraperEnabled(provider.id, command.enabled))
          throw new Error("This provider cannot be changed");
      } else throw new Error("Unsupported command");
      revision++;
      return { message: "Updated on TV" };
    }
  };
}
