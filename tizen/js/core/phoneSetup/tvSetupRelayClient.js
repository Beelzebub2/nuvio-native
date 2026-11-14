const ROOT = "https://starlit.rrmtools.uk/tv/api/";
export const TV_SETUP_URL = "https://starlit.rrmtools.uk/tv/connect";

export function createTvSetupRelayClient(fetchImpl = fetch, root = ROOT) {
  let pairing = null;
  async function request(action, method = "GET", payload) {
    const controller = typeof AbortController === "function" ? new AbortController() : null;
    const timer = controller ? setTimeout(() => controller.abort(), 10000) : 0;
    try {
      const suffix = pairing && action !== "start" ? "?id=" + encodeURIComponent(pairing.id) : "";
      const result = await fetchImpl(root + action + suffix, {
        method, credentials: "omit", cache: "no-store", redirect: "error",
        ...(controller ? { signal: controller.signal } : {}),
        headers: {
          ...(method === "POST" ? { "Content-Type": "application/json" } : {}),
          ...(pairing && action !== "start" ? { "X-TV-Token": pairing.tvToken } : {})
        },
        ...(payload === undefined ? {} : { body: JSON.stringify(payload) })
      });
      const data = await result.json();
      if (!result.ok) throw new Error(data.error || "TV setup unavailable");
      return data;
    } finally { if (timer) clearTimeout(timer); }
  }
  return {
    async start(device, profile) {
      pairing = await request("start", "POST", { name: device, profile });
      return {
        code: pairing.code,
        qrUrl: TV_SETUP_URL + "#code=" + encodeURIComponent(pairing.claimKey),
        expiresIn: pairing.expiresIn
      };
    },
    get active() { return Boolean(pairing); },
    inspect: () => request("tv"),
    approve: (accept) => request("approve", "POST", { accept }),
    state: (state) => request("state", "POST", state),
    ack: (id, result) => request("ack", "POST", { id, ...result }),
    async stop() {
      if (!pairing) return;
      try { await request("stop", "POST", {}); } finally { pairing = null; }
    },
    forget() { pairing = null; }
  };
}
