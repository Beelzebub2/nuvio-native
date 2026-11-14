import { I18n } from "../../i18n/index.js";
import { Router } from "../navigation/router.js";
import { focusWithoutScroll } from "../navigation/focusWithoutScroll.js";
import { NuvioDialog } from "./nuvioDialog.js";
import {
  detectTizenUsbStorage,
  TizenUsbTorrentCache
} from "../../platform/tizen/tizenUsbTorrentCache.js";

const DISABLE_OFFERS_KEY = "starlit.tizen.usbTorrentCache.hideOffers.v1";
const SAFE_ROUTES = new Set([
  "home", "library", "search", "discover", "detail", "settings",
  "collections", "collectionDetail", "catalogSeeAll", "plugins"
]);

function translate(key, fallback) {
  return I18n.t(key, {}, { fallback });
}

// Separate the event/offer lifecycle from the dialog renderer so old TVs can
// detect and dismiss USB offers without starting the torrent service.
export function createTizenUsbCacheOfferController({
  usb = TizenUsbTorrentCache,
  detect = detectTizenUsbStorage,
  Dialog = NuvioDialog,
  router = Router,
  documentRef = globalThis.document,
  windowRef = globalThis.window,
  storage = globalThis.localStorage,
  t = translate,
  initialDelayMs = 1200,
  mountDelayMs = 700,
  unmountDelayMs = 350,
  routeRetryMs = 1800
} = {}) {
  let running = false;
  let unsubscribe = null;
  let scanTimer = null;
  let offerTimer = null;
  let scanVersion = 0;
  let pendingDrive = null;
  let dialog = null;
  let displayedLabel = "";
  let mountedLabels = new Set();
  const offeredLabels = new Set();

  function offersDisabled() {
    try { return storage?.getItem(DISABLE_OFFERS_KEY) === "true"; }
    catch (_) { return false; }
  }
  function disableOffers() {
    try { storage?.setItem(DISABLE_OFFERS_KEY, "true"); } catch (_) {}
  }
  function clearOfferTimer() {
    if (offerTimer != null) clearTimeout(offerTimer);
    offerTimer = null;
  }
  function dismissDialog({ restoreFocus = true } = {}) {
    const existing = dialog;
    if (!existing) return;
    const previousFocus = existing.previousFocus;
    const previousRoute = existing.previousRoute;
    dialog = null;
    displayedLabel = "";
    existing.destroy();
    if (restoreFocus && previousFocus && previousRoute === router.getCurrent?.()) {
      setTimeout(() => {
        if (running && documentRef?.body?.contains?.(previousFocus)) {
          focusWithoutScroll(previousFocus);
        }
      }, 240);
    }
  }
  function canShow() {
    if (!documentRef?.body || documentRef.visibilityState === "hidden" ||
        documentRef.webkitHidden === true) return false;
    const route = String(router.getCurrent?.() || "");
    if (!SAFE_ROUTES.has(route)) return false;
    // Never steal focus from an existing dialog, keyboard, or active player.
    return !documentRef.querySelector?.(".nuvio-dialog-backdrop, .modal-overlay, .ambient-mode, [aria-modal='true']");
  }
  function tryOffer() {
    clearOfferTimer();
    if (!running || !pendingDrive || dialog || usb.isEnabled() || offersDisabled()) return;
    if (!canShow()) {
      offerTimer = setTimeout(tryOffer, routeRetryMs);
      return;
    }
    const drive = pendingDrive;
    pendingDrive = null;
    offeredLabels.add(drive.label);
    const previousFocus = documentRef.activeElement;
    const previousRoute = router.getCurrent?.();
    const close = () => dismissDialog();
    const current = new Dialog({
      title: t("usb_cache_offer_title", "USB storage connected"),
      subtitle: t("usb_cache_offer_description",
        "Use this USB drive for temporary torrent caching? Starlit will measure its speed before playback, keep free space available, and fall back to RAM if needed."),
      widthVw: 54.2,
      suppressEnterUntilKeyUp: true,
      buttons: [
        {
          key: "later",
          label: t("usb_cache_offer_later", "Not now"),
          onAction: close
        },
        {
          key: "enable",
          label: t("usb_cache_offer_enable", "Use USB cache"),
          onAction: () => {
            close();
            void Promise.resolve(usb.setEnabled(true)).catch(() => {
              // Storage remains optional: the existing settings page is the
              // place to inspect errors and retry if the preference fails.
            });
          }
        },
        {
          key: "never",
          label: t("usb_cache_offer_never", "Don't ask again"),
          onAction: () => {
            disableOffers();
            close();
          }
        }
      ],
      onDismiss: () => {
        if (dialog === current) dismissDialog();
      }
    });
    current.previousFocus = previousFocus;
    current.previousRoute = previousRoute;
    dialog = current;
    displayedLabel = drive.label;
    current.mount(documentRef.body);
  }
  async function checkNow() {
    if (!running || usb.isEnabled() || offersDisabled()) return;
    const version = ++scanVersion;
    let available;
    try { available = await detect(); }
    catch (_) { return; }
    if (!running || version !== scanVersion || usb.isEnabled() || offersDisabled()) return;
    const drives = Array.isArray(available?.drives) ? available.drives : [];
    const nextLabels = new Set(drives.map((drive) => String(drive.label || "")));
    for (const label of mountedLabels) {
      if (!nextLabels.has(label)) offeredLabels.delete(label);
    }
    mountedLabels = nextLabels;
    if (dialog && !nextLabels.has(displayedLabel)) dismissDialog({ restoreFocus: false });
    if (pendingDrive && !nextLabels.has(pendingDrive.label)) pendingDrive = null;
    if (!pendingDrive && !dialog) {
      const drive = drives.find((entry) =>
        String(entry.label || "") && !offeredLabels.has(String(entry.label))
      );
      if (drive) pendingDrive = { label: String(drive.label), totalBytes: drive.totalBytes };
    }
    if (pendingDrive) tryOffer();
    else clearOfferTimer();
  }
  function scheduleScan(delay = 500) {
    if (!running || usb.isEnabled() || offersDisabled()) return;
    if (scanTimer != null) clearTimeout(scanTimer);
    scanTimer = setTimeout(() => {
      scanTimer = null;
      void checkNow();
    }, delay);
  }
  function onStorageChange(storageInfo) {
    if (!running || storageInfo?.type !== "EXTERNAL") return;
    ++scanVersion;
    const label = String(storageInfo.label || "");
    if (storageInfo.state !== "MOUNTED") {
      if (label) {
        offeredLabels.delete(label);
        mountedLabels.delete(label);
      }
      if (pendingDrive?.label === label) pendingDrive = null;
      if (dialog && displayedLabel === label) dismissDialog({ restoreFocus: false });
    }
    scheduleScan(storageInfo.state === "MOUNTED" ? mountDelayMs : unmountDelayMs);
  }
  const onForeground = () => {
    if (documentRef.visibilityState === "hidden" || documentRef.webkitHidden === true) return;
    // The TV may miss an unmount notification while suspended. Cancel any
    // deferred offer until a fresh mounted-drive scan has completed.
    ++scanVersion;
    clearOfferTimer();
    pendingDrive = null;
    scheduleScan(0);
  };
  return {
    start() {
      if (running) return;
      running = true;
      unsubscribe = usb.subscribeStorageChanges(onStorageChange);
      documentRef?.addEventListener?.("visibilitychange", onForeground);
      windowRef?.addEventListener?.("pageshow", onForeground);
      scheduleScan(initialDelayMs);
    },
    checkNow,
    stop() {
      if (!running) return;
      running = false;
      scanVersion++;
      unsubscribe?.();
      unsubscribe = null;
      if (scanTimer != null) clearTimeout(scanTimer);
      scanTimer = null;
      clearOfferTimer();
      documentRef?.removeEventListener?.("visibilitychange", onForeground);
      windowRef?.removeEventListener?.("pageshow", onForeground);
      pendingDrive = null;
      dismissDialog({ restoreFocus: false });
    }
  };
}

let activeController = null;
export function startTizenUsbCacheOffers() {
  if (!activeController) activeController = createTizenUsbCacheOfferController();
  activeController.start();
  return activeController;
}
