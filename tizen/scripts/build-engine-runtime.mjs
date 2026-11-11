import {cp} from "node:fs/promises";
import {spawnSync} from "node:child_process";
const result=spawnSync(process.execPath,["build.cjs"],{cwd:new URL("../engine-runtime",import.meta.url),stdio:"inherit"});
if(result.status!==0)throw new Error("Clean licensed local runtime build failed");
for(const name of ["media-http.cjs","THIRD-PARTY-LICENSES.txt","runtime-manifest.json"])await cp(new URL(`../engine-runtime/${name}`,import.meta.url),new URL(`../services/tizen/runtime/${name}`,import.meta.url));
