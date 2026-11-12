import { ScreenUtils } from "../../navigation/screen.js";
import { Router } from "../../navigation/routerState.js";
import { Platform } from "../../../platform/index.js";
import { addonRepository } from "../../../data/repository/addonRepository.js";
import { LibrarySyncService } from "../../../core/profile/librarySyncService.js";
import { withRequestTimeout } from "../../../core/network/requestTimeout.js";
import { renderBrandWordmarkImage } from "../../components/brandWordmark.js";
function safe(value) { return String(value || "").replace(/[&<>"']/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[ch]); }
export function normalizeManifestUrl(value) {
  const url = new URL(String(value || "").trim().replace(/^stremio:\/\//i, "https://"));
  if (url.protocol !== "https:" || url.username || url.password || url.hash) throw new Error("Enter an HTTPS manifest URL without embedded credentials");
  if (!url.pathname.endsWith("/manifest.json")) url.pathname = url.pathname.replace(/\/$/, "") + "/manifest.json";
  return url.href;
}
export const PluginScreen = {
  async mount() { this.container = document.getElementById("plugin"); ScreenUtils.show(this.container); this.message = ""; this.render(); },
  async refreshAddons() { await LibrarySyncService.pull(); addonRepository.invalidateInstalledAddonsCache(); this.message = "Add-ons refreshed"; this.render(); },
  async addManifest() {
    const field = this.container.querySelector("input");
    try {
      const url = normalizeManifestUrl(field.value);
      this.message = "Checking manifest…"; this.container.querySelector("[role=status]").textContent = this.message;
      await withRequestTimeout(async signal => {
        const response = await fetch(url, {signal,credentials:"omit",redirect:"error"});
        if (!response.ok) throw new Error("Manifest unavailable. Check the URL and try again.");
        const text = await response.text(); if(text.length > 262144) throw new Error("Manifest is too large");
        const manifest = JSON.parse(text); if (!manifest.id || !manifest.name || !Array.isArray(manifest.resources)) throw new Error("This URL does not contain a valid add-on manifest");
      },15000);
      await addonRepository.addAddon(url); await LibrarySyncService.push(); this.message="Add-on installed"; this.render();
    } catch(error) { this.message=error.message || "Could not add manifest. Check your network."; this.container.querySelector("[role=status]").textContent=this.message; }
  },
  render() {
    const urls=addonRepository.getInstalledAddonUrls();
    this.container.innerHTML=`<main class="starlit-addon-manager">${renderBrandWordmarkImage()}<h1>Your add-ons</h1><p>Add a manifest from a source you are authorized to use. Starlit includes no catalogs or stream providers.</p><div class="starlit-addon-form"><label for="starlit-manifest">Manifest URL</label><input id="starlit-manifest" class="focusable" type="url" autocomplete="off" placeholder="https://your-addon.example/manifest.json" maxlength="4096"><button class="focusable" data-action="add">Add manifest</button></div><p role="status" aria-live="polite">${safe(this.message)}</p><div class="starlit-addon-actions"><button class="focusable" data-action="refresh">Sync add-ons</button><button class="focusable" data-action="catalogs">Arrange home catalogs</button><button class="focusable" data-action="collections">Collections</button><button class="focusable" data-action="back">Back</button></div>${urls.length ? `<ul>${urls.map((url,index)=>`<li><span>${safe(new URL(url).hostname)}</span><button class="focusable" data-index="${index}" data-action="toggle">${addonRepository.isAddonEnabled(url)?"Disable":"Enable"}</button><button class="focusable" data-index="${index}" data-action="remove">Remove</button></li>`).join("")}</ul>` : "<p>No add-ons installed. Add a manifest above, or sync the add-ons from your Starlit account.</p>"}</main>`;
    this.container.querySelectorAll("button").forEach(node=>node.addEventListener("click",()=>this.activate(node)));
    this.focusIndex=0;this.focusCurrent();
  },
  async activate(node) {
    const action=node.dataset.action,urls=addonRepository.getInstalledAddonUrls(),url=urls[Number(node.dataset.index)];
    if(action==="add") return this.addManifest();
    if(action==="back") return Router.back();
    if(action==="refresh") return this.refreshAddons();
    if(action==="catalogs") return Router.navigate("catalogOrder");
    if(action==="collections") return Router.navigate("collections");
    if(action==="toggle" && url) {addonRepository.setAddonEnabledStates([{url,enabled:!addonRepository.isAddonEnabled(url)}]);addonRepository.notifyAddonsChanged("settings");}
    if(action==="remove" && url) await addonRepository.removeAddon(url);
    await LibrarySyncService.push();this.render();
  },
  focusCurrent() { const nodes=[...this.container.querySelectorAll(".focusable")];nodes.forEach(node=>node.classList.remove("focused"));const node=nodes[this.focusIndex];if(node){node.classList.add("focused");node.focus();node.scrollIntoView({block:"nearest"});} },
  async onKeyDown(event) {
    if(Platform.isBackEvent(event)){event.preventDefault();await Router.back();return;}
    const editing=event.target?.tagName==="INPUT";
    if(editing && !["ArrowDown","Enter"].includes(event.key)) return;
    if(["ArrowDown","ArrowRight","ArrowUp","ArrowLeft"].includes(event.key)) {event.preventDefault();const count=this.container.querySelectorAll(".focusable").length;this.focusIndex=Math.max(0,Math.min(count-1,this.focusIndex+(["ArrowDown","ArrowRight"].includes(event.key)?1:-1)));this.focusCurrent();}
    if(event.key==="Enter"){event.preventDefault();if(editing){this.focusIndex=1;this.focusCurrent();}else await this.activate(event.target);}
  },
  cleanup(){ScreenUtils.hide(this.container);this.container.innerHTML="";}
};
