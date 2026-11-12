import { ExperienceModeStore } from "../../../data/local/experienceModeStore.js";
import { ProfileManager } from "../../../core/profile/profileManager.js";
import { ProfileSettingsSyncService } from "../../../core/profile/profileSettingsSyncService.js";
import { I18n } from "../../../i18n/index.js";
import { Router } from "../../navigation/routerState.js";
import { ScreenUtils } from "../../navigation/screen.js";
import { renderBrandWordmarkImage } from "../../components/brandWordmark.js";
function t(key, fallback) {
  return I18n.t(key, {}, { fallback });
}
function escapeHtml(value = "") {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}
export const EssentialAddonSetupScreen = {
  async mount() {
    this.container = document.getElementById("essentialAddonSetup");
    this.returningToMode = false;
    ScreenUtils.show(this.container);
    this.container.innerHTML = `
      <main class="experience-mode-screen essential-addon-setup">
        ${renderBrandWordmarkImage({ className: "experience-mode-logo" })}
        <h1>${escapeHtml(t("essential_addon_setup_title", "Set up your add-ons"))}</h1>
        <p>${escapeHtml(t("essential_addon_setup_subtitle", "Add a manifest URL manually now, or skip and configure add-ons later from Settings."))}</p>
        <div class="experience-mode-options">
          <button class="experience-mode-card focusable" data-index="0" data-action="addons"><strong>${escapeHtml(t("addon_manage_from_phone_title", "Manage from phone"))}</strong><span>${escapeHtml(t("addon_manage_addons_only_from_phone_subtitle", "Scan a QR code to install or remove add-ons from your phone"))}</span></button>
          <button class="experience-mode-card focusable" data-index="1" data-action="skip"><strong>${escapeHtml(t("essential_addon_continue_for_now", "Continue for now"))}</strong><span>${escapeHtml(t("essential_addon_setup_subtitle", "You can add them later from Settings."))}</span></button>
        </div>
      </main>`;
    this.onClickBound = this.onClick.bind(this);
    // FocusEngine is the single capture-phase remote-key dispatcher.
    // Another document listener would advance focus/activate twice per press.
    this.container.addEventListener("click", this.onClickBound);
    ScreenUtils.setInitialFocus(this.container);
  },
  async finish(skipped) {
    const profileId = ProfileManager.getActiveProfileId();
    ExperienceModeStore.setForProfile(profileId, { addonSetupSkipped: skipped });
    await ProfileSettingsSyncService.push(profileId);
    if (skipped) {
      await Router.navigate(
        "home",
        { forceReload: true },
        { replaceHistory: true, skipStackPush: true }
      );
    } else {
      await Router.navigate("plugin", { essentialSetup: true });
    }
  },
  async onClick(event) {
    const action = event.target.closest("[data-action]")?.dataset.action;
    if (action === "addons") await this.finish(false);
    if (action === "skip") await this.finish(true);
  },
  onKeyDown(event) {
    const code = Number(event?.keyCode || event?.which || 0);
    const left = code === 37 || event?.key === "ArrowLeft" || event?.key === "Left";
    const right = code === 39 || event?.key === "ArrowRight" || event?.key === "Right";
    if (left || right) {
      event.preventDefault();
      ScreenUtils.moveFocus(this.container, left ? -1 : 1);
    } else if (code === 13 || event?.key === "Enter" || event?.key === "Return") {
      event.preventDefault();
      if (!event?.repeat) {
        this.container.querySelector(".focusable.focused")?.click();
      }
    }
  },
  consumeBackRequest() {
    // This onboarding route replaces the mode-selection history entry.
    // Samsung Back should let the viewer revise that choice, not exit.
    if (this.returningToMode) return true;
    this.returningToMode = true;
    Promise.resolve()
      .then(() => Router.navigate(
        "experienceModeSelection",
        {},
        { replaceHistory: true, skipStackPush: true }
      ))
      .catch((error) => console.warn("Could not return to experience selection", error))
      .then(() => { this.returningToMode = false; });
    return true;
  },
  cleanup() {
    this.container?.removeEventListener("click", this.onClickBound);
    ScreenUtils.hide(this.container);
  }
};
