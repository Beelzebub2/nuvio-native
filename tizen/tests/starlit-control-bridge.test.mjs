import test from "node:test";import assert from "node:assert/strict";import vm from "node:vm";import {readFile} from "node:fs/promises";
import {connectLocalControl,getLocalControlToken,clearLocalControlTokens} from "../js/platform/tizen/starlitControlBridge.js";
const serviceId="StarlitTV1.EngineFsService",token="a".repeat(64);
test("UI accepts only a trusted exact-service response matching the random nonce",async()=>{
 clearLocalControlTokens();let callback,removed=0;
 const local={addMessagePortListener:cb=>{callback=cb;return 7;},removeMessagePortListener:id=>{assert.equal(id,7);removed++;}};
 const runtime={crypto:globalThis.crypto,tizen:{messageport:{requestTrustedLocalMessagePort:()=>local,requestTrustedRemoteMessagePort:(id,name)=>({isTrusted:true,sendMessage:data=>{const nonce=data[0].value;callback([{key:"nonce",value:nonce},{key:"token",value:token}],{isTrusted:false,appId:serviceId});callback([{key:"nonce",value:"wrong"},{key:"token",value:token}],{isTrusted:true,appId:serviceId});callback([{key:"nonce",value:nonce},{key:"token",value:token}],{isTrusted:true,appId:serviceId});}})}}};
 assert.equal(await connectLocalControl(serviceId,"2710",runtime),token);assert.equal(getLocalControlToken("2710"),token);assert.equal(removed,1);clearLocalControlTokens();
});
test("missing trusted APIs fails closed before HTTP control is enabled",async()=>{await assert.rejects(connectLocalControl(serviceId,"2710",{crypto:globalThis.crypto,tizen:{}}),/Trusted local streaming controls unavailable/);});
test("service bridges both documented binding shapes and rejects unrelated senders",async()=>{
 const source=await readFile(new URL("../services/tizen/runtime/trusted-control.cjs",import.meta.url),"utf8");
 for(const binding of ["global","module"]){let callback,sent=0;const port={addMessagePortListener:cb=>{callback=cb;return 1;},removeMessagePortListener:()=>{}};const api={messageport:{requestTrustedLocalMessagePort:()=>port}};const context={module:{exports:{}},require:name=>name==="crypto"?{randomBytes:()=>Buffer.from(token,"hex")}:api,Buffer};if(binding==="global")context.tizen=api;vm.runInNewContext(source,context);const bridge=context.module.exports({uiAppId:"StarlitTV1.StarlitTV"});
 const remote={isTrusted:true,appId:"StarlitTV1.StarlitTV",sendMessage:()=>{sent++;}};callback([{key:"nonce",value:"b".repeat(48)}],{...remote,appId:"other.App"});assert.equal(sent,0);callback([{key:"nonce",value:"b".repeat(48)}],remote);assert.equal(sent,1);assert.equal(bridge.token,token);bridge.close();
 }
});

test("concurrent controls share a trusted request and fixed local port",async()=>{
 let callback,requests=0,ports=0;const local={addMessagePortListener:cb=>{callback=cb;return 9;},removeMessagePortListener:()=>{}};let pending;
 const runtime={crypto:globalThis.crypto,tizen:{messageport:{requestTrustedLocalMessagePort:name=>{assert.equal(name,"STARLIT_REPLY_2710");ports++;return local;},requestTrustedRemoteMessagePort:()=>({isTrusted:true,sendMessage:data=>{requests++;pending=data[0].value;}})}}};
 const a=connectLocalControl(serviceId,"2710",runtime),b=connectLocalControl(serviceId,"2710",runtime);assert.equal(requests,1);callback([{key:"nonce",value:pending},{key:"token",value:token}],{isTrusted:true,appId:serviceId});await Promise.all([a,b]);
 const c=connectLocalControl(serviceId,"2710",runtime);assert.equal(ports,1);callback([{key:"nonce",value:pending},{key:"token",value:token}],{isTrusted:true,appId:serviceId});await c;
});
