import test from "node:test";import assert from "node:assert/strict";
const values=new Map();globalThis.localStorage={getItem:k=>values.get(k)||null,setItem:(k,v)=>values.set(k,v),removeItem:k=>values.delete(k)};
const {AuthManager}=await import("../js/core/auth/authManager.js");const {SessionStore}=await import("../js/core/storage/sessionStore.js");const {httpRequest}=await import("../js/core/network/httpClient.js");
test("account switch while initial refresh is suspended cannot attach the new owner's bearer",async()=>{
 SessionStore.accessToken="owner-a";SessionStore.refreshToken="refresh-a";AuthManager.sessionGeneration=1;AuthManager.isAccessTokenExpired=()=>true;let finish;AuthManager.refreshSessionIfNeeded=()=>new Promise(resolve=>{finish=resolve;});let sent=0;globalThis.fetch=()=>{sent++;throw new Error("must not send");};
 const request=httpRequest("https://account.example/rest/v1/rpc/sync_push_provider_credentials",{method:"POST",body:"private-payload"});AuthManager.sessionGeneration=2;SessionStore.accessToken="owner-b";finish(true);await assert.rejects(request,{name:"AbortError"});assert.equal(sent,0);
});
test("account switch during401 refresh cannot retry an old payload under the new account",async()=>{
 SessionStore.accessToken="owner-a";SessionStore.refreshToken="refresh-a";AuthManager.sessionGeneration=3;AuthManager.isAccessTokenExpired=()=>false;let finish,sent=0;AuthManager.refreshSessionIfNeeded=()=>new Promise(resolve=>{finish=resolve;});globalThis.fetch=async(_url,options)=>{sent++;assert.equal(options.headers.Authorization,"Bearer owner-a");return{status:401,ok:false,headers:{get:()=>null}};};
 const request=httpRequest("https://account.example/rest/v1/rpc/sync_push_provider_credentials",{method:"POST",body:"private-payload"});while(!finish)await new Promise(resolve=>setTimeout(resolve,1));AuthManager.sessionGeneration=4;SessionStore.accessToken="owner-b";finish(true);await assert.rejects(request,{name:"AbortError"});assert.equal(sent,1);
});
