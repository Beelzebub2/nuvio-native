import {
  DisplaySettingsStore,
  INTERFACE_SIZE_OPTIONS,
  OVERSCAN_OPTIONS,
  TEXT_SIZE_OPTIONS
} from "../../../data/local/displaySettingsStore.js";
import { ThemeStore } from "../../../data/local/themeStore.js";
import { I18n } from "../../../i18n/index.js";
import { DisplayManager } from "../../theme/displayManager.js";
import { availableThemeIds } from "../../theme/themeAccess.js";
import { ThemeColors } from "../../theme/themeColors.js";
import { ThemeManager } from "../../theme/themeManager.js";
import { THEME_OPTIONS } from "./settingsScreenHelpers-01-settings-ui-state-key.js";
import { SECTION_META } from "./settingsScreenHelpers-03-clamp-subtitle-text-opacity.js";
// Settings > Display & scaling.
//
// Each setting is one focusable row: Left/Right step through its values and OK
// cycles them, so the whole section is usable with the D-pad alone and focus
// never has to dive into a nested control. Values apply immediately; the
// canvas is re-laid out by DisplayManager through the store subscription.
const t = (key, fallback, params = []) => I18n.t(key, params, { fallback });
function escapeHtml(value) {
  return String(value == null ? "" : value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}
const INTERFACE_SIZE_LABELS = {
  auto: ["display_interface_size_auto", "Auto"],
  compact: ["display_interface_size_compact", "Compact 90%"],
  standard: ["display_interface_size_standard", "Standard"],
  large: ["display_interface_size_large", "Large 115%"]
};
const TEXT_SIZE_LABELS = {
  default: ["display_text_size_default", "Default"],
  large: ["display_text_size_large", "Large"],
  xlarge: ["display_text_size_xlarge", "Extra large"]
};
function overscanLabel(value) {
  if (!value) {
    return t("display_screen_edges_off", "Off");
  }
  return `${Math.round(value * 1000) / 10}%`;
}
/** Next option for a step; `wrap` cycles (OK), otherwise stops at the ends. */
export function stepOption(options, current, delta, wrap = false) {
  const index = Math.max(0, options.indexOf(current));
  const next = index + delta;
  if (wrap) {
    return options[(next + options.length) % options.length];
  }
  return next < 0 || next >= options.length ? null : options[next];
}
export function formatDetectedDisplay(info) {
  const sharpness = Number(info?.sharpness) || 1;
  const sharpText = String(Math.round(sharpness * 100) / 100);
  return t(
    "display_detected",
    "Detected: %1$s × %2$s screen · interface drawn at %3$s × %4$s, %5$s× sharp",
    [info.screenWidth, info.screenHeight, info.canvasWidth, info.canvasHeight, sharpText]
  );
}
const ICONS = {
  monitor:
    '<svg class="tv-display-detected-icon" width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="4" width="18" height="12" rx="2"></rect><path d="M8 20h8M12 16v4"></path></svg>',
  previous:
    '<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m15 5-7 7 7 7"></path></svg>',
  next:
    '<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m9 5 7 7-7 7"></path></svg>'
};
function renderRow(screen, { focusKey, title, subtitle, control, role = "adjust", checked = null }) {
  const aria = checked === null ? "" : ` role="switch" aria-checked="${checked ? "true" : "false"}"`;
  return `
    <button class="settings-action-row settings-content-focusable focusable tv-display-row"
            data-zone="content"
            ${screen.registerAction(focusKey, screen.actionMap.get(focusKey))}
            data-adjust-key="${escapeHtml(focusKey)}"
            data-role="${role}"${aria}>
      <span class="settings-row-copy">
        <span class="settings-row-title">${escapeHtml(title)}</span>
        <span class="settings-row-subtitle">${escapeHtml(subtitle)}</span>
      </span>
      <span class="settings-row-tail tv-display-control">${control}</span>
    </button>`;
}
export function renderDisplaySection(model) {
  const settings = DisplaySettingsStore.get();
  const mount = this.settingsMountToken;
  const current = () =>
    this.isMounted && this.activeSection === "display" && this.settingsMountToken === mount;
  // Left/Right handlers: return true when the key was consumed.
  if (!this.adjustMap) {
    this.adjustMap = new Map();
  }
  const bindChoice = (focusKey, options, read, write) => {
    this.actionMap.set(focusKey, () => {
      if (current()) write(stepOption(options, read(), 1, true));
    });
    this.adjustMap.set(focusKey, (delta) => {
      if (!current()) return false;
      const next = stepOption(options, read(), delta);
      if (next === null) return false;
      write(next);
      return true;
    });
  };
  bindChoice(
    "display:interfaceSize",
    INTERFACE_SIZE_OPTIONS,
    () => DisplaySettingsStore.get().interfaceSize,
    (interfaceSize) => DisplaySettingsStore.set({ interfaceSize })
  );
  bindChoice(
    "display:overscan",
    OVERSCAN_OPTIONS,
    () => DisplaySettingsStore.get().overscan,
    (overscan) => DisplaySettingsStore.set({ overscan })
  );
  bindChoice(
    "display:textSize",
    TEXT_SIZE_OPTIONS,
    () => DisplaySettingsStore.get().textSize,
    (textSize) => DisplaySettingsStore.set({ textSize })
  );
  this.actionMap.set("display:reduceMotion", () => {
    if (current()) {
      DisplaySettingsStore.set({ reduceMotion: !DisplaySettingsStore.get().reduceMotion });
    }
  });
  // Only the palettes this account can use: supporter themes stay behind
  // their entitlement, exactly as in Appearance. The custom palette is edited
  // there too, so it is not offered as a one-press swatch.
  const availableIds = new Set(availableThemeIds(model?.memberAccess));
  const themes = THEME_OPTIONS.filter((theme) => availableIds.has(theme.id) && theme.id !== "CUSTOM");
  const activeThemeId = String(model?.theme?.themeName || "").toUpperCase();
  const themeIds = themes.map((theme) => theme.id);
  bindChoice(
    "display:theme",
    themeIds,
    () => String(ThemeStore.get().themeName || "").toUpperCase(),
    (themeId) => {
      const theme = themes.find((entry) => entry.id === themeId);
      if (!theme) return;
      ThemeStore.set({ themeName: theme.id, accentColor: theme.color });
      ThemeManager.apply({ enforceAccess: true, access: model?.memberAccess });
    }
  );
  const activeTheme = themes.find((theme) => theme.id === activeThemeId) || null;
  const activeThemeLabel = activeTheme
    ? I18n.t(activeTheme.labelKey, {}, { fallback: activeTheme.id })
    : t("settings.appearance.themes.custom", "Custom");
  const segmented = INTERFACE_SIZE_OPTIONS.map((option) => {
    const [key, fallback] = INTERFACE_SIZE_LABELS[option];
    return `<span class="tv-segment${settings.interfaceSize === option ? " is-selected" : ""}">${escapeHtml(t(key, fallback))}</span>`;
  }).join("");
  const overscanIndex = OVERSCAN_OPTIONS.indexOf(settings.overscan);
  const stepper = `
    <span class="tv-stepper-arrow${overscanIndex <= 0 ? " is-disabled" : ""}">${ICONS.previous}</span>
    <span class="tv-stepper-value">${escapeHtml(overscanLabel(settings.overscan))}</span>
    <span class="tv-stepper-arrow${overscanIndex >= OVERSCAN_OPTIONS.length - 1 ? " is-disabled" : ""}">${ICONS.next}</span>`;
  const [textKey, textFallback] = TEXT_SIZE_LABELS[settings.textSize] || TEXT_SIZE_LABELS.default;
  const textControl = `<span class="tv-stepper-value">${escapeHtml(t(textKey, textFallback))}</span><span class="tv-stepper-arrow">${ICONS.next}</span>`;
  const toggle = `<span class="settings-toggle-pill${settings.reduceMotion ? " is-checked" : ""}"><span class="settings-toggle-thumb"></span></span>`;
  const swatches = themes
    .map((theme) => {
      const palette = ThemeColors.getPalette(theme.id);
      const selected = theme.id === activeThemeId;
      return `<span class="tv-theme-swatch${selected ? " is-selected" : ""}" style="background:${escapeHtml(palette["--secondary-color"] || theme.color)}"></span>`;
    })
    .join("");
  return `
    ${this.renderSectionHeader(SECTION_META.find((section) => section.id === "display"))}
    <div class="settings-stack tv-display-section">
      <div class="tv-display-detected">${ICONS.monitor}<span>${escapeHtml(formatDetectedDisplay(DisplayManager.describe()))}</span></div>
      ${renderRow(this, {
        focusKey: "display:interfaceSize",
        title: t("display_interface_size_title", "Interface size"),
        subtitle: t("display_interface_size_subtitle", "Auto matches the layout to this screen"),
        control: `<span class="tv-segmented">${segmented}</span>`
      })}
      ${renderRow(this, {
        focusKey: "display:overscan",
        title: t("display_screen_edges_title", "Screen edges"),
        subtitle: t("display_screen_edges_subtitle", "Pull everything inward if the menu or clock is cut off"),
        control: stepper
      })}
      ${renderRow(this, {
        focusKey: "display:textSize",
        title: t("display_text_size_title", "Text size"),
        subtitle: t("display_text_size_subtitle", "Larger titles, synopses and source details"),
        control: textControl
      })}
      ${renderRow(this, {
        focusKey: "display:reduceMotion",
        title: t("display_lighter_animations_title", "Lighter animations"),
        subtitle: t(
          "display_lighter_animations_subtitle",
          "Smoother on older TVs: no zoom on focus, no backdrop fades"
        ),
        control: toggle,
        role: "toggle",
        checked: settings.reduceMotion
      })}
      ${
        themes.length
          ? renderRow(this, {
              focusKey: "display:theme",
              title: t("display_theme_title", "Theme"),
              subtitle: t(
                "display_theme_subtitle",
                "%1$s · same palettes as Starlit on desktop and phone",
                [activeThemeLabel]
              ),
              control: `<span class="tv-theme-swatches">${swatches}</span>`
            })
          : ""
      }
    </div>`;
}
