import { AuthManager } from "../auth/authManager.js";
import { ProfileManager } from "./profileManager.js";
import { SupabaseApi } from "../../data/remote/supabase/supabaseApi.js";
import { withRequestTimeout } from "../network/requestTimeout.js";
let linked = null;
let enabled = true;
const updated = new Set();
function profile() { return Number(ProfileManager.getActiveProfileId() || 1); }
function scopeMatches(scope) { return scope.generation === AuthManager.sessionGeneration && scope.profileId === profile() && AuthManager.isAuthenticated; }
export function malPlaybackTarget(progress) {
  const id = /^mal:(\d+)(?::|$)/.exec(String(progress.contentId || ""));
  const position = Number(progress.positionMs), duration = Number(progress.durationMs);
  const episode = Number(progress.episode || (progress.contentType === "movie" ? 1 : 0));
  if (!id || Number(id[1]) < 1 || !Number.isInteger(episode) || episode < 1 || episode > 100000 || !Number.isFinite(position) || !Number.isFinite(duration) || duration <= 0 || position / duration < 0.85) return null;
  return { id: Number(id[1]), episode };
}
async function request(url, options = {}) {
  return withRequestTimeout(async signal => {
    const response = await fetch(url, { ...options, signal, redirect: "error", credentials: "omit", cache: "no-store" });
    if (!response.ok) throw new Error(response.status === 401 ? "MyAnimeList session expired. Reconnect on your linked device." : "MyAnimeList request failed. Try again.");
    const text = await response.text();
    if (text.length > 2097152) throw new Error("MyAnimeList response is too large");
    return text ? JSON.parse(text) : {};
  }, 15000, AuthManager.getSessionSignal());
}
async function api(path, options = {}) {
  if (!linked || !scopeMatches(linked)) throw new Error("Load a linked MyAnimeList account first");
  const scope = linked;
  if (scope.expiresAt && scope.expiresAt < Date.now() + 60000) {
    if (!scope.refreshToken || !scope.clientId) throw new Error("Reconnect MyAnimeList on your linked device");
    const body = new URLSearchParams({ grant_type: "refresh_token", refresh_token: scope.refreshToken, client_id: scope.clientId });
    if (scope.clientSecret) body.set("client_secret", scope.clientSecret);
    const token = await request("https://myanimelist.net/v1/oauth2/token", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: body.toString() });
    if (!scopeMatches(scope) || !token.access_token || !token.refresh_token) throw new Error("MyAnimeList account changed");
    scope.accessToken = token.access_token; scope.refreshToken = token.refresh_token; scope.expiresAt = Date.now() + Number(token.expires_in) * 1000;
    await SupabaseApi.rpc("sync_push_provider_credentials", { p_profile_id: scope.profileId, p_credentials: [{ provider: "myanimelist", credential_json: { client_id: scope.clientId, access_token: scope.accessToken, refresh_token: scope.refreshToken, expires_at: scope.expiresAt, client_secret: scope.clientSecret } }] });
  }
  const result = await request(`https://api.myanimelist.net/v2/${path}`, { ...options, headers: { ...options.headers, Authorization: `Bearer ${scope.accessToken}`, Accept: "application/json" } });
  if (!scopeMatches(scope)) throw new Error("MyAnimeList account changed");
  return result;
}
export const MalTracking = {
  status() { return linked && scopeMatches(linked) ? "Linked to this profile" : "No linked MyAnimeList session loaded"; },
  isEnabled() { return enabled; },
  setEnabled(value) { enabled = Boolean(value); },
  async loadLinked() {
    if (!AuthManager.isAuthenticated) throw new Error("Sign in to Starlit first");
    const scope = { profileId: profile(), generation: AuthManager.sessionGeneration };
    const rows = await SupabaseApi.rpc("sync_pull_provider_credentials", { p_profile_id: scope.profileId });
    const row = (Array.isArray(rows) ? rows : []).find(row => ["myanimelist", "mal"].includes(String(row.provider).toLowerCase()));
    const raw = row?.credential_json;
    let token; try { token = typeof raw === "string" ? JSON.parse(raw) : raw; } catch (_) { token = null; }
    if (!scopeMatches(scope)) return false;
    if (!token?.access_token || !token?.refresh_token || !token?.client_id) throw new Error("No account-linked MyAnimeList credentials. Link and sync MyAnimeList on a supported Starlit device first.");
    linked = { ...scope, accessToken: token.access_token, refreshToken: token.refresh_token, clientId: token.client_id, clientSecret: token.client_secret || "", expiresAt: Number(token.expires_at || 0) };
    await api("users/@me"); return true;
  },
  async list() { return (await api("users/@me/animelist?fields=list_status,num_episodes&sort=list_updated_at&limit=100")).data || []; },
  async setStatus(id, status) {
    if (!Number.isInteger(Number(id)) || Number(id) < 1 || !["watching", "completed", "on_hold", "dropped", "plan_to_watch"].includes(status)) throw new Error("Invalid MyAnimeList update");
    return api(`anime/${id}/my_list_status`, { method: "PATCH", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: new URLSearchParams({ status }).toString() });
  },
  async updatePlayback(progress) {
    const target = malPlaybackTarget(progress);
    if (!enabled || !target || !linked || !scopeMatches(linked) || Number(progress.profileId || profile()) !== profile()) return false;
    const key = `${linked.generation}:${profile()}:${target.id}:${target.episode}`;
    if (updated.has(key)) return false;
    updated.add(key);
    try {
      const current = await api(`anime/${target.id}?fields=num_episodes,my_list_status`);
      const existing = current.my_list_status || {};
      const watched = Math.max(Number(existing.num_episodes_watched || 0), target.episode);
      const status = Number(current.num_episodes) > 0 && watched >= Number(current.num_episodes) ? "completed" : "watching";
      await api(`anime/${target.id}/my_list_status`, { method: "PATCH", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: new URLSearchParams({ num_watched_episodes: String(watched), status }).toString() });
      return true;
    } catch (_) { updated.delete(key); return false; }
  },
  clear() { linked = null; updated.clear(); }
};
AuthManager.registerSessionTeardownListener(() => MalTracking.clear());
