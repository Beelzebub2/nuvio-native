import test from "node:test";import assert from "node:assert/strict";
import {revokeStarlitDeviceSession} from "../js/core/auth/starlitDeviceRevocation.js";
test("expired access refreshes once and revokes the exact linked TV",async()=>{
 const calls=[];const statuses=[{status:401,ok:false},{status:200,ok:true,json:async()=>({access_token:"fresh"})},{status:200,ok:true}];
 const result=await revokeStarlitDeviceSession({backendUrl:"https://account.example",deviceId:"tv-123",accessToken:"expired",refreshToken:"refresh",fetchImpl:async(url,options)=>{calls.push({url,options});return statuses.shift();}});
 assert.equal(result.confirmed,true);assert.equal(calls.length,3);assert(calls[1].url.endsWith("/auth/v1/token?grant_type=refresh_token"));assert.equal(calls[2].options.headers.Authorization,"Bearer fresh");assert.equal(JSON.parse(calls[2].options.body).device_id,"tv-123");
});
test("offline or failed refresh leaves remote revocation unconfirmed without recursion",async()=>{
 const common={backendUrl:"https://account.example",deviceId:"tv-123",accessToken:"expired",refreshToken:"refresh"};
 assert.equal((await revokeStarlitDeviceSession({...common,fetchImpl:async()=>{throw new Error("offline");}})).confirmed,false);
 let calls=0;assert.equal((await revokeStarlitDeviceSession({...common,fetchImpl:async()=>{calls++;return{status:401,ok:false};}})).confirmed,false);assert.equal(calls,2);
});
