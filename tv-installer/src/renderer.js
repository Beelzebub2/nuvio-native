"use strict";
const $ = id => document.getElementById(id);
const api = window.starlitTV;
const controls = ["btn-discover", "btn-pair", "btn-install", "btn-browse", "btn-launch", "btn-uninstall",
  "tv-ip", "tv-name", "tv-passphrase", "samsung-signing"];
const platforms = {
  webos: {
    name: "LG webOS", brand: "LG", extension: ".ipk", packageText: "LG IPK",
    button: "Pair with LG TV", connectTitle: "Pair with your TV", connected: "LG TV paired",
    localDetail: "An IPK you built or downloaded",
    note: "LG installation uses the official webOS developer tools. Uninstall always asks first.",
    copy: "Installing a newer version updates the existing Starlit app in place."
  },
  tizen: {
    name: "Samsung Tizen", brand: "Samsung", extension: ".wgt", packageText: "Samsung WGT",
    button: "Check Samsung TV", connectTitle: "Connect to your TV", connected: "Samsung TV ready",
    localDetail: "A WGT you built or downloaded",
    note: "First use downloads the checksum-verified, GPL-licensed Apps2Samsung CLI. It signs Starlit for your TV and never uninstalls other apps.",
    copy: "Starlit is signed for your TV and keeps the same app ID, so later versions install as updates."
  }
};
const busyTitles = {
  pair: platform => platform === "webos" ? "Pairing with your LG TV" : "Checking your Samsung TV",
  install: () => "Installing Starlit",
  launch: () => "Launching Starlit",
  uninstall: () => "Uninstalling Starlit"
};
const failTitles = {
  pair: platform => platform === "webos" ? "Pairing failed" : "TV check failed",
  install: () => "Install failed",
  launch: () => "Launch failed",
  uninstall: () => "Uninstall failed"
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
function packageLabel(platform = currentPlatform()) {
  return platforms[platform].extension.slice(1).toUpperCase();
}
function addLog(message, kind = "") {
  const log = $("log");
  const line = document.createElement("p");
  line.className = "entry " + kind;
  const time = document.createElement("span");
  time.className = "entry-time";
  time.textContent = new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  const dot = document.createElement("span");
  dot.className = "entry-dot";
  const text = document.createElement("span");
  text.className = "entry-text";
  text.textContent = String(message);
  line.append(time, dot, text);
  log.appendChild(line);
  while (log.children.length > 50) log.removeChild(log.firstChild);
  log.scrollTop = log.scrollHeight;
}

/* The TV on the left reflects what is happening: off, busy, on, done or error. */
function setStage(tv, title, detail) {
  const stage = $("stage");
  if (tv === "done" && stage.dataset.tv === "done") {
    stage.dataset.tv = "on";
    void stage.offsetWidth;
  }
  stage.dataset.tv = tv;
  $("caption-title").textContent = title;
  $("caption-detail").textContent = detail;
}
function describeTarget(platform = currentPlatform()) {
  const ip = $("tv-ip").value.trim();
  return platform === "webos" ? ip + ", saved as " + $("tv-name").value.trim() : "At " + ip;
}
function showIdleStage() {
  const platform = currentPlatform();
  if (connectedPlatform === platform) {
    setStage("on", platforms[platform].connected, describeTarget(platform));
  } else {
    setStage("off", "No TV connected", "Follow the steps to " +
      (platform === "webos" ? "pair" : "connect") + " your " + platforms[platform].brand + " TV.");
  }
}
function setBusy(nextBusy) {
  busy = nextBusy;
  for (const id of controls) $(id).disabled = busy;
  $("package-sources").disabled = busy;
  for (const button of $("discovery-results").querySelectorAll("button")) button.disabled = busy;
  for (const field of document.querySelectorAll('input[name="platform"]')) field.disabled = busy;
  $("flow").setAttribute("aria-busy", String(busy));
}
function target() {
  return {
    ip: $("tv-ip").value.trim(),
    deviceName: $("tv-name").value.trim(),
    passphrase: currentPlatform() === "webos" ? $("tv-passphrase").value : ""
  };
}

/* Mirrors platforms.cjs isLanIPv4 so obvious typos are caught before any TV work. */
function isLanIPv4(value) {
  const match = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(value);
  if (!match) return false;
  const parts = match.slice(1).map(Number);
  if (parts.some((part, index) => part > 255 || String(part) !== match[index + 1])) return false;
  return parts[0] === 10 || (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) ||
    (parts[0] === 192 && parts[1] === 168);
}
function setIpError(message) {
  const error = $("tv-ip-error");
  error.textContent = message;
  error.hidden = !message;
  if (message) $("tv-ip").setAttribute("aria-invalid", "true");
  else $("tv-ip").removeAttribute("aria-invalid");
}
function setPackageMissing(missing) {
  $("local-panel").classList.toggle("is-missing", missing);
}

function syncSource() {
  const source = selectedSource();
  $("local-panel").hidden = source !== "local";
  $("release-panel").hidden = source !== "release";
  $("source-local-choice").classList.toggle("selected", source === "local");
  $("source-release-choice").classList.toggle("selected", source === "release");
}
function showSelectedFile() {
  const label = packageLabel();
  const panel = $("local-panel");
  panel.classList.toggle("has-file", Boolean(filePath));
  $("selected-file").textContent = filePath ? filePath.split(/[\\/]/).pop() : "No " + label + " chosen";
  $("selected-file").title = filePath;
  $("btn-browse").textContent = filePath ? "Change" : "Choose " + label;
}
function resetConnectionStatus() {
  const connected = connectedPlatform === currentPlatform();
  $("connection-status").classList.toggle("connected", connected);
  $("connection-status").lastElementChild.textContent = connected ?
    (connectedPlatform === "webos" ? "Pairing key saved" : "TV checked") :
    (currentPlatform() === "webos" ? "Not paired yet" : "Not checked yet");
  $("step-connect").classList.toggle("is-done", connected);
}
function clearConnection() {
  connectedPlatform = "";
  $("step-install").classList.remove("is-done");
  resetConnectionStatus();
  if (!busy) showIdleStage();
}
function switchPlatform(name) {
  const info = platforms[name];
  if (!info || busy) return;
  const selected = state[name];
  document.body.dataset.platform = name;
  $("tv-ip").value = selected.ip;
  $("tv-name").value = selected.name || "starlit-lg-tv";
  $("tv-passphrase").value = "";
  setIpError("");
  filePath = "";
  setPackageMissing(false);
  showSelectedFile();
  $("workspace-copy").textContent = info.copy;
  $("connect-title").textContent = info.connectTitle;
  $("lg-steps").hidden = name !== "webos";
  $("samsung-steps").hidden = name !== "tizen";
  $("host-ip-note").hidden = name !== "tizen";
  $("device-name-field").hidden = name !== "webos";
  $("lg-passphrase-field").hidden = name !== "webos";
  $("samsung-signing-field").hidden = name !== "tizen";
  $("btn-pair").textContent = info.button;
  $("local-source-detail").textContent = info.localDetail;
  $("support-note").textContent = info.note;
  $("confirm-title").textContent = "Uninstall Starlit from your " + info.brand + " TV?";
  $("platform-lg").classList.toggle("selected", name === "webos");
  $("platform-samsung").classList.toggle("selected", name === "tizen");
  $("step-install").classList.remove("is-done");
  resetConnectionStatus();
  showIdleStage();
  addLog("Selected " + info.name + ". Use a matching " + info.extension + " package.");
}

/* Home routers usually hand out 192.168.x.x, so list those before 10.x and the
   172.x addresses that virtual adapters such as WSL or Hyper-V tend to use. */
function hostIpRank(ip) {
  return ip.startsWith("192.168.") ? 0 : ip.startsWith("10.") ? 1 : 2;
}
function showHostIps(ips) {
  const list = $("host-ips");
  const valid = Array.isArray(ips) ? ips.filter(ip => typeof ip === "string") : [];
  if (!valid.length) {
    list.textContent = "Unavailable. Check this PC's network settings.";
    return;
  }
  list.replaceChildren(...valid.sort((a, b) => hostIpRank(a) - hostIpRank(b)).map(ip => {
    const chip = document.createElement("span");
    chip.className = "host-ip-chip";
    chip.textContent = ip;
    return chip;
  }));
}

const confirmDialog = $("confirm-dialog");
let resolveConfirm = null;
function closeConfirm(accepted) {
  const resolve = resolveConfirm;
  resolveConfirm = null;
  if (confirmDialog.open) confirmDialog.close();
  if (resolve) resolve(accepted);
}
function confirmUninstall() {
  if (typeof confirmDialog.showModal !== "function") {
    return Promise.resolve(window.confirm($("confirm-title").textContent + " " + $("confirm-copy").textContent));
  }
  return new Promise(resolve => {
    resolveConfirm = resolve;
    confirmDialog.showModal();
    $("confirm-cancel").focus();
  });
}
$("confirm-cancel").addEventListener("click", () => closeConfirm(false));
$("confirm-accept").addEventListener("click", () => closeConfirm(true));
confirmDialog.addEventListener("cancel", event => { event.preventDefault(); closeConfirm(false); });
confirmDialog.addEventListener("click", event => { if (event.target === confirmDialog) closeConfirm(false); });

async function perform(action) {
  if (busy) return;
  if (!api) {
    addLog("Installer connection unavailable. Restart Starlit TV Installer.", "error");
    setStage("error", "Installer unavailable", "Restart Starlit TV Installer.");
    return;
  }
  const platform = currentPlatform();
  const request = { action, platform, target: target(), signing: $("samsung-signing").value };
  if (!isLanIPv4(request.target.ip)) {
    setIpError(request.target.ip ? "Use the TV's private address, like 192.168.1.100." : "Enter the TV's IP address.");
    $("tv-ip").focus();
    return;
  }
  if (action === "install") {
    request.source = selectedSource();
    request.filePath = request.source === "local" ? filePath : "";
    if (request.source === "local" && !filePath) {
      setPackageMissing(true);
      addLog("Choose a Starlit " + platforms[platform].extension + " package first.", "error");
      $("btn-browse").focus();
      return;
    }
  }
  if (action === "uninstall" && !(await confirmUninstall())) return;
  setBusy(true);
  setStage("busy", busyTitles[action](platform), "Starting…");
  addLog(action === "pair" ? "Checking " + platforms[platform].name + " Developer Mode" :
    action === "install" ? "Preparing Starlit " + platforms[platform].packageText + " installation" :
    action === "launch" ? "Launching Starlit" : "Uninstall requested");
  try {
    const response = await api.operate(request);
    if (!response?.ok) throw new Error(response?.error || "The operation did not complete");
    connectedPlatform = platform;
    resetConnectionStatus();
    setBusy(false);
    if (action === "pair") {
      $("tv-passphrase").value = "";
      addLog(platform === "webos" ? "LG pairing key saved." :
        "Samsung TV checked. Installation can now prepare its signing certificate.", "success");
      showIdleStage();
      $("step-install").scrollIntoView({
        behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth", block: "nearest"
      });
    } else if (action === "install") {
      const version = response.result?.version;
      if (response.result?.pendingTvConfirmation) {
        addLog("WGT sent to TV-Store. Confirm Install on the Samsung TV to finish.", "success");
        setStage("on", "Confirm on your TV", "Select Install on the Samsung TV to finish.");
      } else {
        addLog("Starlit " + (version || "") + " installed on " + platforms[platform].name + ".", "success");
        $("step-install").classList.add("is-done");
        setStage("done", version ? "Starlit " + version + " installed" : "Starlit installed",
          "Find it with your TV's apps.");
      }
    } else if (action === "launch") {
      addLog("Starlit launch requested on your TV.", "success");
      setStage("on", "Launch requested", "Starlit should open on your TV.");
    } else if (action === "uninstall") {
      addLog("Starlit removed from your TV.", "success");
      $("step-install").classList.remove("is-done");
      setStage("on", "Starlit removed", "Starlit was uninstalled from your TV.");
    }
  } catch (error) {
    const message = error.message || String(error);
    addLog(message, "error");
    if (action === "pair") {
      connectedPlatform = "";
      $("step-install").classList.remove("is-done");
      resetConnectionStatus();
    }
    setBusy(false);
    setStage("error", failTitles[action](platform), message);
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
    select.className = "button small secondary";
    select.type = "button";
    select.textContent = "Use TV";
    select.setAttribute("aria-label", "Use " + name.textContent + " at " + tv.ip);
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
  setBusy(true);
  setStage("busy", "Finding nearby TVs", "Scanning your private network.");
  $("discovery-status").textContent = "Scanning your private network for Samsung and LG TVs...";
  $("discovery-results").replaceChildren();
  $("discovery-results").hidden = true;
  try {
    const response = await api.discoverTvs();
    if (!response?.ok) throw new Error(response?.error || "Discovery failed");
    const devices = Array.isArray(response.result?.devices) ? response.result.devices : [];
    const warnings = Array.isArray(response.result?.warnings) ? response.result.warnings : [];
    setBusy(false);
    showDiscoveredTvs(devices);
    $("discovery-status").textContent = devices.length
      ? "Found " + devices.length + " TV" + (devices.length === 1 ? "" : "s") +
        ". Choose one below; installation still requires Developer Mode."
      : "No compatible TVs found. Make sure your TV is on and connected to the same LAN, or enter its IP below.";
    for (const warning of warnings) addLog(warning, "error");
    addLog("TV scan finished: " + devices.length + " compatible device" +
      (devices.length === 1 ? "" : "s") + " found.", devices.length ? "success" : "muted");
    showIdleStage();
  } catch (error) {
    $("discovery-status").textContent = "Could not complete the scan. Check network access or enter the TV IP manually.";
    addLog("TV discovery failed: " + (error.message || String(error)), "error");
    setBusy(false);
    setStage("error", "TV scan failed", "Use manual IP entry or try the scan again.");
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
  setIpError("");
  clearConnection();
});
$("tv-name").addEventListener("input", () => {
  state.webos.name = $("tv-name").value;
  clearConnection();
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
    setPackageMissing(false);
    showSelectedFile();
    addLog("Selected " + $("selected-file").textContent);
  } catch (error) {
    addLog("Could not choose a package: " + error.message, "error");
  }
});
$("tv-form").addEventListener("submit", event => event.preventDefault());
$("tv-form").addEventListener("keydown", event => {
  if (event.key === "Enter" && event.target instanceof HTMLInputElement) {
    event.preventDefault();
    perform("pair");
  }
});
if (api) {
  api.onLog(message => {
    addLog(message);
    if (busy) $("caption-detail").textContent = message;
  });
  api.localIps().then(showHostIps).catch(() => showHostIps([]));
}
addLog("Choose LG or Samsung, then follow the steps.", "muted");
syncSource();
showSelectedFile();
resetConnectionStatus();
showIdleStage();
