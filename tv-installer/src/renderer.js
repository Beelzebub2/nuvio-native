"use strict";
const $ = id => document.getElementById(id);
const api = window.starlitTV;
const buttons = ["btn-pair", "btn-install", "btn-browse", "btn-launch", "btn-uninstall"];
let filePath = "";
let busy = false;

function addLog(message, kind = "") {
  const log = $("log");
  const line = document.createElement("p");
  line.className = "entry " + kind;
  const time = new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  line.textContent = "[" + time + "] " + String(message);
  log.appendChild(line);
  while (log.children.length > 50) log.removeChild(log.firstChild);
  log.scrollTop = log.scrollHeight;
}
function setBusy(isBusy, label = "") {
  busy = isBusy;
  for (const id of buttons) $(id).disabled = isBusy;
  $("task-state").textContent = label || (isBusy ? "Working" : "Ready");
}
function target() {
  return {
    ip: $("tv-ip").value.trim(),
    deviceName: $("tv-name").value.trim(),
    passphrase: $("tv-passphrase").value
  };
}
function selectedSource() {
  return document.querySelector('input[name="source"]:checked').value;
}
function syncSource() {
  const source = selectedSource();
  $("local-panel").hidden = source !== "local";
  $("release-panel").hidden = source !== "release";
  $("source-local-choice").classList.toggle("selected", source === "local");
  $("source-release-choice").classList.toggle("selected", source === "release");
}
async function perform(action) {
  if (busy) return;
  if (!api) { addLog("Installer bridge unavailable. Restart the application.", "error"); return; }
  const request = { action, target: target() };
  if (action === "install") {
    request.source = selectedSource();
    request.filePath = request.source === "local" ? filePath : "";
    if (request.source === "local" && !filePath) {
      addLog("Choose a local Starlit IPK to install.", "error");
      $("btn-browse").focus();
      return;
    }
  }
  if (action === "uninstall" &&
      !window.confirm("Uninstall Starlit from this LG TV? TV-local application data may be deleted.")) return;
  setBusy(true, action === "pair" ? "Pairing…" : action === "install" ? "Installing…" : action === "launch" ? "Launching…" : "Uninstalling…");
  addLog(action === "pair" ? "Starting LG Developer Mode pairing" : action === "install" ? "Preparing Starlit installation" : action === "launch" ? "Launching Starlit on the TV" : "Uninstall requested");
  try {
    const response = await api.operate(request);
    if (!response?.ok) throw new Error(response?.error || "The operation did not complete");
    if (action === "pair") {
      $("tv-passphrase").value = "";
      $("connection-status").classList.add("connected");
      $("connection-status").lastElementChild.textContent = "LG pairing key saved for " + request.target.deviceName;
      addLog("TV paired successfully. You can install Starlit.", "success");
    } else if (action === "install") addLog("Starlit " + response.result.version + " installed on the TV.", "success");
    else if (action === "launch") addLog("Starlit launch requested on the TV.", "success");
    else if (action === "uninstall") addLog("Starlit was uninstalled from the TV.", "success");
    setBusy(false, "Completed");
  } catch (error) {
    addLog(error.message || String(error), "error");
    setBusy(false, "Action failed");
  }
}
for (const radio of document.querySelectorAll('input[name="source"]')) radio.addEventListener("change", syncSource);
$("btn-pair").addEventListener("click", () => perform("pair"));
$("btn-install").addEventListener("click", () => perform("install"));
$("btn-launch").addEventListener("click", () => perform("launch"));
$("btn-uninstall").addEventListener("click", () => perform("uninstall"));
$("btn-browse").addEventListener("click", async () => {
  if (busy) return;
  try {
    const selected = await api.chooseIpk();
    if (!selected) return;
    filePath = selected;
    $("selected-file").textContent = selected.split(/[\\/]/).pop();
    addLog("Selected local package " + $("selected-file").textContent);
  } catch (error) {
    addLog("Could not select a file: " + error.message, "error");
  }
});
$("tv-form").addEventListener("submit", event => event.preventDefault());
if (api) api.onLog(message => addLog(message));
syncSource();
