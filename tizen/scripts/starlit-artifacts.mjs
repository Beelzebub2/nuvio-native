import { readFile, writeFile, mkdir } from "node:fs/promises";
import { createHash } from "node:crypto";
import JSZip from "jszip";
import { readAppMetadata } from "./appMetadata.mjs";
const { version } = await readAppMetadata();
const file = `Starlit-Tizen-${version}.wgt`;
const bytes = await readFile(new URL(`../artifacts/${file}`, import.meta.url));
const zip = await JSZip.loadAsync(bytes);
const required = ["config.xml","index.html","app.bundle.js","icon.png","services/tizen/enginefs-service.js","services/tizen/runtime/media-http.cjs","services/tizen/runtime/proxy-network.cjs","services/tizen/runtime/trusted-control.cjs","services/tizen/runtime/protected-media-proxy.cjs","services/tizen/runtime/THIRD-PARTY-LICENSES.txt","services/tizen/runtime/runtime-manifest.json","services/tizen/plugin-service.js","services/plugin-http.cjs","LICENSE","UPSTREAM.json"];
for (const name of required) if (!zip.file(name)) throw new Error(`Missing required WGT file: ${name}`);
const config = await zip.file("config.xml").async("string");
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
