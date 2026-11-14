"use strict";
const $ = id => document.getElementById(id);
const api = window.starlitTV;
const buttons = ["btn-discover", "btn-pair", "btn-install", "btn-browse", "btn-launch", "btn-uninstall"];
const platforms = {
  webos: {
    name: "LG webOS", extension: ".ipk", title: "Local IPK file",
    packageText: "LG IPK", button: "Pair with LG TV",
    note: "LG installation uses the official webOS Developer Mode tools. Uninstall always requires confirmation.",
    copy: "Choose an LG webOS package. Installing the next version updates the existing Starlit app in place."
  },
  tizen: {
    name: "Samsung Tizen", extension: ".wgt", title: "Local WGT file",
    packageText: "Samsung WGT", button: "Check Samsung TV",
    note: "First use downloads the checksum-verified GPL-licensed Apps2Samsung CLI. It signs the package for your TV and does not uninstall other apps.",
    copy: "Choose a Samsung Tizen WGT. The installer signs it for your TV and keeps the same app ID for future updates."
  }
};
const state = { webos: { ip: "", name: "starlit-lg-tv" }, tizen: { ip: "", name: "" } };
let filePath = "";
let busy = false;
let connectedPlatform = "";

function currentPlatform() {
  return document.querySelector('input[name="platform"]:checked').value;
}
function selectedSource() {
  return document.querySelector('input[name="source"]:checked').value;
}
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
function setBusy(nextBusy, label = "") {
  busy = nextBusy;
  for (const id of buttons) $(id).disabled = busy;
  $("package-sources").disabled = busy;
  for (const button of $("discovery-results").querySelectorAll("button")) button.disabled = busy;
  for (const field of document.querySelectorAll('input[name="platform"]')) field.disabled = busy;
  $("tv-ip").disabled = busy;
  $("tv-name").disabled = busy;
  $("tv-passphrase").disabled = busy;
  $("samsung-signing").disabled = busy;
  $("task-state").textContent = label || (busy ? "Working" : "Ready");
}
function target() {
  return {
    ip: $("tv-ip").value.trim(),
    deviceName: $("tv-name").value.trim(),
    passphrase: currentPlatform() === "webos" ? $("tv-passphrase").value : ""
  };
}
function syncSource() {
  const source = selectedSource();
  $("local-panel").hidden = source !== "local";
  $("release-panel").hidden = source !== "release";
  $("source-local-choice").classList.toggle("selected", source === "local");
  $("source-release-choice").classList.toggle("selected", source === "release");
}
function resetConnectionStatus() {
  const connected = connectedPlatform === currentPlatform();
  $("connection-status").classList.toggle("connected", connected);
  $("connection-status").lastElementChild.textContent =
    connected ? (connectedPlatform === "webos" ? "LG pairing key saved" : "Samsung connection checked") :
      "No TV connected in this session";
}
function switchPlatform(name) {
  const info = platforms[name];
  if (!info || busy) return;
  const selected = state[name];
  $("tv-ip").value = selected.ip;
  $("tv-name").value = selected.name || "starlit-lg-tv";
  $("tv-passphrase").value = "";
  filePath = "";
  $("selected-file").textContent = "No " + info.extension.toUpperCase().slice(1) + " selected";
  $("btn-browse").textContent = "Choose " + info.extension.toUpperCase().slice(1);
  $("workspace-platform").textContent = info.name;
  $("workspace-copy").textContent = info.copy;
  $("guide-title").textContent = "Before you connect your " + (name === "tizen" ? "Samsung" : "LG") + " TV";
  $("lg-steps").hidden = name !== "webos";
  $("samsung-steps").hidden = name !== "tizen";
  $("host-ip-note").hidden = name !== "tizen";
  $("device-name-field").hidden = name !== "webos";
  $("lg-passphrase-field").hidden = name !== "webos";
  $("samsung-signing-field").hidden = name !== "tizen";
  $("btn-pair").textContent = info.button;
  $("local-source-title").textContent = info.title;
  $("support-note").textContent = info.note;
  $("platform-lg").classList.toggle("selected", name === "webos");
  $("platform-samsung").classList.toggle("selected", name === "tizen");
  resetConnectionStatus();
  addLog("Selected " + info.name + ". Choose its matching " + info.extension + " package.");
}
async function perform(action) {
  if (busy) return;
  if (!api) {
    addLog("Installer connection unavailable. Restart Starlit TV Installer.", "error");
    return;
  }
  const platform = currentPlatform();
  const request = { action, platform, target: target(), signing: $("samsung-signing").value };
  if (action === "install") {
    request.source = selectedSource();
    request.filePath = request.source === "local" ? filePath : "";
    if (request.source === "local" && !filePath) {
      addLog("Choose a Starlit " + platforms[platform].extension + " package first.", "error");
      $("btn-browse").focus();
      return;
    }
  }
  if (action === "uninstall" &&
      !window.confirm("Uninstall Starlit from this " + platforms[platform].name + " TV? TV-local app data may be deleted.")) return;
  setBusy(true, action === "pair" ? "Connecting..." : action === "install" ? "Installing..." : action === "launch" ? "Launching..." : "Uninstalling...");
  addLog(action === "pair" ? "Checking " + platforms[platform].name + " Developer Mode" :
    action === "install" ? "Preparing Starlit " + platforms[platform].packageText + " installation" :
    action === "launch" ? "Launching Starlit" : "Uninstall requested");
  try {
    const response = await api.operate(request);
    if (!response?.ok) throw new Error(response?.error || "The operation did not complete");
    if (action === "pair") {
      $("tv-passphrase").value = "";
      connectedPlatform = platform;
      resetConnectionStatus();
      addLog(platform === "webos" ? "LG pairing key saved." :
        "Samsung TV checked. Installation can now prepare its signing certificate.", "success");
    } else if (action === "install") {
      if (response.result.pendingTvConfirmation) {
        addLog("WGT sent to TV-Store. Confirm Install on the Samsung TV to finish.", "success");
      } else {
        addLog("Starlit " + response.result.version + " installed on " + platforms[platform].name + ".", "success");
      }
    } else if (action === "launch") addLog("Starlit launch requested on your TV.", "success");
    else if (action === "uninstall") addLog("Starlit removed from your TV.", "success");
    setBusy(false, "Completed");
  } catch (error) {
    addLog(error.message || String(error), "error");
    setBusy(false, "Action failed");
  }
}

function useDiscoveredTv(tv) {
  if (busy || !tv || !platforms[tv.platform]) return;
  const platform = tv.platform;
  const previousIp = state[platform].ip;
  if (platform === "webos" && previousIp !== tv.ip) {
    state.webos.name = tv.savedDeviceName || ("starlit-lg-" + tv.ip.split(".").pop());
  }
  state[platform].ip = tv.ip;
  connectedPlatform = "";
  const radio = document.querySelector('input[name="platform"][value="' + platform + '"]');
  radio.checked = true;
  switchPlatform(platform);
  $("discovery-status").textContent = "Selected " + (tv.name || platforms[platform].name) +
    " (" + tv.ip + "). Check Developer Mode and connect to continue.";
  addLog("Selected " + platforms[platform].name + " at " + tv.ip + ". Developer Mode and pairing are still required.");
  $("btn-pair").focus();
}

function showDiscoveredTvs(devices) {
  const list = $("discovery-results");
  list.replaceChildren();
  if (!devices.length) {
    list.hidden = true;
    return;
  }
  for (const tv of devices) {
    if (!tv || !platforms[tv.platform] || typeof tv.ip !== "string") continue;
    const item = document.createElement("div");
    item.className = "discovered-device";
    item.setAttribute("role", "listitem");

    const info = document.createElement("div");
    info.className = "device-info";
    const platform = document.createElement("span");
    platform.className = "device-platform";
    platform.textContent = platforms[tv.platform].name;
    const name = document.createElement("strong");
    name.textContent = tv.name || platforms[tv.platform].name;
    const meta = document.createElement("small");
    meta.textContent = [tv.ip, tv.model].filter(Boolean).join(" · ");
    const detail = document.createElement("small");
    detail.textContent = tv.detail || "Developer Mode setup required";
    info.append(platform, name, meta, detail);

    const select = document.createElement("button");
    select.className = "secondary";
    select.type = "button";
    select.textContent = "Use TV";
    select.setAttribute("aria-label", "Use " + name.textContent + " at " + tv.ip);
    select.disabled = busy;
    select.addEventListener("click", () => useDiscoveredTv(tv));
    item.append(info, select);
    list.appendChild(item);
  }
  list.hidden = !list.children.length;
}

async function scanForTvs() {
  if (busy) return;
  if (!api?.discoverTvs) {
    $("discovery-status").textContent = "Network discovery is unavailable. Enter the IP address below.";
    addLog("The installer cannot start TV discovery.", "error");
    return;
  }
  setBusy(true, "Searching for TVs...");
  $("discovery-status").textContent = "Scanning your private network for Samsung and LG TVs...";
  $("discovery-results").replaceChildren();
  $("discovery-results").hidden = true;
  try {
    const response = await api.discoverTvs();
    if (!response?.ok) throw new Error(response?.error || "Discovery failed");
    const devices = Array.isArray(response.result?.devices) ? response.result.devices : [];
    const warnings = Array.isArray(response.result?.warnings) ? response.result.warnings : [];
    showDiscoveredTvs(devices);
    $("discovery-status").textContent = devices.length
      ? "Found " + devices.length + " TV" + (devices.length === 1 ? "" : "s") +
        ". Choose one below; installation still requires Developer Mode."
      : "No compatible TVs found. Make sure your TV is on and connected to the same LAN, or enter its IP below.";
    for (const warning of warnings) addLog(warning, "error");
    addLog("TV scan finished: " + devices.length + " compatible device" +
      (devices.length === 1 ? "" : "s") + " found.", devices.length ? "success" : "muted");
    setBusy(false, "Ready");
  } catch (error) {
    $("discovery-status").textContent = "Could not complete the scan. Check network access or enter the TV IP manually.";
    addLog("TV discovery failed: " + (error.message || String(error)), "error");
    setBusy(false, "Scan failed");
  }
}

for (const radio of document.querySelectorAll('input[name="source"]')) {
  radio.addEventListener("change", syncSource);
}
for (const radio of document.querySelectorAll('input[name="platform"]')) {
  radio.addEventListener("change", () => switchPlatform(radio.value));
}
$("tv-ip").addEventListener("input", () => {
  state[currentPlatform()].ip = $("tv-ip").value;
  connectedPlatform = "";
  resetConnectionStatus();
});
$("tv-name").addEventListener("input", () => {
  state.webos.name = $("tv-name").value;
  connectedPlatform = "";
  resetConnectionStatus();
});
$("btn-discover").addEventListener("click", scanForTvs);
$("btn-pair").addEventListener("click", () => perform("pair"));
$("btn-install").addEventListener("click", () => perform("install"));
$("btn-launch").addEventListener("click", () => perform("launch"));
$("btn-uninstall").addEventListener("click", () => perform("uninstall"));
$("btn-browse").addEventListener("click", async () => {
  if (busy || !api) return;
  try {
    const selected = await api.choosePackage(currentPlatform());
    if (!selected) return;
    filePath = selected;
    $("selected-file").textContent = selected.split(/[\\/]/).pop();
    addLog("Selected " + $("selected-file").textContent);
  } catch (error) {
    addLog("Could not choose a package: " + error.message, "error");
  }
});
$("tv-form").addEventListener("submit", event => event.preventDefault());
if (api) {
  api.onLog(message => addLog(message));
  api.localIps().then(ips => {
    $("host-ips").textContent = Array.isArray(ips) && ips.length ?
      ips.join(", ") : "Unavailable; check your PC's network settings";
  }).catch(() => { $("host-ips").textContent = "Unavailable; check your PC's network settings"; });
}
syncSource();
resetConnectionStatus();
