import { access, mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { readAppMetadata } from "./appMetadata.mjs";

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const artifactsDir = path.join(rootDir, "artifacts");
const staging = path.join(rootDir, ".cache", "webos-package");
const appId = "app.starlit.webos";
const serviceIds = [appId + ".service", appId + ".plugin.service"];
const arMagic = Buffer.from("!<arch>\n", "ascii");

export function verifyArPackage(bytes) {
  if (bytes.length < 8 || !bytes.subarray(0, 8).equals(arMagic)) {
    throw new Error("Not a webOS IPK (missing ar archive header)");
  }
  let offset = 8;
  const names = new Set();
  while (offset < bytes.length) {
    if (bytes.length - offset < 60) throw new Error("Truncated IPK member header");
    const header = bytes.subarray(offset, offset + 60);
    if (header[58] !== 0x60 || header[59] !== 0x0a) throw new Error("Invalid IPK ar member");
    const name = header.subarray(0, 16).toString("ascii").trim().replace(/\/$/, "");
    const lengthText = header.subarray(48, 58).toString("ascii").trim();
    if (!/^\d+$/.test(lengthText)) throw new Error("Invalid IPK member size");
    const size = Number(lengthText);
    if (!Number.isSafeInteger(size) || size < 0 || offset + 60 + size > bytes.length) {
      throw new Error("Truncated or oversized IPK member");
    }
    names.add(name);
    offset += 60 + size + (size % 2);
  }
  if (!names.has("data.tar.gz") && !names.has("data.tar.xz")) {
    throw new Error("IPK has no application payload");
  }
  if (!names.has("control.tar.gz") && !names.has("control.tar.xz")) {
    throw new Error("IPK has no control metadata");
  }
}
async function verifyStage() {
  const app = JSON.parse(await readFile(path.join(staging, "app", "appinfo.json"), "utf8"));
  if (app.id !== appId || app.title !== "Starlit TV" ||
      JSON.stringify(app.services) !== JSON.stringify(serviceIds)) {
    throw new Error("webOS app identity or companion services do not match Starlit");
  }
  for (const service of serviceIds) {
    const manifest = JSON.parse(await readFile(path.join(staging, service, "services.json"), "utf8"));
    const packageJson = JSON.parse(await readFile(path.join(staging, service, "package.json"), "utf8"));
    if (manifest.id !== service || packageJson.name !== service ||
        !Array.isArray(manifest.services) || !manifest.services.length) {
      throw new Error("Invalid companion service: " + service);
    }
    await access(path.join(staging, service, "src", "index.js"));
  }
  const html = await readFile(path.join(staging, "app", "index.html"), "utf8");
  if (!html.includes('window.__NUVIO_PLATFORM__ = "webos"') ||
      !html.includes(serviceIds[1]) || html.includes("<title>Nuvio")) {
    throw new Error("webOS app boot configuration is invalid");
  }
  const bundle = await readFile(path.join(staging, "app", "app.bundle.js"), "utf8");
  if (bundle.includes("space.nuvio.webos.service") || bundle.includes("space.nuvio.webos.plugin.service")) {
    throw new Error("Upstream service identity remains in application bundle");
  }
  await access(path.join(staging, "app", "LICENSE"));
  await access(path.join(staging, "app", "UPSTREAM.json"));
}
export async function verifyWebOsArtifact({ verify = false } = {}) {
  const { version } = await readAppMetadata();
  const file = "Starlit-WebOS-" + version + ".ipk";
  await mkdir(artifactsDir, { recursive: true });
  if (!verify) {
    await verifyStage();
    const generated = path.join(artifactsDir, appId + "_" + version + "_all.ipk");
    await rename(generated, path.join(artifactsDir, file));
  }
  const bytes = await readFile(path.join(artifactsDir, file));
  verifyArPackage(bytes);
  const sha256 = createHash("sha256").update(bytes).digest("hex");
  const checksum = sha256 + "  " + file + "\n";
  const checksumPath = path.join(artifactsDir, "SHA256SUMS-WebOS");
  if (verify) {
    if ((await readFile(checksumPath, "utf8")) !== checksum) throw new Error("webOS artifact checksum mismatch");
  } else {
    await writeFile(checksumPath, checksum, "utf8");
    await writeFile(path.join(artifactsDir, "webos-package-metadata.json"),
      JSON.stringify({ version, appId, filename: file, sha256, status: "NOT DEVICE-VERIFIED" }, null, 2) + "\n");
  }
  console.log("Verified Starlit webOS IPK " + file + ": " + sha256);
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  verifyWebOsArtifact({ verify: process.argv.includes("--verify") }).catch(error => {
    console.error(error);
    process.exitCode = 1;
  });
}
