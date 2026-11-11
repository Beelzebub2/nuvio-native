import {AuthManager} from "../auth/authManager.js";
import {SupabaseApi} from "../../data/remote/supabase/supabaseApi.js";
import {ProfileManager} from "./profileManager.js";
import {addonRepository} from "../../data/repository/addonRepository.js";
import {isSyncBackoffActive} from "../sync/syncBackoffPolicy.js";
import {addonPendingVersion,confirmAddonsPending} from "./starlitAddonPending.js";
let lastPullStatus={state:"idle",count:0,error:null,at:0};
function scope(){return {profile:String(ProfileManager.getActiveProfileId()||1),effective:String(addonRepository.getActiveStorageProfileId()),generation:AuthManager.sessionGeneration};}
function matches(value){const current=scope();return AuthManager.isAuthenticated&&current.profile===value.profile&&current.effective===value.effective&&current.generation===value.generation;}
function status(state,count=0){lastPullStatus={state,count,error:state==="error"?"Starlit add-on sync unavailable":null,at:Date.now()};}
export const LibrarySyncService={
 getLastPullStatus(){return lastPullStatus;},
 async pull(){
  if(!AuthManager.isAuthenticated || isSyncBackoffActive())return addonRepository.getInstalledAddonUrls();
  const current=scope();
  try{
   if(addonPendingVersion(current.effective) && !(await this.push())){status("local-pending");return addonRepository.getInstalledAddonUrls();}
   if(!matches(current))return addonRepository.getInstalledAddonUrls();
   const version=addonPendingVersion(current.effective);
   const rows=await SupabaseApi.select("addons",`profile_id=eq.${Number(current.effective)}&select=*&order=sort_order.asc`,true);
   if(!matches(current)||addonPendingVersion(current.effective)!==version)return addonRepository.getInstalledAddonUrls();
   if(!Array.isArray(rows)||rows.some(row=>typeof row.url!=="string"||!/^https?:\/\//i.test(row.url)))throw new Error("Invalid add-on snapshot");
   const urls=rows.map(row=>row.url);
   addonRepository.setAddonDisplayNameOverrides(rows.map(row=>({url:row.url,name:row.display_name||row.name||""})),{replace:true});
   addonRepository.setAddonEnabledStates(rows.map(row=>({url:row.url,enabled:row.enabled!==false})),{replace:true});
   await addonRepository.setAddonOrder(urls,{silent:true,allowReadOnly:true});
   status("ok",urls.length);return urls;
  }catch(_){status("error");return addonRepository.getInstalledAddonUrls();}
 },
 async push(){
  if(!AuthManager.isAuthenticated || isSyncBackoffActive())return false;
  const current=scope();if(current.profile!==current.effective || !addonRepository.canEdit())return true;
  const version=addonPendingVersion(current.effective),urls=addonRepository.getInstalledAddonUrls();
  try{
   await SupabaseApi.rpc("sync_push_addons",{p_profile_id:Number(current.effective),p_addons:urls.map((url,index)=>({url,sort_order:index,enabled:addonRepository.isAddonEnabled(url),name:addonRepository.getAddonDisplayNameOverride(url)||""}))},true);
   if(!matches(current))return false;confirmAddonsPending(current.effective,version);return true;
  }catch(_){return false;}
 }
};
