const tokens = new Map();
const localPorts = new Map();
const inFlight = new Map();
export function getLocalControlToken(port) { return tokens.get(String(port)) || ""; }
export function clearLocalControlTokens() { tokens.clear(); }
export async function connectLocalControl(serviceId, port, runtime = globalThis) {
  const key=`${serviceId}:${port}`;
  if(inFlight.has(key))return inFlight.get(key);
  const messageport = runtime.tizen?.messageport;
  if (!messageport?.requestTrustedLocalMessagePort || !messageport?.requestTrustedRemoteMessagePort || !runtime.crypto?.getRandomValues) throw new Error("Trusted local streaming controls unavailable on this TV; use a direct source");
  const bytes = new Uint8Array(24); runtime.crypto.getRandomValues(bytes);
  const nonce = Array.from(bytes).map(byte => byte.toString(16).padStart(2,"0")).join("");
  let entry=localPorts.get(String(port));
  if(!entry || entry.runtime!==runtime){entry={runtime,local:messageport.requestTrustedLocalMessagePort(`STARLIT_REPLY_${port}`)};localPorts.set(String(port),entry);}
  const local=entry.local;tokens.delete(String(port));
  const connecting = new Promise((resolve, reject) => {
    let timer, retry, listener, finished = false;
    const finish = (error, token) => { if(finished)return;finished=true;clearTimeout(timer);clearTimeout(retry);if(listener != null)local.removeMessagePortListener(listener);if(error){tokens.delete(String(port));reject(error);}else{tokens.set(String(port),token);resolve(token);} };
    listener = local.addMessagePortListener((data, remote) => {
      if (!remote?.isTrusted || remote.appId !== serviceId) return;
      const fields = new Map((Array.isArray(data) ? data : []).map(item => [item.key,item.value]));
      if (fields.get("nonce") !== nonce || !/^[a-f0-9]{64}$/.test(fields.get("token") || "")) return;
      finish(null, fields.get("token"));
    });
    const send = () => { if(finished)return;try { const remote = messageport.requestTrustedRemoteMessagePort(serviceId,"STARLIT_ENGINE_CONTROL");if(!remote.isTrusted)throw new Error("Untrusted service");remote.sendMessage([{key:"nonce",value:nonce}],local); } catch (_) { retry=setTimeout(send,250); } };
    timer=setTimeout(()=>finish(new Error("Trusted streaming service did not respond; use a direct source")),10000);send();
  });
  inFlight.set(key,connecting);
  try{return await connecting;}finally{inFlight.delete(key);}
}
