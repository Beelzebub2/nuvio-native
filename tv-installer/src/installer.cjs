"use strict";
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const path = require("node:path");
const os = require("node:os");
const { spawn } = require("node:child_process");
const { isIP } = require("node:net");
const { inspectIpk, MAX_IPK, STABLE_APP_ID } = require("./ipk.cjs");
const { fetchVerifiedRelease } = require("./releases.cjs");

function isLanIPv4(value) {
  if (isIP(value) !== 4) return false;
  const parts = value.split(".").map(Number);
  return parts[0] === 10 || (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) ||
    (parts[0] === 192 && parts[1] === 168);
}
function validateTarget({ ip, deviceName, passphrase = "" } = {}) {
  if (!isLanIPv4(ip)) throw new Error("Enter the TV's private LAN IPv4 address");
  if (!/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,49}$/.test(String(deviceName || ""))) {
    throw new Error("Use a short TV device name with letters, numbers, dots or hyphens");
  }
  if (typeof passphrase !== "string" || passphrase.length > 128 || /[\r\n\0]/.test(passphrase)) {
    throw new Error("Invalid Developer Mode passphrase");
  }
  return { ip, deviceName, passphrase };
}
function deviceListPath() {
  return path.join(process.env.APPDATA || process.env.HOME || process.env.USERPROFILE || os.homedir(), ".webos", "tv", "novacom-devices.json");
}
async function devices() {
  try {
    const entries = JSON.parse(await fsp.readFile(deviceListPath(), "utf8"));
    return Array.isArray(entries) ? entries : [];
  } catch (error) {
    if (error.code === "ENOENT") return [];
    throw new Error("Could not inspect saved webOS devices: " + error.message);
  }
}
function resolveAresBinary(command) {
  if (!/^ares-(?:setup-device|novacom|install|launch)$/.test(command)) {
    throw new Error("Unsupported webOS operation");
  }
  const packagePath = require.resolve("@webos-tools/cli/package.json");
  const pkg = JSON.parse(fs.readFileSync(packagePath, "utf8"));
  const relative = pkg.bin && pkg.bin[command];
  if (!relative || path.isAbsolute(relative) || relative.includes("..")) {
    throw new Error("webOS CLI missing " + command);
  }
  return path.join(path.dirname(packagePath), relative);
}
async function runAres(command, args, { onStatus = () => {}, passphrase = "" } = {}) {
  const entrypoint = resolveAresBinary(command);
  return new Promise((resolve, reject) => {
    const inElectron = Boolean(process.versions.electron);
    const child = spawn(process.execPath, [entrypoint, ...args], {
      shell: false,
      windowsHide: true,
      env: inElectron ? { ...process.env, ELECTRON_RUN_AS_NODE: "1" } : process.env,
      stdio: ["ignore", "pipe", "pipe"]
    });
    let stdout = "", stderr = "";
    const redact = text => passphrase ? String(text).split(passphrase).join("[redacted]") : String(text);
    const timer = setTimeout(() => child.kill(), 120000);
    child.stdout.on("data", chunk => {
      stdout = (stdout + chunk).slice(-16000);
      const message = redact(String(chunk).trim()).slice(0, 500);
      if (message) onStatus(message);
    });
    child.stderr.on("data", chunk => {
      stderr = (stderr + chunk).slice(-16000);
    });
    child.once("error", error => { clearTimeout(timer); reject(error); });
    child.once("close", code => {
      clearTimeout(timer);
      if (code === 0) return resolve(redact(stdout));
      reject(new Error(command + " failed: " + redact(stderr || stdout || "No details from webOS CLI").slice(-800)));
    });
  });
}
async function pairTv(target, { run = runAres, listDevices = devices, onStatus = () => {} } = {}) {
  const { ip, deviceName, passphrase } = validateTarget(target);
  if (!passphrase) throw new Error("Enter the passphrase shown by the TV's Developer Mode app");
  const existing = (await listDevices()).find(item => item.name === deviceName);
  if (existing && existing.host !== ip) {
    throw new Error("That device name already belongs to another TV. Choose a new device name.");
  }
  if (!existing) {
    onStatus("Saving your TV to the LG developer tools");
    await run("ares-setup-device", [
      "--add", deviceName, "--info", "username=prisoner",
      "--info", "host=" + ip, "--info", "port=9922", "--info", "default=true"
    ], { onStatus });
  }
  onStatus("Pairing securely with the TV's Key Server");
  await run("ares-novacom", ["--device", deviceName, "--getkey", "--passphrase", passphrase], {
    onStatus, passphrase
  });
  return { deviceName, paired: true };
}
async function requireSavedDevice(target, { listDevices = devices } = {}) {
  const { ip, deviceName } = validateTarget(target);
  if (!(await listDevices()).some(entry => entry.name === deviceName && entry.host === ip)) {
    throw new Error("This TV is not paired. Enable Developer Mode and pair it first.");
  }
  return deviceName;
}
async function operate({
  action, target, source = "release", filePath = ""
} = {}, {
  run = runAres, listDevices = devices, onStatus = () => {},
  fetchRelease = fetchVerifiedRelease
} = {}) {
  if (!["pair", "install", "launch", "uninstall"].includes(action)) {
    throw new Error("Unknown installer action");
  }
  if (action === "pair") return pairTv(target, { run, listDevices, onStatus });
  const name = await requireSavedDevice(target, { listDevices });
  if (action === "launch") {
    await run("ares-launch", ["--device", name, STABLE_APP_ID], { onStatus });
    return { launched: true };
  }
  if (action === "uninstall") {
    await run("ares-install", ["--device", name, "--remove", STABLE_APP_ID], { onStatus });
    return { uninstalled: true };
  }
  if (!["release", "local"].includes(source)) throw new Error("Select a verified release or local IPK");
  let packagePath = filePath;
  let tempDir = "";
  try {
    if (source === "release") {
      const downloaded = await fetchRelease({ onStatus });
      tempDir = await fsp.mkdtemp(path.join(os.tmpdir(), "starlit-tv-install-"));
      packagePath = path.join(tempDir, downloaded.filename);
      await fsp.writeFile(packagePath, downloaded.bytes, { flag: "wx", mode: 0o600 });
      onStatus("Release SHA-256 verified");
    } else {
      if (!path.isAbsolute(String(packagePath)) || path.extname(packagePath).toLowerCase() !== ".ipk") {
        throw new Error("Choose a local .ipk file using the file picker");
      }
    }
    const stat = await fsp.stat(packagePath);
    if (!stat.isFile() || stat.size <= 0 || stat.size > MAX_IPK) throw new Error("Invalid or oversized IPK");
    const packageInfo = inspectIpk(await fsp.readFile(packagePath));
    onStatus("Installing Starlit " + packageInfo.version + " on " + name);
    await run("ares-install", ["--device", name, packagePath], { onStatus });
    return { installed: true, version: packageInfo.version, appId: STABLE_APP_ID };
  } finally {
    if (tempDir) await fsp.rm(tempDir, { recursive: true, force: true });
  }
}
module.exports = { isLanIPv4, validateTarget, deviceListPath, devices, pairTv, requireSavedDevice, operate, runAres, resolveAresBinary };
