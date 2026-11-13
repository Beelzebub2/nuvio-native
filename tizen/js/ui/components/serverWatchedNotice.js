import { getTvCanvasRoot } from "../../platform/tvViewport.js";
import { I18n } from "../../i18n/index.js";
let timer = null;
document.addEventListener("nuvio:server-watched-failed", () => {
  let notice = document.getElementById("server-watched-notice");
  if (!notice) {
    notice = document.createElement("div");
    notice.id = "server-watched-notice";
    notice.className = "server-watched-notice";
    notice.setAttribute("role", "status");
    getTvCanvasRoot().appendChild(notice);
  }
  notice.textContent = I18n.t("servers_watched_failed");
  clearTimeout(timer);
  timer = setTimeout(() => notice.remove(), 4500);
});
