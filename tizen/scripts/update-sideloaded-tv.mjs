/**
 * Opt-in, non-interactive update helper for developer-sideloaded Samsung TVs.
 * A public Tizen app cannot call the platform-only package install privilege.
 * This helper runs on a trusted PC with the already-configured Apps2Samsung
 * CLI/certificate; it is not part of the on-TV executable.
 *
 * Defaults to CHECK ONLY. --install must be explicitly supplied, e.g. by an
 * owner-created Task Scheduler entry. Never uninstalls, changes package IDs,
 * bypasses certificate warnings, opens a browser or launches the TV app.
 */
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { connect, isIP } from "node:net";
import { tmpdir } from "node:os";
import { basename, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  getLatestAppUpdateWithRetry,
  isRemoteAppVersionNewer
} from "../js/core/update/appUpdateService.js";

const APP_ID = "StarlitTV1.StarlitTV";
const RELEASE_ASSET_BASE = "https://github.com/Beelzebub2/nuvio-native/releases/download";
const MAX_WGT_BYTES = 64 * 1024 * 1024;
const MAX_SUMS_BYTES = 32 * 1024;

export function isLanIp(ip) {
  if (isIP(ip) !== 4) return false;
  const octets = ip.split(".").map(Number);
  return octets[0] === 10 ||
    (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) ||
    (octets[0] === 192 && octets[1] === 168);
}

export function parseExpectedWgtSha256(sums, filename) {
  if (!/^Starlit-Tizen-\d+\.\d+\.\d+\.wgt$/.test(filename)) {
    throw new Error("Invalid Tizen package filename");
  }
  const matches = String(sums).replace(/\r\n?/g, "\n")
    .split("\n")
    .map((line) => /^([0-9a-f]{64})  (Starlit-Tizen-\d+\.\d+\.\d+\.wgt)$/.exec(line.trim()))
    .filter((match) => match?.[2] === filename);
  if (matches.length !== 1) {
    throw new Error("Package must have exactly one SHA-256 checksum entry");
  }
  return matches[0][1].toLowerCase();
}

export function releaseAssetUrls(update) {
  if (!update || !/^v\d+\.\d+\.\d+$/.test(update.tag) ||
    update.assetName !== `Starlit-Tizen-${update.tag.slice(1)}.wgt`) {
    throw new Error("Release and package versions do not match");
  }
  const prefix = `${RELEASE_ASSET_BASE}/${update.tag}/`;
  return {
    wgt: prefix + update.assetName,
    checksums: prefix + "SHA256SUMS-Tizen"
  };
}

async function fetchWithTimeout(fetchImpl, url, timeoutMs = 7000) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetchImpl(url, {
      signal: controller.signal,
      cache: "no-store",
      headers: { Accept: "*/*" }
    });
    if (!response?.ok) throw new Error(`Request failed (HTTP ${response?.status || 0})`);
    return response;
  } finally {
    clearTimeout(timeout);
  }
}

export async function isSdbReady(tvIp, timeoutMs = 1500) {
  if (!isLanIp(tvIp)) return false;
  return new Promise((resolveReady) => {
    const socket = connect({ host: tvIp, port: 26101 });
    let settled = false;
    const finish = (ready) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      resolveReady(ready);
    };
    socket.setTimeout(timeoutMs, () => finish(false));
    socket.once("connect", () => finish(true));
    socket.once("error", () => finish(false));
  });
}

export async function readTvAppStatus(tvIp, fetchImpl = globalThis.fetch) {
  if (!isLanIp(tvIp)) throw new Error("Expected a private LAN IPv4 address for the TV");
  const response = await fetchWithTimeout(fetchImpl,
    `http://${tvIp}:8001/api/v2/applications/${APP_ID}`);
  const app = await response.json();
  if (app?.id !== APP_ID || !/^\d+\.\d+\.\d+$/.test(String(app.version || ""))) {
    throw new Error("Cannot verify installed Starlit TV identity and version");
  }
  return { version: app.version, running: app.running === true, visible: app.visible === true };
}

export async function downloadVerifiedWgt(update, fetchImpl = globalThis.fetch) {
  const urls = releaseAssetUrls(update);
  const sumsResponse = await fetchWithTimeout(fetchImpl, urls.checksums);
  const sumsBytes = Buffer.from(await sumsResponse.arrayBuffer());
  if (sumsBytes.length > MAX_SUMS_BYTES) throw new Error("Checksum file is too large");
  const expected = parseExpectedWgtSha256(sumsBytes.toString("utf8"), update.assetName);
  const wgtResponse = await fetchWithTimeout(fetchImpl, urls.wgt, 30000);
  const declaredSize = Number(wgtResponse.headers?.get?.("content-length") || 0);
  if (declaredSize > MAX_WGT_BYTES) throw new Error("TV package exceeds the size limit");
  const bytes = Buffer.from(await wgtResponse.arrayBuffer());
  if (bytes.length === 0 || bytes.length > MAX_WGT_BYTES) {
    throw new Error("TV package is empty or exceeds the size limit");
  }
  const received = createHash("sha256").update(bytes).digest("hex");
  if (received !== expected) {
    throw new Error("TV package checksum mismatch; installation blocked");
  }
  return { bytes, sha256: received };
}

function runCli(executable, args, { spawnImpl = spawn, timeoutMs = 90_000 } = {}) {
  return new Promise((resolveRun, rejectRun) => {
    const child = spawnImpl(executable, args, {
      windowsHide: true,
      shell: false,
      stdio: ["ignore", "pipe", "pipe"]
    });
    let stdout = "";
    let stderr = "";
    const timer = setTimeout(() => child.kill(), timeoutMs);
    child.stdout?.on("data", (chunk) => { stdout = (stdout + chunk).slice(-32_000); });
    child.stderr?.on("data", (chunk) => { stderr = (stderr + chunk).slice(-8_000); });
    child.on("error", (error) => { clearTimeout(timer); rejectRun(error); });
    child.on("close", (code) => {
      clearTimeout(timer);
      if (code !== 0) return rejectRun(new Error(`Apps2Samsung exited with ${code}: ${stderr.slice(-300)}`));
      resolveRun(stdout);
    });
  });
}

export function parseInstallCliResult(output) {
  // Apps2Samsung prints progress before its final pretty-printed --json
  // result. JSON.parse(stdout) would reject a successful installation.
  const lines = String(output || "").replace(/\r/g, "").split("\n");
  for (let i = lines.length - 1; i >= 0; i--) {
    if (!lines[i].trimStart().startsWith("{")) continue;
    try {
      const result = JSON.parse(lines.slice(i).join("\n").trim());
      if (result && typeof result === "object" && !Array.isArray(result)) {
        return result;
      }
    } catch (_) {
      // This line was diagnostic text, not the final JSON payload.
    }
  }
  throw new Error("Installer did not return JSON verification");
}

export async function updateSideloadedTv({
  tvIp,
  localIp = null,
  install = false,
  cli = process.env.STARLIT_APPS2SAMSUNG_CLI || "apps2samsung-cli",
  fetchImpl = globalThis.fetch,
  runCliImpl = runCli,
  sdbReadyImpl = isSdbReady
} = {}) {
  if (!isLanIp(tvIp) || (localIp && !isLanIp(localIp))) {
    throw new Error("TV and optional host addresses must be private LAN IPv4 addresses");
  }
  let current;
  try {
    current = await readTvAppStatus(tvIp, fetchImpl);
  } catch (error) {
    return { status: "skipped", reason: "TV_OFFLINE_OR_UNVERIFIED", detail: error.message };
  }
  if (current.running || current.visible) {
    return { status: "skipped", reason: "APP_IN_USE", currentVersion: current.version };
  }
  const update = await getLatestAppUpdateWithRetry({
    currentVersion: current.version, fetchImpl, maxAttempts: 2
  });
  if (!update) return { status: "current", currentVersion: current.version };
  if (!isRemoteAppVersionNewer(update.tag, current.version)) {
    return { status: "current", currentVersion: current.version };
  }
  if (!install) {
    return { status: "available", currentVersion: current.version, nextVersion: update.tag };
  }
  // Skip before any download if SDB cannot take a direct install. Apps2Samsung
  // may otherwise fall back to TV-Store, which prompts on the TV screen.
  if (!(await sdbReadyImpl(tvIp))) {
    return { status: "skipped", reason: "SDB_NOT_READY", currentVersion: current.version };
  }
  const { bytes, sha256 } = await downloadVerifiedWgt(update, fetchImpl);
  // A viewer may start Starlit while the download is in flight. Recheck
  // immediately before installation rather than interrupt a new session.
  let beforeInstall;
  try {
    beforeInstall = await readTvAppStatus(tvIp, fetchImpl);
  } catch (error) {
    return { status: "skipped", reason: "TV_OFFLINE_OR_UNVERIFIED", detail: error.message };
  }
  if (beforeInstall.running || beforeInstall.visible) {
    return { status: "skipped", reason: "APP_IN_USE", currentVersion: beforeInstall.version };
  }
  if (beforeInstall.version !== current.version) {
    return { status: "skipped", reason: "VERSION_CHANGED", currentVersion: beforeInstall.version };
  }
  const directory = await mkdtemp(join(tmpdir(), "starlit-tizen-update-"));
  try {
    const packagePath = join(directory, basename(update.assetName));
    await writeFile(packagePath, bytes, { flag: "wx" });
    const args = [
      "install", packagePath, "--tv", tvIp,
      "--tizen-cert", "public", "--no-browser",
      "--login-timeout", "15", "--json",
      ...(localIp ? ["--local-ip", localIp] : [])
    ];
    // No --yes, --allow-remove, --remove-first or --launch. Certificate or
    // Developer Mode errors must stop, not erase data or interrupt viewing.
    const output = await runCliImpl(cli, args);
    const result = parseInstallCliResult(output);
    if (result?.ok !== true || result?.via === "tvstore" || result?.appId !== APP_ID) {
      throw new Error("Installer did not confirm a direct, in-place Starlit TV update");
    }
    const installed = await readTvAppStatus(tvIp, fetchImpl);
    if (installed.version !== update.tag.slice(1)) {
      throw new Error("Installer returned success but the TV version is not verified");
    }
    return { status: "installed", version: installed.version, sha256 };
  } finally {
    // This directory is uniquely created by this invocation and never holds
    // source, signing keys or user data; release assets remain on GitHub.
    await rm(directory, { recursive: true, force: true });
  }
}

function parseArgs(argv) {
  const args = { install: false };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--install") args.install = true;
    else if (["--tv", "--local-ip", "--cli"].includes(arg)) {
      const value = argv[++index];
      if (!value || value.startsWith("--")) throw new Error(`Missing ${arg} value`);
      args[{ "--tv": "tvIp", "--local-ip": "localIp", "--cli": "cli" }[arg]] = value;
    } else throw new Error(`Unknown argument ${arg}`);
  }
  if (!args.tvIp) throw new Error("Usage: node scripts/update-sideloaded-tv.mjs --tv TV_LAN_IP [--install] [--local-ip HOST_LAN_IP] [--cli PATH]");
  return args;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  updateSideloadedTv(parseArgs(process.argv.slice(2)))
    .then((result) => { process.stdout.write(JSON.stringify(result) + "\n"); })
    .catch((error) => {
      process.stderr.write(`Tizen update skipped: ${error.message}\n`);
      process.exitCode = 1;
    });
}
