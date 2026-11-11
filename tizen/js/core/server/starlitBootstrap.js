import { SUPABASE_URL } from "../../config.js";
import { discoverServer } from "./serverDiscovery.js";
import { ServerConfigurationStore } from "../../data/local/serverConfigurationStore.js";
export async function initializeStarlitServer() {
  const current = ServerConfigurationStore.getActive();
  if (current.isCustom) return;
  try {
    const configuration = await discoverServer(SUPABASE_URL, { timeoutMs: 8000 });
    if (new URL(configuration.backendUrl).origin !== new URL(SUPABASE_URL).origin) return;
    ServerConfigurationStore.setDiscoveredOfficial({ ...configuration, isCustom: false,
      capabilities: { ...configuration.capabilities, watchParty: false } });
  } catch (_) { /* Guest playback and cached account state remain available offline. */ }
}
