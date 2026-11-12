import { getLocalControlToken } from "./starlitControlBridge.js";
// A custom request header blocks browser form/image access to control endpoints.
export function installLocalServiceFetch(runtime = globalThis) {
  if (!runtime.fetch || runtime.__starlitLocalFetch) return;
  const original = runtime.fetch.bind(runtime);
  runtime.fetch = (input, options = {}) => {
    try {
      const url = new URL(typeof input === "string" ? input : input.url);
      if (url.protocol === "http:" && ["127.0.0.1", "localhost"].includes(url.hostname) && ["2710", "2711"].includes(url.port)) {
        const headers = new Headers(options.headers || input?.headers || {});
        headers.set("X-Starlit-Local", "1");
        const token = getLocalControlToken(url.port);
        if(token)headers.set("X-Starlit-Token",token);
        return original(input, { ...options, headers });
      }
    } catch (_) { /* Keep ordinary fetch error behavior. */ }
    return original(input, options);
  };
  runtime.__starlitLocalFetch = true;
}
