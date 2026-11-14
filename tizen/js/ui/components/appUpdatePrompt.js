import { I18n } from "../../i18n/index.js";
import { QrCodeGenerator } from "../../core/qr/qrCodeGenerator.js";
import { NuvioDialog } from "./nuvioDialog.js";
function t(key, fallback) {
  return I18n.t(key, {}, { fallback });
}
function normalizeReleaseNoteLine(rawLine) {
  return String(rawLine || "")
    .replace(/!\[([^\]]*)\]\([^)]+\)/g, "$1")
    .replace(/\[([^\]]+)\]\([^)]+\)/g, "$1")
    .replace(/(\*\*|__|\*|_|~~|`)/g, "")
    .trim();
}
export function buildReleaseNotesContent(notes, documentRef = globalThis.document) {
  const container = documentRef.createElement("div");
  container.className = "app-update-notes";
  container.setAttribute("tabindex", "-1");
  const lines = String(notes || "")
    .replace(/\r\n?/g, "\n")
    .split("\n");
  let renderedLineCount = 0;
  lines.forEach((rawLine) => {
    const trimmed = String(rawLine || "").trim();
    if (!trimmed) {
      return;
    }
    let className = "app-update-notes-paragraph";
    let content = trimmed;
    if (/^#{1,3}\s+/.test(trimmed)) {
      className = "app-update-notes-heading";
      content = trimmed.replace(/^#{1,3}\s+/, "");
    } else if (/^[-*+]\s+/.test(trimmed)) {
      className = "app-update-notes-bullet";
      content = trimmed.replace(/^[-*+]\s+/, "");
    }
    const line = documentRef.createElement("div");
    line.className = className;
    line.textContent = normalizeReleaseNoteLine(content);
    container.appendChild(line);
    renderedLineCount += 1;
  });
  if (renderedLineCount === 0) {
    const fallback = documentRef.createElement("div");
    fallback.className = "app-update-notes-paragraph";
    fallback.textContent = t(
      "update_release_notes_unavailable",
      "No release description is available."
    );
    container.appendChild(fallback);
  }
  return container;
}
export function showAppUpdatePrompt(update, { documentRef = globalThis.document, onClose } = {}) {
  if (!update || !documentRef?.body) {
    return null;
  }
  const content = documentRef.createElement("div");
  content.className = "app-update-content";
  const version = documentRef.createElement("div");
  version.className = "app-update-version";
  version.textContent = /^[vV]/.test(update.tag) ? update.tag : `v${update.tag}`;
  content.appendChild(version);
  const notesLabel = documentRef.createElement("div");
  notesLabel.className = "app-update-notes-label";
  notesLabel.textContent = t("update_release_notes", "What's new");
  content.appendChild(notesLabel);
  const notes = buildReleaseNotesContent(update.notes, documentRef);
  content.appendChild(notes);
  // The public app cannot call tizen.package.install: that requires a
  // platform-level privilege. Offer a scannable official release URL so a
  // sideloaded installation can be updated by Apps2Samsung on a phone/PC.
  if (/^https:\/\/github\.com\/Beelzebub2\/(?:nuvio-native|starlit)\/releases\/tag\/v\d+\.\d+\.\d+$/.test(update.releaseUrl || "")) {
    content.classList.add("app-update-has-qr");
    const instructions = documentRef.createElement("div");
    instructions.className = "app-update-installer-hint";
    instructions.textContent = t(
      "update_via_phone",
      "Scan to open this release on your phone, then update the TV using Apps2Samsung."
    );
    content.appendChild(instructions);
    const qr = documentRef.createElement("canvas");
    qr.className = "app-update-qr";
    qr.setAttribute("aria-label", "Official Starlit TV release QR code");
    content.appendChild(qr);
    void QrCodeGenerator.generate(qr, update.releaseUrl, 208)
      .catch(() => qr.remove());
  }
  let dialog = null;
  let closed = false;
  const notifyClosed = () => {
    if (closed) {
      return;
    }
    closed = true;
    if (typeof onClose === "function") {
      onClose();
    }
  };
  const close = () => {
    const activeDialog = dialog;
    dialog = null;
    notifyClosed();
    activeDialog?.destroy();
  };
  dialog = new NuvioDialog({
    title: t("update_title", "App Update"),
    subtitle: t(
      "update_installer_required",
      "Use Apps2Samsung on a phone or computer to update sideloaded TV apps."
    ),
    widthVw: 52.1,
    panelClassName: "app-update-dialog",
    actionsClassName: "app-update-actions",
    content,
    onVerticalNavigate(direction) {
      const previous = notes.scrollTop;
      notes.scrollTop += direction * Math.max(120, Math.round(notes.clientHeight * 0.7));
      return notes.scrollTop !== previous;
    },
    buttons: [
      {
        key: "ok",
        label: "OK",
        onAction: close
      }
    ],
    onDismiss: () => {
      dialog = null;
      notifyClosed();
    }
  });
  dialog.mount(documentRef.body);
  return dialog;
}
