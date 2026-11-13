import { I18n } from "../../../i18n/index.js";
// The banner eyebrow: what the focused title is and which row it sits in,
// e.g. "SERIES · CONTINUE WATCHING". Upper-casing is left to the stylesheet.
//
// Both parts live in attributes and are drawn by the stylesheet. Home patches
// its DOM by diffing generated markup (homeDomUpdate.js), and text written into
// the live node would be merged with the next render's text.
const TYPE_LABEL_KEYS = Object.freeze({
  movie: ["type_movie", "Movie"],
  series: ["type_series", "Series"],
  tv: ["type_series", "Series"],
  anime: ["type_series", "Series"]
});
export function getHeroTypeLabel(type) {
  const entry = TYPE_LABEL_KEYS[String(type || "").trim().toLowerCase()];
  return entry ? I18n.t(entry[0], {}, { fallback: entry[1] }) : "";
}
export function buildHeroEyebrowText({ type = "", rowTitle = "" } = {}) {
  return [getHeroTypeLabel(type), String(rowTitle || "").trim()].filter(Boolean).join(" · ");
}
/** Row title of the focused card (or of `focusedNode`), or "" outside a row. */
export function getFocusedRowTitle(container, focusedNode = null) {
  const focused = focusedNode || container?.querySelector?.(".home-content-card.focused");
  const title = focused?.closest?.(".home-row")?.querySelector?.(".home-row-title");
  return String(title?.textContent || "").trim();
}
export function syncHeroEyebrow(container, hero, focusedNode = null) {
  const node = container?.querySelector?.(".home-hero-eyebrow");
  if (!node) {
    return;
  }
  const type = hero?.type || hero?.apiType || "";
  const typeLabel = getHeroTypeLabel(type);
  const rowTitle = getFocusedRowTitle(container, focusedNode);
  if (node.getAttribute("data-type-label") !== typeLabel) {
    node.setAttribute("data-type-label", typeLabel);
  }
  if (rowTitle) {
    if (node.getAttribute("data-row-title") !== rowTitle) {
      node.setAttribute("data-row-title", rowTitle);
    }
  } else if (node.hasAttribute("data-row-title")) {
    node.removeAttribute("data-row-title");
  }
  node.setAttribute("aria-label", buildHeroEyebrowText({ type, rowTitle }));
}
