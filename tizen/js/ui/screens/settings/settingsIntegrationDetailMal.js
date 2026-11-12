import { MalTracking } from "../../../core/profile/starlitMalTracking.js";
function safe(value) { return String(value || "").replace(/[&<>"']/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[ch]); }
export function renderMalIntegrationDetail() {
  const showError = error => { this.malStatus = error.message; };
  this.actionMap.set("integration:mal:load", async () => { try { await MalTracking.loadLinked(); this.malStatus = "MyAnimeList connected"; } catch(error) { showError(error); } });
  this.actionMap.set("integration:mal:enabled", () => MalTracking.setEnabled(!MalTracking.isEnabled()));
  this.actionMap.set("integration:mal:clear", () => { MalTracking.clear(); this.malStatus = "MyAnimeList cleared from this TV"; });
  this.actionMap.set("integration:mal:list", async () => {
    try {
      const rows = await MalTracking.list();
      this.openOptionDialog({ title: "MyAnimeList", returnFocusKey: "integration:mal:list", options: rows.map(row => ({ id: String(row.node.id), label: row.node.title })), onSelect: item => {
        this.openOptionDialog({ title: item.label, returnFocusKey: "integration:mal:list", options: ["watching", "completed", "on_hold", "dropped", "plan_to_watch"].map(id => ({id,label:id.replace(/_/g," ")})), onSelect: async choice => { try { await MalTracking.setStatus(Number(item.id),choice.id); this.malStatus="MyAnimeList updated"; } catch(error) { showError(error); } } });
        return false;
      } });
    } catch(error) { showError(error); }
  });
  return `<h1 class="settings-section-title">MyAnimeList</h1><p>${safe(this.malStatus || MalTracking.status())}</p><p>Load account-linked credentials, then manage your anime list. Playback updates only confirmed MyAnimeList IDs after 85% watched.</p><div class="settings-stack">${this.renderActionRow({focusKey:"integration:back",title:"Back to integrations"})}${this.renderActionRow({focusKey:"integration:mal:load",title:"Load linked MyAnimeList account",subtitle:"Uses this Starlit profile’s private provider credentials"})}${this.renderActionRow({focusKey:"integration:mal:list",title:"Manage anime list"})}${this.renderToggleRow({focusKey:"integration:mal:enabled",title:"Update watched episodes",checked:MalTracking.isEnabled()})}${this.renderActionRow({focusKey:"integration:mal:clear",title:"Clear MyAnimeList from this TV",subtitle:"Keeps your account connection on other devices"})}</div>`;
}
