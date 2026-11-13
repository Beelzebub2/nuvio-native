import { access, cp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { constants as fsConstants } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { readAppMetadata, syncVersionFiles } from "./appMetadata.mjs";
import { compatibilityPolicy } from "./compatibilityPolicy.mjs";
import { runWebOsToolsBinary } from "./aresCli.mjs";
import { pruneCssFileForPlatform } from "./pruneCssForPlatform.mjs";
import { fetchPinnedWebOsServices } from "./fetch-webos-services.mjs";
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, "..");
const distDir = path.join(rootDir, "dist");
const cacheDir = path.join(rootDir, ".cache");
const stagingDir = path.join(cacheDir, "webos-package");
const appStageDir = path.join(stagingDir, "app");
const serviceStageDir = path.join(stagingDir, "app.starlit.webos.service");
const pluginServiceStageDir = path.join(stagingDir, "app.starlit.webos.plugin.service");
const appName = "Starlit TV";
const webOsServiceId = "app.starlit.webos.service";
const webOsPluginServiceId = "app.starlit.webos.plugin.service";
const upstreamRootDir = path.join(cacheDir, "webos-upstream");
const webOsServiceSourceDir = path.join(upstreamRootDir, "services", "webos");
const artifactsDir = path.join(rootDir, "artifacts");
const appId = "app.starlit.webos";
const webOsPluginServiceSourceDir = path.join(upstreamRootDir, "services", "webos", "plugin");
const webOsRuntimeScriptPath = "assets/libs/webOSTV.js";
async function assertDistExists() {
  try {
    await access(path.join(distDir, "app.bundle.js"), fsConstants.R_OK);
    await access(path.join(distDir, "appinfo.json"), fsConstants.R_OK);
  } catch {
    throw new Error(`Build output not found at ${distDir}. Run "npm run build" first.`);
  }
}
async function pathExists(filePath) {
  try {
    await access(filePath, fsConstants.F_OK);
    return true;
  } catch {
    return false;
  }
}
function validateWebOsAppInfo(appInfo) {
  const requiredFields = ["id", "title", "type", "main", "icon", "version"];
  const missingField = requiredFields.find((field) => !String(appInfo?.[field] || "").trim());
  if (missingField) {
    throw new Error(`webOS appinfo.json is missing required field: ${missingField}`);
  }
  if (Object.prototype.hasOwnProperty.call(appInfo, "requiredVersion")) {
    throw new Error(
      "webOS appinfo.json contains unsupported requiredVersion metadata. " +
        "The app runtime compatibility gate is maintained in scripts/compatibilityPolicy.mjs."
    );
  }
  if (!/^#[0-9a-f]{6}(?:[0-9a-f]{2})?$/i.test(String(appInfo.iconColor || ""))) {
    throw new Error("webOS appinfo.json requires a valid iconColor in #RRGGBB or #RRGGBBAA form.");
  }
  if (String(appInfo.title).length > 20) {
    throw new Error("webOS appinfo.json title must be 20 characters or fewer.");
  }
  if (String(appInfo.appDescription || "").length > 60) {
    throw new Error("webOS appinfo.json appDescription must be 60 characters or fewer.");
  }
  const appId = String(appInfo.id);
  const services = Array.isArray(appInfo.services) ? appInfo.services : [];
  if (services.some((serviceId) => !String(serviceId).startsWith(`${appId}.`))) {
    throw new Error("webOS service IDs must begin with the application ID followed by a dot.");
  }
}
async function validatePngDimensions(filePath, expectedWidth, expectedHeight, label) {
  const image = await readFile(filePath);
  const isPng =
    image.length >= 24 &&
    image.readUInt32BE(0) === 0x89504e47 &&
    image.readUInt32BE(4) === 0x0d0a1a0a;
  if (
    !isPng ||
    image.readUInt32BE(16) !== expectedWidth ||
    image.readUInt32BE(20) !== expectedHeight
  ) {
    throw new Error(
      `${label} must be a PNG of exactly ${expectedWidth}x${expectedHeight}: ${filePath}`
    );
  }
}
async function validateOpaquePng(filePath, label) {
  const image = await readFile(filePath);
  const isPng =
    image.length >= 26 &&
    image.readUInt32BE(0) === 0x89504e47 &&
    image.readUInt32BE(4) === 0x0d0a1a0a;
  const colorType = isPng ? image.readUInt8(25) : -1;
  if (!isPng || colorType === 4 || colorType === 6) {
    throw new Error(`${label} must use an opaque PNG color type: ${filePath}`);
  }
  let offset = 8;
  while (offset + 12 <= image.length) {
    const chunkLength = image.readUInt32BE(offset);
    const chunkType = image.toString("ascii", offset + 4, offset + 8);
    if (chunkType === "tRNS") {
      throw new Error(`${label} must not contain a transparency chunk: ${filePath}`);
    }
    offset += chunkLength + 12;
  }
}
function validateWebOsServiceManifest(serviceManifest, expectedId = webOsServiceId) {
  if (String(serviceManifest?.id || "") !== expectedId) {
    throw new Error(`webOS services.json must use service id ${expectedId}.`);
  }
  const services = Array.isArray(serviceManifest?.services) ? serviceManifest.services : [];
  if (
    !services.length ||
    services.some(
      (service) =>
        !String(service?.name || "").startsWith(`${expectedId}.`) &&
        String(service?.name || "") !== expectedId
    )
  ) {
    throw new Error(
      "Every webOS services.json service name must begin with the application service ID."
    );
  }
}
async function resolveWebOsScriptPath(targetDir) {
  const webOsScriptPath = path.join(targetDir, webOsRuntimeScriptPath);
  if (!(await pathExists(webOsScriptPath))) {
    return "";
  }
  return webOsRuntimeScriptPath;
}
function buildWebOsIndexHtml({ webOsScriptPath = "" } = {}) {
  const webOsScriptTag = webOsScriptPath ? `  <script src="${webOsScriptPath}"></script>\n` : "";
  const compatibilityOptions = JSON.stringify({
    platform: "webos",
    minVersion: Number.parseInt(compatibilityPolicy.webOsRequiredVersion, 10),
    minChrome: compatibilityPolicy.webOsChromiumVersion,
    requiredLabel: `LG webOS ${compatibilityPolicy.webOsRequiredVersion}+ · Chromium ${compatibilityPolicy.webOsChromiumVersion}+ (${compatibilityPolicy.webOsSupportYear}+)`
  });
  return `<!DOCTYPE html>
<html lang="en" class="no-flex-gap no-css-math no-backdrop-filter no-aspect-ratio">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <meta http-equiv="X-UA-Compatible" content="IE=edge" />
  <title>${appName}</title>
  <script src="assets/runtime/legacy-features.js"></script>
  <link rel="stylesheet" href="css/bundle.css" />
</head>
<body>
  <script src="boot-guard.js"></script>
  <script src="core-js.bundle.js" onerror="window.NuvioBootGuard &amp;&amp; window.NuvioBootGuard.scriptFailed(this.src)"></script>
  <script>window.__NUVIO_PLATFORM__ = "webos";</script>
  <script>window.__NUVIO_WEBOS_PLUGIN_SERVICE_ENABLED__ = true; window.__NUVIO_WEBOS_PLUGIN_SERVICE_ID__ = "${webOsPluginServiceId}";</script>
  <script src="nuvio.env.js"></script>
  <script src="assets/libs/qrcode-generator.js"></script>
${webOsScriptTag}  <script>
    window.NuvioBootGuard.runCompatibilityGate(${compatibilityOptions}, function startNuvioApp() {
      window.NuvioBootGuard.loadScript("app.bundle.js");
    });
  </script>
</body>
</html>
`;
}
async function stageApp() {
  const { version } = await readAppMetadata();
  await cp(distDir, appStageDir, { recursive: true });
  await pruneCssFileForPlatform(path.join(appStageDir, "css", "bundle.css"), "webos");
  const appInfoPath = path.join(appStageDir, "appinfo.json");
  const appInfo = JSON.parse(await readFile(appInfoPath, "utf8"));
  appInfo.id = appId;
  appInfo.title = appName;
  appInfo.vendor = "Starlit contributors";
  appInfo.appDescription = "Watch your own sources with Starlit on LG webOS.";
  appInfo.version = version;
  appInfo.icon = "icon.png";
  appInfo.largeIcon = "largeIcon.png";
  appInfo.services = [webOsServiceId, webOsPluginServiceId];
  validateWebOsAppInfo(appInfo);
  await writeFile(appInfoPath, `${JSON.stringify(appInfo, null, 2)}\n`, "utf8");
  await Promise.all([
    validatePngDimensions(
      path.join(rootDir, "assets", "images", "icon.png"),
      80,
      80,
      "webOS small icon"
    ),
    validatePngDimensions(
      path.join(rootDir, "assets", "images", "largeIcon.png"),
      130,
      130,
      "webOS large icon"
    ),
    validateOpaquePng(path.join(rootDir, "assets", "images", "icon.png"), "webOS small icon"),
    validateOpaquePng(path.join(rootDir, "assets", "images", "largeIcon.png"), "webOS large icon"),
    validatePngDimensions(
      path.join(rootDir, "assets", "images", "splash.png"),
      1920,
      1080,
      "webOS splash image"
    ),
    cp(path.join(rootDir, "assets", "images", "icon.png"), path.join(appStageDir, "icon.png")),
    cp(
      path.join(rootDir, "assets", "images", "largeIcon.png"),
      path.join(appStageDir, "largeIcon.png")
    ),
    cp(path.join(rootDir, "assets", "images", "splash.png"), path.join(appStageDir, "splash.png"))
  ]);
  const webOsScriptPath = await resolveWebOsScriptPath(appStageDir);
  await cp(path.join(rootDir, "LICENSE"), path.join(appStageDir, "LICENSE"));
  await cp(path.join(rootDir, "UPSTREAM.json"), path.join(appStageDir, "UPSTREAM.json"));
  await writeFile(
    path.join(appStageDir, "index.html"),
    buildWebOsIndexHtml({ webOsScriptPath }),
    "utf8"
  );
}
// The exact upstream service revision is adapted during bundling; source remains immutable.
const starlitWebOsServiceIdentity = {
  name: "starlit-webos-identity",
  setup(builder) {
    builder.onLoad({ filter: /\.[cm]?js$/ }, async ({ path: sourcePath }) => {
      if (!sourcePath.startsWith(upstreamRootDir + path.sep)) return undefined;
      return {
        contents: (await readFile(sourcePath, "utf8")).replaceAll("space.nuvio.webos", appId),
        loader: "js"
      };
    });
  }
};

async function stageService() {
  const packageJsonPath = path.join(webOsServiceSourceDir, "package.json");
  const servicesManifestPath = path.join(webOsServiceSourceDir, "services.json");
  const packageJson = JSON.parse(await readFile(packageJsonPath, "utf8"));
  packageJson.name = webOsServiceId;
  const servicesManifest = JSON.parse((await readFile(servicesManifestPath, "utf8")).replaceAll("space.nuvio.webos", appId));
  validateWebOsServiceManifest(servicesManifest);
  await mkdir(path.join(serviceStageDir, "src"), { recursive: true });
  await mkdir(path.join(serviceStageDir, "runtime"), { recursive: true });
  await cp(
    path.join(webOsServiceSourceDir, "runtime", "dts"),
    path.join(serviceStageDir, "runtime", "dts"),
    { recursive: true }
  );
  await Promise.all([
    writeFile(
      path.join(serviceStageDir, "package.json"),
      `${JSON.stringify(packageJson, null, 2)}\n`,
      "utf8"
    ),
    writeFile(
      path.join(serviceStageDir, "services.json"),
      `${JSON.stringify(servicesManifest, null, 2)}\n`,
      "utf8"
    ),
    cp(
      path.join(webOsServiceSourceDir, "runtime", "media-http.cjs"),
      path.join(serviceStageDir, "runtime", "media-http.cjs")
    )
  ]);
  await build({
    entryPoints: [path.join(webOsServiceSourceDir, "src", "index.js")],
    outfile: path.join(serviceStageDir, "src", "index.js"),
    bundle: true,
    platform: "node",
    format: "cjs",
    target: [`node${compatibilityPolicy.webOsServiceNodeVersion}`],
    external: ["webos-service"],
    plugins: [starlitWebOsServiceIdentity],
    logLevel: "silent"
  });
}
async function stagePluginService() {
  const packageJsonPath = path.join(webOsPluginServiceSourceDir, "package.json");
  const servicesManifestPath = path.join(webOsPluginServiceSourceDir, "services.json");
  const packageJson = JSON.parse(await readFile(packageJsonPath, "utf8"));
  packageJson.name = webOsPluginServiceId;
  const servicesManifest = JSON.parse((await readFile(servicesManifestPath, "utf8")).replaceAll("space.nuvio.webos", appId));
  validateWebOsServiceManifest(servicesManifest, webOsPluginServiceId);
  await mkdir(path.join(pluginServiceStageDir, "src"), { recursive: true });
  await Promise.all([
    writeFile(
      path.join(pluginServiceStageDir, "package.json"),
      `${JSON.stringify(packageJson, null, 2)}\n`,
      "utf8"
    ),
    writeFile(
      path.join(pluginServiceStageDir, "services.json"),
      `${JSON.stringify(servicesManifest, null, 2)}\n`,
      "utf8"
    )
  ]);
  await build({
    entryPoints: [path.join(webOsPluginServiceSourceDir, "src", "index.js")],
    outfile: path.join(pluginServiceStageDir, "src", "index.js"),
    bundle: true,
    platform: "node",
    format: "cjs",
    target: [`node${compatibilityPolicy.webOsServiceNodeVersion}`],
    external: ["webos-service"],
    plugins: [starlitWebOsServiceIdentity],
    logLevel: "silent"
  });
}
async function packageWebOs() {
  await syncVersionFiles();
  await assertDistExists();
  await fetchPinnedWebOsServices();
  console.log("staging webOS package files...");
  await rm(stagingDir, { recursive: true, force: true });
  await mkdir(stagingDir, { recursive: true });
  await mkdir(artifactsDir, { recursive: true });
  await Promise.all([stageApp(), stageService(), stagePluginService()]);
  console.log("creating webOS IPK...");
  await runWebOsToolsBinary("ares-package", [
    appStageDir, serviceStageDir, pluginServiceStageDir, "--outdir", artifactsDir
  ]);
}
try {
  await packageWebOs();
} catch (error) {
  console.error("\nwebOS packaging failed:");
  console.error(error);
  process.exitCode = 1;
}
