import { access, mkdir, mkdtemp, readFile, rename, rm, writeFile } from "node:fs/promises";
import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const cacheDir = path.join(rootDir, ".cache");
const destination = path.join(cacheDir, "webos-upstream");
const sourceURL = "https://github.com/NuvioMedia/NuvioTVSmart.git";
const requiredFiles = [
  "LICENSE", "services/plugin-http.cjs",
  "services/webos/package.json", "services/webos/services.json",
  "services/webos/src/index.js", "services/webos/src/serverHost.js",
  "services/webos/plugin/src/index.js", "services/webos/plugin/services.json",
  "services/webos/runtime/media-http.cjs", "services/webos/runtime/dts/ffmpeg-core.wasm"
];
async function git(args, cwd) {
  await new Promise((resolve, reject) => {
    const child = spawn("git", args, { cwd, shell: false, stdio: "inherit" });
    child.on("error", reject);
    child.on("exit", (code) => code === 0 ? resolve() : reject(new Error("git failed: " + args[0])));
  });
}
async function verify(root) {
  for (const name of requiredFiles) await access(path.join(root, name));
}
export async function fetchPinnedWebOsServices() {
  const upstream = JSON.parse(await readFile(path.join(rootDir, "UPSTREAM.json"), "utf8"));
  if (upstream.repository !== "https://github.com/NuvioMedia/NuvioTVSmart" ||
      !/^[0-9a-f]{40}$/.test(upstream.commit)) {
    throw new Error("Unexpected webOS upstream pin; refusing mutable downloads");
  }
  try {
    const marker = JSON.parse(await readFile(path.join(destination, ".starlit-webos-pin.json"), "utf8"));
    if (marker.commit !== upstream.commit || marker.repository !== upstream.repository) {
      throw new Error("Cached webOS sources belong to a different upstream revision");
    }
    await verify(destination);
    return destination;
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
    if (await access(destination).then(() => true, () => false)) {
      throw new Error("Incomplete webOS source cache; inspect it rather than replacing unknown files");
    }
  }
  await mkdir(cacheDir, { recursive: true });
  const temporary = await mkdtemp(path.join(cacheDir, "webos-fetch-"));
  try {
    await git(["init", "-q"], temporary);
    await git(["remote", "add", "origin", sourceURL], temporary);
    await git(["sparse-checkout", "init", "--no-cone"], temporary);
    await git(["sparse-checkout", "set", "--no-cone", "/services/webos/", "/services/plugin-http.cjs", "/LICENSE"], temporary);
    await git(["-c", "protocol.version=2", "fetch", "--no-tags", "--depth", "1", "--filter=blob:none", "origin", upstream.commit], temporary);
    await git(["checkout", "--detach", "FETCH_HEAD"], temporary);
    const head = await new Promise((resolve, reject) => {
      let output = "";
      const child = spawn("git", ["rev-parse", "HEAD"], { cwd: temporary, stdio: ["ignore", "pipe", "inherit"] });
      child.stdout.on("data", (data) => output += data);
      child.on("error", reject);
      child.on("exit", (code) => code === 0 ? resolve(output.trim()) : reject(new Error("Cannot verify upstream commit")));
    });
    if (head !== upstream.commit) throw new Error("webOS upstream commit checksum mismatch");
    await verify(temporary);
    await writeFile(path.join(temporary, ".starlit-webos-pin.json"), JSON.stringify({ repository: upstream.repository, commit: head }) + "\n");
    await rename(temporary, destination);
    return destination;
  } catch (error) {
    await rm(temporary, { recursive: true, force: true });
    throw error;
  }
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  fetchPinnedWebOsServices().then(() => console.log("Pinned GPL webOS services verified")).catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
}
