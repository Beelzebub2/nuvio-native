import {createServer} from "node:http";
import {readFile,mkdir,writeFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";
import path from "node:path";
import assert from "node:assert/strict";
import {build} from "esbuild";
import {chromium} from "@playwright/test";
const root=fileURLToPath(new URL("..",import.meta.url));
await mkdir(path.join(root,".cache"),{recursive:true});await mkdir(path.join(root,"test-results"),{recursive:true});
await build({stdin:{contents:`import "./js/app.js";import {Router} from "./js/ui/navigation/router.js";import {ExperienceModeStore} from "./js/data/local/experienceModeStore.js";import {addonRepository} from "./js/data/repository/addonRepository.js";import {savedLibraryRepository} from "./js/data/repository/savedLibraryRepository.js";import {TizenStreamingServerResolver} from "./js/core/p2p/tizenStreamingServerResolver.js";ExperienceModeStore.set({mode:"ADVANCED",addonSetupSkipped:true});globalThis.__starlitSmoke={Router,addonRepository,savedLibraryRepository,TizenStreamingServerResolver};`,resolveDir:root},outfile:path.join(root,".cache/browser-smoke.js"),bundle:true,format:"iife",define:{__NUVIO_APP_VERSION__:'"0.1.0"'},target:"chrome63"});
const poster='<svg xmlns="http://www.w3.org/2000/svg" width="600" height="900"><rect width="600" height="900" fill="#17223c"/><circle cx="300" cy="390" r="145" fill="#5175cc"/><path d="M270 300L390 390L270 480Z" fill="white"/><text x="300" y="690" text-anchor="middle" fill="white" font-size="44" font-family="sans-serif">Signal test</text></svg>';
let origin;
const item=()=>({id:"synthetic-1",type:"movie",name:"Signal test",poster:origin+"/fixture/poster.svg",background:origin+"/fixture/poster.svg",description:"A generated test pattern for playback and remote navigation.",releaseInfo:"2026",imdbRating:"8.0",genres:["Test"],runtime:"1 min"});
const mime={".html":"text/html",".js":"text/javascript",".css":"text/css",".json":"application/json",".png":"image/png",".svg":"image/svg+xml",".wasm":"application/wasm",".mp4":"video/mp4",".m3u8":"application/vnd.apple.mpegurl",".ts":"video/mp2t",".mpd":"application/dash+xml",".m4s":"video/mp4"};
const server=createServer(async(req,res)=>{try{
 const pathname=new URL(req.url,"http://local").pathname;
 if(pathname==="/fixture/poster.svg"){res.setHeader("Content-Type","image/svg+xml");res.end(poster);return;}
 let payload;
 if(pathname==="/fixture/manifest.json")payload={id:"org.example.starlit.synthetic",version:"1.0.0",name:"Synthetic fixture",description:"Local generated test only",types:["movie"],resources:["catalog","meta","stream"],catalogs:[{id:"fixture",type:"movie",name:"Signal checks",extra:[{name:"search",isRequired:false}]}]};
 if(pathname.startsWith("/fixture/catalog/"))payload={metas:[item()]};
 if(pathname.startsWith("/fixture/meta/"))payload={meta:item()};
 if(pathname.startsWith("/fixture/stream/"))payload={streams:[{name:"Direct 1080p",title:"Synthetic MP4 • 1080p • generated signal",url:origin+"/media/synthetic-direct.mp4",behaviorHints:{videoSize:540000}},{name:"Torrent 720p",title:"Synthetic unavailable source • seeds: 0",infoHash:"a".repeat(40),fileIdx:1}]};
 if(payload){res.setHeader("Content-Type","application/json");res.end(JSON.stringify(payload));return;}
 const base=pathname.startsWith("/media/")?path.join(root,"tests/fixtures"):path.join(root,"dist");
 const relative=pathname.startsWith("/media/")?pathname.slice(7):pathname==="/"?"index.html":pathname.slice(1);
 const target=path.resolve(base,relative);if(!target.startsWith(base+path.sep)&&target!==base)throw new Error("Invalid path");
 const bytes=await readFile(relative==="app.bundle.js"?path.join(root,".cache/browser-smoke.js"):target);
 res.setHeader("Content-Type",mime[path.extname(target)]||"application/octet-stream");res.setHeader("Accept-Ranges","bytes");
 const match=/^bytes=(\d+)-(\d*)$/.exec(req.headers.range||"");
 if(match){const start=Number(match[1]),end=match[2]?Math.min(Number(match[2]),bytes.length-1):bytes.length-1;res.writeHead(206,{"Content-Range":`bytes ${start}-${end}/${bytes.length}`,"Content-Length":end-start+1});res.end(bytes.subarray(start,end+1));}else{res.setHeader("Content-Length",bytes.length);res.end(bytes);}
 }catch(_){res.writeHead(404);res.end();}});
await new Promise(resolve=>server.listen(0,"127.0.0.1",resolve));origin=`http://127.0.0.1:${server.address().port}`;
const browser=await chromium.launch({headless:true,args:["--autoplay-policy=no-user-gesture-required"]});
const evidence=[];
try{
 for(const size of [{width:1920,height:1080},{width:3840,height:2160}]){
  const context=await browser.newContext({viewport:size});const page=await context.newPage();const errors=[];page.on("pageerror",error=>errors.push(error.message));
  await page.route("https://**/*",route=>route.abort());
  await page.addInitScript(()=>{localStorage.setItem("skipAuthQrGate","true");localStorage.setItem("activeProfileId",'"1"');localStorage.setItem("rememberLastProfile","true");localStorage.setItem("hasEverSelectedProfile","true");});
  await page.goto(origin);await page.waitForFunction(()=>window.__starlitSmoke?.Router?.navigate&&document.getElementById("home"));
  await page.evaluate(()=>window.__starlitSmoke.Router.navigate("home",{forceReload:true}));
  const capture=async(name)=>{await page.waitForTimeout(450);await page.evaluate(()=>document.fonts.ready);assert(await page.locator("body").innerText());await page.screenshot({path:path.join(root,`test-results/${size.width}-${name}.png`)});evidence.push({viewport:size,screen:name,focused:await page.locator(".focused").count(),pageErrors:[...errors]});};
  await capture("home-empty");
  await page.evaluate(()=>window.__starlitSmoke.Router.navigate("plugin"));await capture("addons-empty");
  await page.evaluate(()=>window.__starlitSmoke.Router.navigate("experienceModeSelection"));
  await page.waitForFunction(()=>document.querySelector("#experienceModeSelection [data-mode].focused"));
  await page.keyboard.press("ArrowRight");
  assert.equal(await page.locator("#experienceModeSelection [data-mode].focused").getAttribute("data-mode"),"ADVANCED");
  await page.keyboard.press("Enter");
  await page.waitForFunction(()=>document.querySelector("#experienceModeSelection [data-layout].focused"));
  await page.keyboard.press("ArrowRight");
  assert.equal(await page.locator("#experienceModeSelection [data-layout].focused").getAttribute("data-index"),"1","remote Right must not skip the middle layout");
  await capture("onboarding-layout-focus");
  await page.waitForTimeout(750);
  await page.keyboard.press("Escape");
  await page.waitForFunction(()=>document.querySelector("#experienceModeSelection [data-mode].focused"));
  await capture("onboarding-back-mode");
  await page.evaluate(()=>window.__starlitSmoke.Router.navigate("home",{forceReload:true}));
  await page.evaluate(async url=>{await window.__starlitSmoke.addonRepository.addAddon(url);await window.__starlitSmoke.Router.navigate("home",{forceReload:true});},origin+"/fixture");
  await page.waitForFunction(()=>document.querySelector("#home")?.textContent.includes("Signal test"),{timeout:20000});await capture("home-catalog");
  assert(await page.locator("#home .focused").count()>0);await page.keyboard.press("Enter");await page.waitForFunction(()=>window.__starlitSmoke.Router.getCurrent()==="detail");await capture("home-enter-details");await page.keyboard.press("Escape");await page.waitForFunction(()=>window.__starlitSmoke.Router.getCurrent()==="home");assert(await page.locator("#home .focused").count()>0);
  await page.keyboard.press("ArrowDown");await page.keyboard.press("ArrowRight");
  await page.evaluate(()=>window.__starlitSmoke.Router.navigate("search"));await capture("search");
  await page.evaluate(async()=>{await window.__starlitSmoke.savedLibraryRepository.save({contentId:"synthetic-1",contentType:"movie",title:"Signal test"});await window.__starlitSmoke.Router.navigate("library");});await capture("library");
  await page.evaluate(()=>window.__starlitSmoke.Router.navigate("detail",{itemId:"synthetic-1",itemType:"movie"}));await page.waitForFunction(()=>document.querySelector("#detail")?.textContent.includes("Signal test"));await capture("details");
  assert(await page.locator("#detail .focused").count()>0);await page.keyboard.press("ArrowRight");await page.keyboard.press("ArrowLeft");await page.keyboard.press("Enter");await page.waitForTimeout(600);await capture("details-play-action");
  await page.evaluate(()=>window.__starlitSmoke.Router.navigate("stream",{itemId:"synthetic-1",itemType:"movie",videoId:"synthetic-1",itemTitle:"Signal test"}));await page.waitForFunction(()=>document.querySelector("#stream")?.textContent.includes("1080p"));await capture("sources");assert((await page.locator("#stream").innerText()).includes("Signal test"));
  assert(await page.locator("#stream .focused").count()>0);await page.keyboard.press("ArrowDown");await page.keyboard.press("Enter");await page.waitForFunction(()=>window.__starlitSmoke.Router.getCurrent()==="player");await page.waitForFunction(()=>document.querySelector("#videoPlayer")?.currentTime>0.2);await page.keyboard.press("ArrowDown");await capture("source-enter-player-controls");await page.keyboard.press("Escape");await page.waitForTimeout(250);if(await page.evaluate(()=>window.__starlitSmoke.Router.getCurrent())==="player")await page.keyboard.press("Escape");await page.waitForFunction(()=>window.__starlitSmoke.Router.getCurrent()!=="player");
  await page.evaluate(()=>window.__starlitSmoke.Router.navigate("settings"));await capture("settings");
  await page.evaluate(()=>window.__starlitSmoke.Router.navigate("player",{itemId:"synthetic-1",itemType:"movie",title:"Signal test",streamUrl:location.origin+"/media/synthetic-direct.mp4",mimeType:"video/mp4"}));
  await page.waitForFunction(()=>document.querySelector("#videoPlayer")?.currentTime>0.5);await page.keyboard.press("ArrowDown");await capture("player");
  const video=page.locator("#videoPlayer");await video.evaluate(v=>{v.pause();v.currentTime=5;});await page.waitForFunction(()=>Math.abs(document.querySelector("#videoPlayer").currentTime-5)<1);assert(await video.evaluate(v=>v.paused));await video.evaluate(v=>v.play());await page.waitForFunction(()=>document.querySelector("#videoPlayer").currentTime>5.4);
  await page.keyboard.press("Escape");await page.waitForTimeout(250);if(await page.evaluate(()=>window.__starlitSmoke.Router.getCurrent())==="player")await page.keyboard.press("Escape");await page.waitForTimeout(500);await capture("back-from-player");assert.notEqual(await page.evaluate(()=>window.__starlitSmoke.Router.getCurrent()),"player");
  assert.deepEqual(errors,[],"Browser runtime errors");await context.close();
 }
 await writeFile(path.join(root,"test-results/browser-evidence.json"),JSON.stringify({status:"PASS",scope:"Real Chromium UI and HTML5 playback; Samsung AVPlay/firmware NOT DEVICE-VERIFIED",evidence},null,2));
 console.log(`Browser UI/playback smoke PASS: ${evidence.length} captures`);
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
