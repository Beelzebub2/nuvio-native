import {LocalStore} from "../storage/localStore.js";
const KEY="starlitAddonPending";
export function addonPendingVersion(profileId){return Number(LocalStore.get(KEY,{})[String(profileId)]||0);}
export function markAddonsPending(profileId){const values=LocalStore.get(KEY,{});values[String(profileId)]=Math.max(Date.now(),Number(values[String(profileId)]||0)+1);LocalStore.set(KEY,values);}
export function confirmAddonsPending(profileId,version){if(addonPendingVersion(profileId)!==version)return;const values=LocalStore.get(KEY,{});delete values[String(profileId)];LocalStore.set(KEY,values);}
