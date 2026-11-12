import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
const require=createRequire(import.meta.url);
const network=require("../services/tizen/runtime/proxy-network.cjs");
test("clean runtime provenance matches the bundled bytes and excludes the opaque engine",async()=>{
 const raw=await readFile(new URL("../services/tizen/runtime/media-http.cjs",import.meta.url));
 const manifest=JSON.parse(await readFile(new URL("../services/tizen/runtime/runtime-manifest.json",import.meta.url),"utf8"));
 assert(!raw.includes(Buffer.from("\r\n")), "runtime bytes must remain stable across platform checkouts");
 assert.equal(manifest.engine,"torrent-stream@1.2.1");assert.equal(require("node:crypto").createHash("sha256").update(raw).digest("hex"),manifest.sha256);
 assert(!raw.toString().includes("server.js.LICENSE.txt"));assert(manifest.modules.every(item=>item.license || item.files?.length));
});
test("proxy blocks private IPv4/IPv6 and credentials",()=>{
 for(const address of ["0.0.0.0","127.0.0.1","10.0.0.2","172.31.1.2","192.168.0.1","169.254.169.254","100.64.0.1","::1","::ffff:127.0.0.1","fc00::1","fe80::1","2002:7f00:1::"]) assert(network.blockedAddress(address),address);
 for(const address of ["8.8.8.8","1.1.1.1","2606:4700:4700::1111"]) assert(!network.blockedAddress(address),address);
 for(const url of ["http://127.0.0.1/","http://u:p@example.com/","file:///etc/passwd","https://[::ffff:127.0.0.1]/"]) assert.throws(()=>network.origin(url));
});
test("cross-origin proxy redirects preserve Range and remove all credentials",async()=>{
 const calls=[];const fake=async(url,opts)=>{calls.push({url,opts});return{}};fake.Headers=Headers;
 const options={};const headers=new Headers({Authorization:"Bearer secret",Cookie:"session=secret","X-Api-Key":"secret",Range:"bytes=10-20","User-Agent":"TV"});
 const parse=require("node:url").parse;
 await network.protectedFetch(parse("https://cdn.example/a"),{method:"GET"},headers,options,fake);
 await network.protectedFetch(parse("https://other.example/a"),{method:"GET"},headers,options,fake);
 assert.equal(calls[0].opts.headers.get("authorization"),"Bearer secret");
 for(const key of ["authorization","cookie","x-api-key"]) assert.equal(calls[1].opts.headers.get(key),null);
 assert.equal(calls[1].opts.headers.get("range"),"bytes=10-20");
 assert.equal(calls[1].opts.agent.options.rejectUnauthorized,true);
});
