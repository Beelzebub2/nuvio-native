import { randomBytes, timingSafeEqual } from "node:crypto";

const PENDING_MS = 5 * 60_000;
const ACTIVE_IDLE_MS = 20 * 60_000;
const ACTIVE_MAX_MS = 60 * 60_000;
const MAX_SESSIONS = 300;
const MAX_COMMANDS = 5;
const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const OPERATIONS = new Set([
  "previewAddon", "installAddon", "removeAddon", "setAddonEnabled",
  "previewPlugin", "installPlugin", "removePlugin", "setRepositoryEnabled",
  "setProviderEnabled", "refresh"
]);
const token = () => randomBytes(32).toString("hex");
const shortCode = (length = 8) => [...randomBytes(length)]
  .map((byte) => ALPHABET[byte % ALPHABET.length]).join("");
const equal = (one, other) => {
  if (typeof one !== "string" || typeof other !== "string" ||
      one.length !== other.length || one.length < 32) return false;
  return timingSafeEqual(Buffer.from(one), Buffer.from(other));
};
const safeText = (value, max = 90) => String(value ?? "")
  .replace(/[\u0000-\u001f\u007f]/g, "").slice(0, max);
const isSafeId = (id) => typeof id === "string" && /^[a-zA-Z0-9:_-]{1,100}$/.test(id);

export class RelayError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

function validateCommand(raw) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw) || !OPERATIONS.has(raw.op))
    throw new RelayError(400, "Unsupported action");
  const out = { op: raw.op };
  if (["previewAddon", "installAddon", "previewPlugin", "installPlugin"].includes(out.op)) {
    if (typeof raw.url !== "string" || raw.url.length > 4096)
      throw new RelayError(400, "Invalid repository URL");
    let u;
    try { u = new URL(raw.url); } catch { throw new RelayError(400, "Invalid repository URL"); }
    if (u.protocol !== "https:" || !u.hostname || u.username || u.password || u.hash ||
        u.port || /^(localhost|.*\.local|.*\.internal)$/i.test(u.hostname) ||
        /^\d+(?:\.\d+){3}$/.test(u.hostname) || u.hostname.includes(":"))
      throw new RelayError(400, "Only public HTTPS repositories are accepted");
    out.url = u.href;
  }
  if (["removeAddon", "setAddonEnabled", "removePlugin", "setRepositoryEnabled", "setProviderEnabled"].includes(out.op)) {
    if (!isSafeId(raw.target)) throw new RelayError(400, "Invalid item");
    out.target = raw.target;
  }
  if (["setAddonEnabled", "setRepositoryEnabled", "setProviderEnabled"].includes(out.op)) {
    if (typeof raw.enabled !== "boolean") throw new RelayError(400, "Invalid state");
    out.enabled = raw.enabled;
  }
  if (raw.revision !== undefined && (!Number.isSafeInteger(raw.revision) || raw.revision < 0))
    throw new RelayError(400, "Invalid revision");
  out.revision = raw.revision ?? 0;
  return out;
}

function sanitizedState(raw) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw))
    throw new RelayError(400, "Invalid TV state");
  const entries = (items, limit) => (Array.isArray(items) ? items.slice(0, limit) : [])
    .map(x => ({
      id: isSafeId(x?.id) ? x.id : "",
      name: safeText(x?.name),
      enabled: x?.enabled === true,
      type: safeText(x?.type, 24),
      repositoryId: isSafeId(x?.repositoryId) ? x.repositoryId : ""
    })).filter(x => x.id && x.name);
  return {
    profile: safeText(raw.profile, 40),
    canEditAddons: raw.canEditAddons === true,
    canEditPlugins: raw.canEditPlugins === true,
    pluginExecutable: raw.pluginExecutable === true,
    pluginReason: safeText(raw.pluginReason, 160),
    addons: entries(raw.addons, 150),
    repositories: entries(raw.repositories, 100),
    providers: entries(raw.providers, 250),
    revision: Number.isSafeInteger(raw.revision) ? raw.revision : 0
  };
}

export class TvSetupRelay {
  constructor(now = () => Date.now()) {
    this.now = now;
    this.sessions = new Map();
    this.codes = new Map();
  }

  gc() {
    const now = this.now();
    for (const [id, s] of this.sessions) {
      if (s.revoked || now > (s.approved ? Math.min(s.created + ACTIVE_MAX_MS, s.lastActive + ACTIVE_IDLE_MS) : s.created + PENDING_MS)) {
        this.sessions.delete(id);
        this.codes.delete(s.code);
        this.codes.delete(s.claimKey);
      }
    }
  }

  start({ name, profile } = {}) {
    this.gc();
    if (this.sessions.size >= MAX_SESSIONS) throw new RelayError(503, "Pairing temporarily unavailable");
    let code = shortCode();
    while (this.codes.has(code)) code = shortCode();
    const id = token();
    const claimKey = token();
    const tvToken = token();
    const s = {
      id, code, claimKey, tvToken, created: this.now(), lastActive: this.now(),
      name: safeText(name, 56) || "Starlit TV", profile: safeText(profile, 32) || "1",
      claimToken: "", verification: "", approved: false, revoked: false,
      pending: [], results: [], state: null, sequence: 0, completed: new Set()
    };
    this.sessions.set(id, s);
    this.codes.set(code, id);
    this.codes.set(claimKey, id);
    return { id, code, claimKey, tvToken, expiresIn: PENDING_MS / 1000 };
  }

  session(id) {
    this.gc();
    const s = this.sessions.get(id);
    if (!s) throw new RelayError(410, "Pairing expired");
    return s;
  }
  tv(id, tvToken) {
    const s = this.session(id);
    if (!equal(s.tvToken, tvToken)) throw new RelayError(403, "Unauthorized TV");
    s.lastActive = this.now();
    return s;
  }
  phone(id, claimToken) {
    const s = this.session(id);
    if (!equal(s.claimToken, claimToken)) throw new RelayError(403, "Unauthorized phone");
    return s;
  }
  claim(code) {
    const key = String(code ?? "").trim().toUpperCase();
    const id = this.codes.get(key) || this.codes.get(String(code ?? "").trim());
    if (!id) throw new RelayError(404, "Code not found");
    const s = this.session(id);
    if (s.claimToken) throw new RelayError(409, "A phone is already requesting access");
    s.claimToken = token();
    s.verification = shortCode(6);
    return { id, claimToken: s.claimToken, verification: s.verification, name: s.name, profile: s.profile };
  }
  inspectTV(id, key) {
    const s = this.tv(id, key);
    return {
      claimed: Boolean(s.claimToken), approved: s.approved,
      verification: s.verification, commands: s.approved ? s.pending.slice(0, 1) : []
    };
  }
  approve(id, key, accept) {
    const s = this.tv(id, key);
    if (!s.claimToken || s.approved) throw new RelayError(409, "No pending request");
    if (accept !== true) { this.revoke(id, key); return { approved: false }; }
    s.approved = true;
    s.lastActive = this.now();
    return { approved: true };
  }
  inspectPhone(id, key) {
    const s = this.phone(id, key);
    return {
      approved: s.approved, name: s.name, profile: s.profile,
      state: s.approved ? s.state : null,
      results: s.approved ? s.results.slice(-10) : [],
      expiresIn: Math.max(0, Math.ceil((Math.min(s.created + ACTIVE_MAX_MS, s.lastActive + ACTIVE_IDLE_MS) - this.now()) / 1000))
    };
  }
  updateState(id, key, state) {
    const s = this.tv(id, key);
    if (!s.approved) throw new RelayError(403, "TV has not approved the phone");
    s.state = sanitizedState(state);
    return { ok: true };
  }
  enqueue(id, key, raw) {
    const s = this.phone(id, key);
    if (!s.approved) throw new RelayError(403, "TV has not approved the phone");
    if (s.pending.length >= MAX_COMMANDS) throw new RelayError(429, "TV is handling other actions");
    const command = validateCommand(raw);
    const entry = { ...command, id: ++s.sequence };
    s.pending.push(entry);
    s.lastActive = this.now();
    return { id: entry.id, queued: true };
  }
  acknowledge(id, key, raw) {
    const s = this.tv(id, key);
    const idNumber = Number(raw?.id);
    const found = s.pending.find(x => x.id === idNumber);
    if (!found) {
      if (s.completed.has(idNumber)) return { ok: true };
      throw new RelayError(409, "Unknown action");
    }
    s.pending = s.pending.filter(x => x.id !== idNumber);
    s.completed.add(idNumber);
    if (s.completed.size > 100) s.completed.delete(s.completed.values().next().value);
    const detail = raw?.detail && typeof raw.detail === "object" ? {
      name: safeText(raw.detail.name, 100),
      description: safeText(raw.detail.description, 250),
      items: (Array.isArray(raw.detail.items) ? raw.detail.items : []).slice(0, 128).map(x => safeText(x, 80))
    } : null;
    s.results.push({ id: idNumber, ok: raw?.ok === true,
      message: safeText(raw?.message, 160), detail });
    if (s.results.length > 20) s.results.shift();
    return { ok: true };
  }
  revoke(id, key) {
    const s = this.session(id);
    if (!equal(s.tvToken, key) && !equal(s.claimToken, key))
      throw new RelayError(403, "Unauthorized");
    s.revoked = true;
    this.gc();
    return { revoked: true };
  }
}
