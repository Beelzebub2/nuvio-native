import { LocalStore } from "../storage/localStore.js";
const KEY="starlitLibraryMutations";
const identity=item=>`${item.content_id || item.contentId}::${item.content_type || item.contentType || "movie"}`;
export function pendingLibraryMutations(profileId) { return LocalStore.get(KEY,{})[String(profileId)] || {upserts:[],deletes:[]}; }
function write(profileId,state){const all=LocalStore.get(KEY,{});all[String(profileId)]=state;LocalStore.set(KEY,all);}
export function markLibraryMutation(profileId,item,remove=false){const state=pendingLibraryMutations(profileId),key=identity(item);state.upserts=state.upserts.filter(entry=>identity(entry)!==key);state.deletes=state.deletes.filter(entry=>identity(entry)!==key);(remove?state.deletes:state.upserts).push(remove?{content_id:item.contentId,content_type:item.contentType||"movie"}:item);write(profileId,state);}
export function confirmLibraryMutations(profileId,sent){const state=pendingLibraryMutations(profileId);for(const kind of ["upserts","deletes"])state[kind]=state[kind].filter(entry=>!sent[kind].some(previous=>JSON.stringify(previous)===JSON.stringify(entry)));write(profileId,state);}
export function mergePendingLibrary(profileId,remote){const pending=pendingLibraryMutations(profileId),removed=new Set(pending.deletes.map(identity)),local=new Map(pending.upserts.map(item=>[identity(item),item]));return [...remote.filter(item=>!removed.has(identity(item))&&!local.has(identity(item))),...local.values()];}
