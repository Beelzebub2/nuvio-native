import { readFile, writeFile, mkdir } from "node:fs/promises";
import { createHash } from "node:crypto";
import JSZip from "jszip";
import vm from "node:vm";
import { readAppMetadata } from "./appMetadata.mjs";
import { TIZEN_MEDIA_KEY_NAMES } from "../js/platform/tizen/tizenRemoteKeys.js";
const { version } = await readAppMetadata();
const file = `Starlit-Tizen-${version}.wgt`;
const bytes = await readFile(new URL(`../artifacts/${file}`, import.meta.url));
const zip = await JSZip.loadAsync(bytes);
const required = ["config.xml","index.html","main.js","app.bundle.js","icon.png","services/tizen/enginefs-service.js","services/tizen/runtime/media-http.cjs","services/tizen/runtime/proxy-network.cjs","services/tizen/runtime/trusted-control.cjs","services/tizen/runtime/protected-media-proxy.cjs","services/tizen/runtime/THIRD-PARTY-LICENSES.txt","services/tizen/runtime/runtime-manifest.json","services/tizen/plugin-service.js","services/plugin-http.cjs","LICENSE","UPSTREAM.json"];
for (const name of required) if (!zip.file(name)) throw new Error(`Missing required WGT file: ${name}`);
const config = await zip.file("config.xml").async("string");
const mainJs = await zip.file("main.js").async("string");
const remoteListMatch = /var remoteKeys = (\[[^\r\n]+\]);/.exec(mainJs);
if (!remoteListMatch) throw new Error("Missing Tizen remote key registration");
const registeredKeys = JSON.parse(remoteListMatch[1]);
if (JSON.stringify(registeredKeys) !== JSON.stringify(TIZEN_MEDIA_KEY_NAMES)) throw new Error("Remote key registration list does not match the client");
if (["Back", "Return", "Enter", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight"].some(key => registeredKeys.includes(key))) throw new Error("Mandatory Samsung remote key was incorrectly registered");
// Run the real packaged ES5 bootstrap with a simulated Samsung input device.
// This verifies the optional-key filter and partial-registration fallback.
function runRemoteBootstrap({ supportedNames, failName } = {}) {
  const registered = [];
  const tvinputdevice = {
    getSupportedKeys() { return (supportedNames || TIZEN_MEDIA_KEY_NAMES).map(name => ({ name })); },
    registerKey(name) {
      if (name === failName) throw new Error("Unsupported optional remote button");
      registered.push(name);
    }
  };
  const window = { tizen: { tvinputdevice }, NuvioBootGuard: { runCompatibilityGate() {} } };
  vm.runInNewContext(mainJs, { window }, { timeout: 1000 });
  return { registered, completed: window.__NUVIO_TIZEN_MEDIA_KEYS_REGISTERED__ };
}
const remoteSubset = runRemoteBootstrap({ supportedNames: ["Back", "Enter", "MediaPlayPause", "MediaTrackNext"] });
if (JSON.stringify(remoteSubset.registered) !== JSON.stringify(["MediaPlayPause", "MediaTrackNext"]) || remoteSubset.completed !== true) throw new Error("Tizen bootstrap registered mandatory or unsupported keys");
const remotePartialFailure = runRemoteBootstrap({ supportedNames: ["Back", "MediaPlayPause", "MediaTrackNext"], failName: "MediaTrackNext" });
if (JSON.stringify(remotePartialFailure.registered) !== JSON.stringify(["MediaPlayPause"]) || remotePartialFailure.completed !== false) throw new Error("Tizen bootstrap did not expose failed media-key registration to adapter");
for (const value of ['id="StarlitTV1.StarlitTV"', 'package="StarlitTV1"', '<name>Starlit TV</name>', 'id="StarlitTV1.EngineFsService"', 'src="services/tizen/enginefs-service.js"', 'required_version="4.0"']) if (!config.includes(value)) throw new Error(`Invalid package identity: ${value}`);
if (zip.file("author-signature.xml") || zip.file("signature1.xml")) throw new Error("Distribution WGT must be unsigned for per-TV signing");
const runtimeBytes = await zip.file("services/tizen/runtime/media-http.cjs").async("nodebuffer");
const runtime = runtimeBytes.toString("utf8");
const provenance=JSON.parse(await zip.file("services/tizen/runtime/runtime-manifest.json").async("string"));
if(provenance.engine!=="torrent-stream@1.2.1" || !runtime.includes("starlit-torrent") || runtime.includes("server.js.LICENSE.txt")) throw new Error("Unrecognized or unlicensed local runtime");
if(createHash("sha256").update(runtimeBytes).digest("hex")!==provenance.sha256) throw new Error("Runtime provenance checksum mismatch");
if(!Array.isArray(provenance.modules)||!provenance.modules.length||provenance.modules.some(module=>!module.name||!module.license)) throw new Error("Missing runtime module license provenance");
if(!(await zip.file("services/tizen/runtime/THIRD-PARTY-LICENSES.txt").async("string")).trim()) throw new Error("Missing runtime license notices");
const env = await zip.file("nuvio.env.js").async("string");
if (/https:\/\/nuvio\.tv|supabase\.co/i.test(env)) throw new Error("Upstream cloud configuration is forbidden");
const sha256 = createHash("sha256").update(bytes).digest("hex");
const sums = `${sha256}  ${file}\n`;
if (process.argv.includes("--verify")) {
  if (await readFile(new URL("../artifacts/SHA256SUMS-Tizen", import.meta.url), "utf8") !== sums) throw new Error("Checksum mismatch");
} else {
  await writeFile(new URL("../artifacts/SHA256SUMS-Tizen", import.meta.url), sums);
  await writeFile(new URL("../artifacts/package-metadata.json", import.meta.url), JSON.stringify({ version, filename: file, sha256, status: "NOT DEVICE-VERIFIED" },null,2) + "\n");
}
if(!process.argv.includes("--verify")) await writeFile(new URL("../artifacts/apps2samsung-feed.json",import.meta.url),JSON.stringify({schemaVersion:2,providers:[{id:"starlit-tizen",url:"https://api.github.com/repos/Beelzebub2/nuvio-native/releases",displayName:"Starlit TV",take:1,expandAssets:false,certLevel:"public",category:"media",buildInfo:{name:"Starlit TV",description:"Starlit account sync and user-supplied sources for Samsung Tizen, adapted from NuvioTVSmart."}}]},null,2)+"\n");
console.log(`Verified unsigned ${file}: ${sha256}`);
