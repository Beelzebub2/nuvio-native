import { Platform } from "./index.js";
import { TizenCapabilities, isTizen5PluginPreviewEnabled } from "./tizen/tizenCapabilities.js";
import { TizenPluginService } from "./tizen/tizenPluginService.js";
import { WebOsPluginService } from "./webos/webosPluginService.js";
import {
  normalizePluginHeaders,
  validatePluginFetchRequest,
  validateLegacyCorsPluginUrl
} from "../core/player/pluginSecurity.js";
import {
  diagnosticError,
  emitPluginDiagnosticEvent,
  emitPluginServiceDiagnostics
} from "../core/diagnostics/pluginDiagnostics.js";
let cachedHealth = null;
let cachedHealthAt = 0;
const HEALTH_TTL_MS = 30000;
const SERVICE_WATCHDOG_INTERVAL_MS = 10000;
const WATCHDOG_FAILURE_THRESHOLD = 2;
const WATCHDOG_RECOVERY_BACKOFF_INITIAL_MS = 30000;
const WATCHDOG_RECOVERY_BACKOFF_MAX_MS = 120000;
let lifecycleMonitorTimer = null;
let lifecycleMonitorStartPromise = null;
let lifecycleMonitorProbePromise = null;
let lifecycleMonitorGeneration = 0;
let lifecycleLastFailure = "";
let lifecycleConsecutiveFailures = 0;
let lifecycleRecoveryNotBefore = 0;
let lifecycleRecoveryBackoffMs = WATCHDOG_RECOVERY_BACKOFF_INITIAL_MS;
let lastHealthDiagnostic = "";
// Set by the app shell: while it returns true (for example during playback)
// the watchdog skips its periodic probe so the player keeps the main thread.
let lifecycleMonitorPauseWhen = null;
// On Chrome 63 there is no native AbortController. Keep timed-out requests
// counted until the underlying fetch settles, so a provider cannot start
// unbounded network work while the TV retains those connections.
const LEGACY_MAX_ACTIVE_FETCHES = 3;
const legacyActiveFetches = new Map();
export const PLUGIN_PROTOCOL_VERSION = 1;
function serviceForPlatform() {
  if (Platform.isTizen()) return TizenPluginService;
  if (Platform.isWebOS()) return WebOsPluginService;
  return null;
}
const TIZEN_PLUGIN_UNSUPPORTED_DETAIL = "Tizen plugin support starts at Tizen 6.0";
function areTizenPluginsSupported() {
  return !Platform.isTizen() || TizenCapabilities.canUsePlugins();
}
function unsupportedTizenPluginResult() {
  return {
    returnValue: false,
    status: "unsupported",
    pluginUnsupported: true,
    detail: TIZEN_PLUGIN_UNSUPPORTED_DETAIL
  };
}
function reportLifecycleFailure(error) {
  const detail = String(error?.message || error || "PluginService is unavailable");
  if (detail === lifecycleLastFailure) {
    return;
  }
  lifecycleLastFailure = detail;
  console.warn("[Nuvio PluginService] lifecycle watchdog recovery failed", detail);
}
function reportHealthFailure(error) {
  const detail = String(error?.message || error || "PluginService health check failed");
  if (detail === lastHealthDiagnostic) return;
  lastHealthDiagnostic = detail;
  emitPluginDiagnosticEvent(
    "plugin service health check failed",
    { error: diagnosticError(error) },
    { prefix: "[Nuvio PluginService]", level: "error" }
  );
}
function assertCompatibleHealth(health) {
  if (health.returnValue !== true)
    throw new Error(health.detail || "Plugin network service is not ready");
  if (
    Number(health.protocolVersion || 0) !== PLUGIN_PROTOCOL_VERSION ||
    Number(health.serviceVersion || 0) < 1 ||
    typeof health.runtimeVersion !== "string" ||
    typeof health.quickjsVersion !== "string" ||
    health.workerSupport !== true ||
    Number(health.maxConcurrency || 0) < 1 ||
    typeof health.memoryTier !== "string" ||
    health.jsPluginCapability !== true ||
    health.networkBoundary !== true
  ) {
    throw new Error("Plugin service protocol or JavaScript capability is incompatible");
  }
  return health;
}
function resetLifecycleRecoveryState() {
  lifecycleConsecutiveFailures = 0;
  lifecycleRecoveryNotBefore = 0;
  lifecycleRecoveryBackoffMs = WATCHDOG_RECOVERY_BACKOFF_INITIAL_MS;
  lifecycleLastFailure = "";
}
function healthFailureDetail(error) {
  return String(error?.message || error || "PluginService health check failed");
}
function pluginRequestDetails(request = {}) {
  return {
    requestId: String(request.requestId || "").slice(0, 128),
    executionId: String(request.executionId || "").slice(0, 128),
    profileId: String(request.profileId || "").slice(0, 64),
    repositoryId: String(request.repositoryId || "").slice(0, 128),
    scraperId: String(request.scraperId || "").slice(0, 128),
    method: String(request.method || "GET").toUpperCase(),
    url: String(request.url || "")
  };
}
async function probeReadyWithoutStarting() {
  const service = serviceForPlatform();
  if (Platform.isTizen() && typeof service?.probe === "function") {
    try {
      const payload = await service.probe();
      const health = { returnValue: payload?.returnValue !== false, status: "success", ...payload };
      const ready = assertCompatibleHealth(health);
      cachedHealth = ready;
      cachedHealthAt = Date.now();
      return ready;
    } catch (error) {
      cachedHealth = {
        returnValue: false,
        status: "error",
        detail: healthFailureDetail(error)
      };
      cachedHealthAt = Date.now();
      throw error;
    }
  }
  // Keep this helper safe if it is ever used by another TV adapter without a
  // probe-only API. The Tizen implementation above is the important path:
  // its watchdog check never starts the service on the first transient miss.
  return PluginServiceClient.ensureReady({ force: true });
}
async function runLifecycleCheck(generation) {
  try {
    const health = await probeReadyWithoutStarting();
    if (generation !== lifecycleMonitorGeneration) {
      return { status: "stopped", health };
    }
    resetLifecycleRecoveryState();
    return { status: "running", health, recovered: false };
  } catch (probeError) {
    if (generation !== lifecycleMonitorGeneration) {
      return { status: "stopped", detail: healthFailureDetail(probeError) };
    }
    lifecycleConsecutiveFailures += 1;
    if (lifecycleConsecutiveFailures < WATCHDOG_FAILURE_THRESHOLD) {
      return {
        status: "degraded",
        failures: lifecycleConsecutiveFailures,
        detail: healthFailureDetail(probeError)
      };
    }
    const now = Date.now();
    if (now < lifecycleRecoveryNotBefore) {
      return {
        status: "backoff",
        failures: lifecycleConsecutiveFailures,
        retryInMs: lifecycleRecoveryNotBefore - now,
        detail: healthFailureDetail(probeError)
      };
    }
    if (generation !== lifecycleMonitorGeneration) {
      return { status: "stopped", detail: healthFailureDetail(probeError) };
    }
    const backoff = lifecycleRecoveryBackoffMs;
    lifecycleRecoveryNotBefore = now + backoff;
    lifecycleRecoveryBackoffMs = Math.min(
      WATCHDOG_RECOVERY_BACKOFF_MAX_MS,
      Math.max(WATCHDOG_RECOVERY_BACKOFF_INITIAL_MS, backoff * 2)
    );
    try {
      // Only the confirmed consecutive-failure path is allowed to ask Tizen
      // to launch the service after the initial optional probe has failed.
      const health = await PluginServiceClient.ensureReady({ force: true });
      if (generation !== lifecycleMonitorGeneration) {
        return { status: "stopped", health };
      }
      resetLifecycleRecoveryState();
      return { status: "running", health, recovered: true };
    } catch (recoveryError) {
      reportLifecycleFailure(recoveryError);
      return {
        status: "degraded",
        failures: lifecycleConsecutiveFailures,
        recoveryAttempted: true,
        detail: healthFailureDetail(recoveryError)
      };
    }
  }
}
function normalizeResponse(payload, requestedUrl = "") {
  const status = Number(payload?.status || payload?.statusCode || 0);
  return {
    returnValue: payload?.returnValue !== false,
    // Android's OkHttp contract is successful only for 2xx responses. Do not
    // turn redirects or service errors into a successful plugin response.
    ok: status >= 200 && status < 300,
    status,
    statusText: String(payload?.statusText || ""),
    url: String(payload?.url || requestedUrl),
    body: typeof payload?.body === "string" ? payload.body : "",
    ...(typeof payload?.bodyBase64 === "string" ? { bodyBase64: payload.bodyBase64 } : {}),
    headers: payload?.headers && typeof payload.headers === "object" ? payload.headers : {},
    truncated: payload?.truncated === true
  };
}
function androidFetchFailure(request = {}, error) {
  return {
    returnValue: true,
    ok: false,
    status: 0,
    statusText: String(error?.message || error || "Fetch failed"),
    url: String(request.url || ""),
    body: "",
    headers: {},
    truncated: false
  };
}
async function directBrowserFetch(request) {
  const validation = validatePluginFetchRequest(request, {
    maxBodyBytes: Number(request.maxBodyBytes || 1024 * 1024)
  });
  if (!validation.ok) throw new Error(validation.reason);
  const controller = typeof AbortController === "function" ? new AbortController() : null;
  const abort = () => controller?.abort();
  request.signal?.addEventListener?.("abort", abort, { once: true });
  const timer = setTimeout(abort, Number(request.timeoutMs || 30000));
  try {
    const requestBody =
      validation.bodyKind === "base64"
        ? Uint8Array.from(atob(validation.bodyBase64), (char) => char.charCodeAt(0))
        : validation.bodyKind === "text"
          ? validation.body
          : new Uint8Array(0);
    const methodHasBody =
      ["POST", "PUT", "PATCH"].includes(validation.method) ||
      (validation.method === "DELETE" && validation.bodyKind !== "none");
    const response = await fetch(validation.url, {
      method: validation.method,
      headers: normalizePluginHeaders(validation.headers),
      body: methodHasBody ? requestBody : undefined,
      signal: controller?.signal || request.signal
    });
    const binaryResponse =
      request.responseEncoding === "base64" ? await response.clone().arrayBuffer() : null;
    const body = await response.text();
    let bodyBase64;
    if (binaryResponse) {
      const bytes = new Uint8Array(binaryResponse);
      const chunks = [];
      for (let index = 0; index < bytes.length; index += 8192) {
        chunks.push(String.fromCharCode.apply(null, bytes.subarray(index, index + 8192)));
      }
      bodyBase64 = btoa(chunks.join(""));
    }
    return normalizeResponse(
      {
        returnValue: true,
        ok: response.ok,
        status: response.status,
        statusText: response.statusText,
        url: response.url,
        body,
        ...(bodyBase64 !== undefined ? { bodyBase64 } : {}),
        headers: {}
      },
      validation.url
    );
  } finally {
    clearTimeout(timer);
    request.signal?.removeEventListener?.("abort", abort);
  }
}
// No Node service on legacy Tizen. CORS-compatible providers may run in
// QuickJS; unsupported origins fail closed without a permissive LAN proxy.
export async function legacyCorsFetch(request) {
  const limit = Math.min(512 * 1024, Math.max(1, Number(request.maxBodyBytes || 512 * 1024)));
  const validation = validatePluginFetchRequest(request, { maxBodyBytes: limit });
  if (!validation.ok) throw new Error(validation.reason);
  const safeUrl = validateLegacyCorsPluginUrl(validation.url);
  if (!safeUrl.ok) throw new Error(safeUrl.reason);
  if (request.signal?.aborted) throw new Error("Plugin execution cancelled");
  const requestId = String(request.requestId || `${Date.now()}-${Math.random()}`);
  if (legacyActiveFetches.has(requestId) || legacyActiveFetches.size >= LEGACY_MAX_ACTIVE_FETCHES) {
    throw new Error("Plugin network service unavailable");
  }
  if (typeof fetch !== "function" || typeof TextDecoder !== "function") {
    throw new Error("Safe legacy browser fetch APIs are unavailable");
  }
  const headers = normalizePluginHeaders(validation.headers, { addDefaultUserAgent: false });
  Object.keys(headers).forEach((name) => {
    if (/^(?:host|origin|referer|user-agent|cookie|connection|accept-encoding|content-length|proxy-.*|sec-.*)$/i.test(name)) {
      delete headers[name];
    }
  });
  const requestBody = validation.bodyKind === "base64"
    ? Uint8Array.from(atob(validation.bodyBase64), (char) => char.charCodeAt(0))
    : validation.bodyKind === "text" ? validation.body : undefined;
  const controller = typeof AbortController === "function" ? new AbortController() : null;
  let timer = 0;
  let activeReader = null;
  const stopNetwork = () => {
    controller?.abort();
    if (activeReader) Promise.resolve(activeReader.cancel()).catch(() => {});
  };
  let cancelPending;
  const cancellation = new Promise((_, reject) => {
    cancelPending = () => {
      stopNetwork();
      reject(new Error("Plugin execution cancelled"));
    };
  });
  const onAbort = () => cancelPending();
  request.signal?.addEventListener?.("abort", onAbort, { once: true });
  legacyActiveFetches.set(requestId, cancelPending);
  const maxBytes = Math.min(limit, Math.max(1, Number(request.maxResponseBytes || limit)));
  const execute = async () => {
    const response = await fetch(validation.url, {
      method: validation.method,
      headers,
      body: requestBody,
      redirect: "error",
      credentials: "omit",
      mode: "cors",
      referrerPolicy: "no-referrer",
      ...(controller ? { signal: controller.signal } : {})
    });
    const responseUrl = validateLegacyCorsPluginUrl(response.url || validation.url);
    if (!responseUrl.ok || responseUrl.url !== validation.url)
      throw new Error("Redirected or unsafe legacy plugin response");
    const length = Number(response.headers?.get?.("content-length") || 0);
    if (length > maxBytes) throw new Error("Plugin response exceeds legacy limit");
    if (response.body && typeof response.body.getReader !== "function")
      throw new Error("Bounded HTTP streaming unavailable on this TV");
    if (!response.body && response.status !== 204 && response.status !== 205)
      throw new Error("Bounded HTTP streaming unavailable on this TV");
    const chunks = [];
    let total = 0;
    if (response.body) {
      activeReader = response.body.getReader();
      for (;;) {
        const part = await activeReader.read();
        if (part.done) break;
        total += part.value.byteLength;
        if (total > maxBytes) throw new Error("Plugin response exceeds legacy limit");
        chunks.push(part.value);
      }
    }
    const bytes = new Uint8Array(total);
    let offset = 0;
    chunks.forEach((chunk) => { bytes.set(chunk, offset); offset += chunk.byteLength; });
    let bodyBase64;
    if (request.responseEncoding === "base64") {
      const segments = [];
      for (let index = 0; index < bytes.length; index += 8192) {
        segments.push(String.fromCharCode.apply(null, bytes.subarray(index, index + 8192)));
      }
      bodyBase64 = btoa(segments.join(""));
    }
    return normalizeResponse({
      returnValue: true,
      status: response.status,
      statusText: response.statusText,
      url: response.url,
      body: new TextDecoder("utf-8").decode(bytes),
      ...(bodyBase64 === undefined ? {} : { bodyBase64 }),
      headers: {},
      truncated: false
    }, validation.url);
  };
  const deadlineMs = Math.min(15000, Math.max(1, Number(request.timeoutMs || 15000)));
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => {
      stopNetwork();
      reject(new Error("Legacy plugin HTTPS request timed out"));
    }, deadlineMs);
  });
  // Unlike the user-facing timeout/cancel race, keep the connection counted
  // until fetch itself settles. Old browsers cannot cancel a pending fetch;
  // this keeps memory and outstanding sockets bounded by the preview quota.
  const ongoing = execute();
  ongoing.then(
    () => legacyActiveFetches.delete(requestId),
    () => legacyActiveFetches.delete(requestId)
  );
  try {
    return await Promise.race([ongoing, timeout, cancellation]);
  } finally {
    clearTimeout(timer);
    request.signal?.removeEventListener?.("abort", onAbort);
  }
}
export function cancelLegacyPluginRequest(requestId) {
  const cancel = legacyActiveFetches.get(String(requestId || ""));
  if (!cancel) return false;
  cancel();
  return true;
}
function legacyCorsHealth() {
  const ready = typeof fetch === "function" &&
    typeof TextDecoder === "function" &&
    typeof ReadableStream === "function";
  return {
    returnValue: ready,
    status: ready ? "success" : "unsupported",
    detail: ready ? "Legacy browser HTTPS/CORS transport" : "Bounded CORS APIs unavailable",
    protocolVersion: PLUGIN_PROTOCOL_VERSION,
    serviceVersion: 1,
    runtimeVersion: "browser-cors",
    quickjsVersion: "asmjs-0.32.0",
    workerSupport: typeof Worker === "function",
    maxConcurrency: 1,
    memoryTier: "legacy",
    jsPluginCapability: ready,
    networkBoundary: ready
  };
}
function runLifecycleWatchdogTick(generation) {
  if (generation !== lifecycleMonitorGeneration || lifecycleMonitorProbePromise) {
    return;
  }
  try {
    if (typeof lifecycleMonitorPauseWhen === "function" && lifecycleMonitorPauseWhen()) {
      return;
    }
  } catch (_) {
    // A failing predicate must not stop supervision.
  }
  lifecycleMonitorProbePromise = runLifecycleCheck(generation).finally(() => {
    lifecycleMonitorProbePromise = null;
  });
}
// The watchdog used to start at application boot, which launched the Node
// service during Home render on every Tizen 6+ TV and then probed it every
// 10 s for the whole session even when no plugin was installed. It now starts
// the first time plugin code needs the service: the readiness barrier or a
// plugin fetch.
function ensureLifecycleMonitorRunning() {
  if (!Platform.isTizen() || !areTizenPluginsSupported() || lifecycleMonitorTimer) {
    return false;
  }
  resetLifecycleRecoveryState();
  const generation = lifecycleMonitorGeneration;
  lifecycleMonitorTimer = setInterval(
    () => runLifecycleWatchdogTick(generation),
    SERVICE_WATCHDOG_INTERVAL_MS
  );
  return true;
}
export const PluginServiceClient = {
  getService() {
    return serviceForPlatform();
  },
  configureLifecycleMonitor({ pauseProbeWhen = null } = {}) {
    lifecycleMonitorPauseWhen = typeof pauseProbeWhen === "function" ? pauseProbeWhen : null;
  },
  isLifecycleMonitorRunning() {
    return Boolean(lifecycleMonitorTimer);
  },
  async health({ force = false } = {}) {
    if (isTizen5PluginPreviewEnabled()) return legacyCorsHealth();
    const now = Date.now();
    if (!areTizenPluginsSupported()) {
      cachedHealth = unsupportedTizenPluginResult();
      cachedHealthAt = now;
      return cachedHealth;
    }
    if (!force && cachedHealth && now - cachedHealthAt < HEALTH_TTL_MS) return cachedHealth;
    const service = serviceForPlatform();
    if (!service) {
      cachedHealth = {
        returnValue: globalThis.__NUVIO_ALLOW_BROWSER_PLUGIN_RUNTIME__ === true,
        status:
          globalThis.__NUVIO_ALLOW_BROWSER_PLUGIN_RUNTIME__ === true ? "browser" : "unsupported",
        detail: "No packaged TV plugin service"
      };
      cachedHealthAt = now;
      return cachedHealth;
    }
    try {
      const payload = await service.health();
      cachedHealth = { returnValue: payload?.returnValue !== false, status: "success", ...payload };
      if (cachedHealth.returnValue !== true) {
        reportHealthFailure(new Error(cachedHealth.detail || "PluginService health check failed"));
      } else {
        lastHealthDiagnostic = "";
      }
    } catch (error) {
      reportHealthFailure(error);
      cachedHealth = {
        returnValue: false,
        status: "error",
        detail: String(error?.message || error)
      };
    }
    cachedHealthAt = Date.now();
    return cachedHealth;
  },
  async ensureReady({ force = false } = {}) {
    if (!areTizenPluginsSupported()) {
      return unsupportedTizenPluginResult();
    }
    if (!isTizen5PluginPreviewEnabled()) ensureLifecycleMonitorRunning();
    const health = await this.health({ force });
    return assertCompatibleHealth(health);
  },
  startLifecycleMonitor() {
    if (!Platform.isTizen() || !areTizenPluginsSupported() || isTizen5PluginPreviewEnabled()) {
      return Promise.resolve({ status: "skipped" });
    }
    if (lifecycleMonitorStartPromise) {
      return lifecycleMonitorStartPromise;
    }
    if (lifecycleMonitorTimer) {
      return Promise.resolve({ status: "running" });
    }
    if (!lifecycleMonitorStartPromise) {
      // Start supervising before the first health check. If a cold TV boot
      // misses the initial startup window, later ticks must still retry while
      // the UI continues booting independently.
      ensureLifecycleMonitorRunning();
      const generation = lifecycleMonitorGeneration;
      lifecycleMonitorStartPromise = (async () => {
        // The platform start call is only an acknowledgement. The initial
        // health check is intentionally asynchronous for the optional service;
        // callers may await this promise when they explicitly need readiness.
        const health = await this.ensureReady({ force: true });
        if (generation !== lifecycleMonitorGeneration) {
          return { status: "stopped", health };
        }
        resetLifecycleRecoveryState();
        return { status: "running", health };
      })().finally(() => {
        lifecycleMonitorStartPromise = null;
      });
    }
    return lifecycleMonitorStartPromise;
  },
  checkLifecycleNow() {
    if (!Platform.isTizen() || !areTizenPluginsSupported() || isTizen5PluginPreviewEnabled()) {
      return Promise.resolve({ status: "skipped" });
    }
    if (!lifecycleMonitorTimer) {
      // Nothing has used the service in this session. A foreground check would
      // otherwise count probe misses and launch it after two of them.
      return Promise.resolve({ status: "idle" });
    }
    if (!lifecycleMonitorProbePromise) {
      const generation = lifecycleMonitorGeneration;
      lifecycleMonitorProbePromise = runLifecycleCheck(generation).finally(() => {
        lifecycleMonitorProbePromise = null;
      });
    }
    return lifecycleMonitorProbePromise;
  },
  stopLifecycleMonitor() {
    lifecycleMonitorGeneration += 1;
    resetLifecycleRecoveryState();
    if (lifecycleMonitorTimer) {
      clearInterval(lifecycleMonitorTimer);
      lifecycleMonitorTimer = null;
    }
  },
  async fetch(request = {}) {
    const androidContract = request.androidResponseContract === true;
    const requestDetails = pluginRequestDetails(request);
    try {
      if (!areTizenPluginsSupported()) {
        throw new Error(TIZEN_PLUGIN_UNSUPPORTED_DETAIL);
      }
      if (isTizen5PluginPreviewEnabled()) {
        return await legacyCorsFetch(request);
      }
      ensureLifecycleMonitorRunning();
      if (Platform.isBrowser()) {
        if (globalThis.__NUVIO_ALLOW_BROWSER_PLUGIN_RUNTIME__ !== true)
          throw new Error("Plugin execution is TV-only");
        const result = await directBrowserFetch(request);
        if (!result.ok || result.truncated || result.returnValue === false) {
          emitPluginDiagnosticEvent(
            result.truncated ? "provider response truncated" : "provider response failed",
            {
              ...requestDetails,
              status: result.status,
              statusText: result.statusText,
              responseUrl: result.url,
              truncated: result.truncated
            },
            {
              prefix: "[Nuvio PluginService]",
              level: result.status >= 500 || result.status === 0 ? "error" : "warn"
            }
          );
        }
        return result;
      }
      const validation = validatePluginFetchRequest(request, {
        maxBodyBytes: Number(request.maxBodyBytes || 1024 * 1024)
      });
      if (!validation.ok) throw new Error(validation.reason);
      const service = serviceForPlatform();
      if (!service) throw new Error("Plugin network service unavailable");
      const result = await service.fetch(
        {
          requestId: String(request.requestId || `${Date.now()}-${Math.random()}`),
          url: validation.url,
          method: validation.method,
          headers: validation.headers,
          bodyKind: validation.bodyKind,
          body: validation.body,
          ...(validation.bodyBase64 !== undefined ? { bodyBase64: validation.bodyBase64 } : {}),
          ...(request.responseEncoding === "base64" ? { responseEncoding: "base64" } : {}),
          maxBodyBytes: Number(request.maxBodyBytes || 1024 * 1024),
          maxResponseBytes: Number(request.maxResponseBytes || request.maxBodyBytes || 1024 * 1024),
          executionId: String(request.executionId || ""),
          profileId: String(request.profileId || ""),
          repositoryId: String(request.repositoryId || ""),
          scraperId: String(request.scraperId || ""),
          deadline: Number(request.deadline || 0) || undefined
        },
        {
          timeoutMs: Number(request.timeoutMs || 30000),
          signal: request.signal
        }
      );
      const normalized = normalizeResponse(result, validation.url);
      if (!normalized.ok || normalized.truncated || normalized.returnValue === false) {
        emitPluginDiagnosticEvent(
          normalized.truncated ? "provider response truncated" : "provider response failed",
          {
            ...requestDetails,
            url: validation.url,
            status: normalized.status,
            statusText: normalized.statusText,
            responseUrl: normalized.url,
            truncated: normalized.truncated
          },
          {
            prefix: "[Nuvio PluginService]",
            level: normalized.status >= 500 || normalized.status === 0 ? "error" : "warn"
          }
        );
      }
      return normalized;
    } catch (error) {
      emitPluginDiagnosticEvent(
        "plugin service fetch failed",
        { ...requestDetails, error: diagnosticError(error) },
        { prefix: "[Nuvio PluginService]", level: "error" }
      );
      // Android's native fetch resolves transport failures as a normal
      // response with status 0. Keep management/API calls throwing, and apply
      // that contract only to the explicit request emitted by PluginRuntime.
      if (androidContract) return androidFetchFailure(request, error);
      throw error;
    }
  },
  capabilities() {
    if (isTizen5PluginPreviewEnabled()) return Promise.resolve(legacyCorsHealth());
    if (!areTizenPluginsSupported()) return unsupportedTizenPluginResult();
    const service = serviceForPlatform();
    if (!service?.capabilities) return this.health({ force: true });
    return service.capabilities();
  },
  async diagnostics() {
    if (isTizen5PluginPreviewEnabled()) return legacyCorsHealth();
    if (!areTizenPluginsSupported()) return unsupportedTizenPluginResult();
    const service = serviceForPlatform();
    if (!service?.diagnostics)
      return Promise.resolve({ returnValue: false, detail: "Diagnostics unavailable" });
    try {
      const result = await service.diagnostics();
      emitPluginServiceDiagnostics(result);
      return result;
    } catch (error) {
      emitPluginDiagnosticEvent(
        "plugin service diagnostics request failed",
        { error: diagnosticError(error) },
        { prefix: "[Nuvio PluginService]", level: "error" }
      );
      throw error;
    }
  },
  cancel(requestId) {
    if (isTizen5PluginPreviewEnabled()) return Promise.resolve(cancelLegacyPluginRequest(requestId));
    if (!areTizenPluginsSupported()) return Promise.resolve(false);
    const service = serviceForPlatform();
    if (!service || !requestId) return Promise.resolve(false);
    return service.cancel({ requestId: String(requestId) });
  },
  clearCache() {
    if (isTizen5PluginPreviewEnabled()) return Promise.resolve(false);
    if (!areTizenPluginsSupported()) return Promise.resolve(false);
    const service = serviceForPlatform();
    return service ? service.clearCache() : Promise.resolve(false);
  },
  resetHealthCache() {
    cachedHealth = null;
    cachedHealthAt = 0;
    lastHealthDiagnostic = "";
  }
};
