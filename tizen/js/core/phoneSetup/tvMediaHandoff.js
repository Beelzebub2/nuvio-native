import { Router } from "../../ui/navigation/routerState.js";
import { StreamScreen } from "../../ui/screens/stream/streamScreen.js";

// No stream URLs, headers, torrent identifiers, provider credentials or cookies
// leave this television. A phone chooses a short-lived opaque source handle.
const MEDIA_ID = /^[A-Za-z0-9:_-]{1,150}$/;
const REQUEST_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const SOURCE_ID = /^source:[1-9][0-9]{0,3}$/;
const hasControl = text => [...text].some(char => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127);
const clean = (value, limit = 120) => [...String(value || "")]
  .map(char => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127 ? " " : char).join("")
  .replace(/(?:https?:\/\/|magnet:\?)[^\s]+/ig, "[source]")
  .replace(/(?:token|apikey|api_key|authorization|password)\s*[:=]\s*[^\s]+/ig, "[private]")
  .trim().slice(0, limit);

export function isValidTvMediaRequest(raw) {
  return Boolean(
    raw && typeof raw === "object" && !Array.isArray(raw) &&
    REQUEST_ID.test(String(raw.requestId || "")) &&
    ["movie", "series"].includes(raw.contentType) &&
    MEDIA_ID.test(String(raw.videoId || "")) &&
    MEDIA_ID.test(String(raw.parentMetaId || "")) &&
    typeof raw.title === "string" && raw.title.trim().length > 0 &&
    raw.title.length <= 160 && !hasControl(raw.title) &&
    (raw.season == null) === (raw.episode == null) &&
    (raw.contentType !== "movie" || raw.season == null) &&
    (raw.season == null || (Number.isInteger(raw.season) && raw.season >= 0 && raw.season <= 999)) &&
    (raw.episode == null || (Number.isInteger(raw.episode) && raw.episode >= 0 && raw.episode <= 9999)) &&
    (raw.resumePositionMs == null || (Number.isSafeInteger(raw.resumePositionMs) &&
      raw.resumePositionMs >= 0 && raw.resumePositionMs <= 43_200_000))
  );
}

/**
 * Uses the TV's own stream-route pipeline, including installed add-ons, plugin
 * capabilities, debrid and P2P resolvers. TV source IDs are never sent to the
 * relay: opaque source:1 handles map back to full objects kept in TV memory.
 */
export function createTvMediaHandoff({ router = Router, streamScreen = StreamScreen } = {}) {
  let pending = null;
  let handleById = new Map();
  let streamByHandle = new Map();
  let nextHandle = 1;

  function isOwnStreamPage() {
    return pending && router.getCurrent?.() === "stream" &&
      streamScreen.params?.remoteHandoffRequestId === pending.requestId;
  }

  function state() {
    if (!pending) return {
      requestId: "", phase: "idle", title: "", message: "", sources: []
    };
    let phase = pending.phase;
    let message = "";
    let sources = [];
    if (isOwnStreamPage() && phase !== "starting") {
      const entries = Array.isArray(streamScreen.streams) ? streamScreen.streams : [];
      for (const entry of entries) {
        const id = String(entry?.id || "");
        if (!id || streamByHandle.size >= 36 && !handleById.has(id)) continue;
        let handle = handleById.get(id);
        if (!handle) {
          handle = "source:" + nextHandle++;
          handleById.set(id, handle);
        }
        streamByHandle.set(handle, id);
        if (sources.length < 36) {
          sources.push({
            id: handle,
            label: clean(entry?.title || entry?.name || entry?.description || "Stream"),
            quality: clean(entry?.quality || "", 24),
            provider: clean(entry?.addonName || "", 64)
          });
        }
      }
      phase = streamScreen.streamSearchCompleted
        ? sources.length ? "ready" : streamScreen.error ? "error" : "empty"
        : "searching";
      if (phase === "error") message = "Could not load TV sources. Try again.";
    } else if (phase === "starting" && router.getCurrent?.() === "player") {
      phase = "playing";
      pending.phase = "playing";
    } else if (phase === "searching" && !isOwnStreamPage()) {
      // The user left the requested title or profile; don't surface stale
      // options that might now refer to another playback generation.
      phase = "error";
      message = "The TV left source selection. Send the title again.";
    }
    return {
      requestId: pending.requestId,
      phase,
      title: clean(pending.title, 160),
      message,
      sources
    };
  }

  return {
    state,
    async search(media) {
      if (!isValidTvMediaRequest(media)) throw new Error("Invalid playback request");
      pending = { requestId: media.requestId, title: media.title, phase: "searching" };
      handleById = new Map();
      streamByHandle = new Map();
      nextHandle = 1;
      await router.navigate("stream", {
        itemType: media.contentType,
        itemId: media.parentMetaId,
        videoId: media.videoId,
        itemTitle: media.title,
        season: media.season ?? null,
        episode: media.episode ?? null,
        resumePositionMs: media.resumePositionMs || 0,
        manualSelection: true,
        remoteHandoffRequestId: media.requestId,
      });
      return { message: "TV searching for sources" };
    },
    async select(requestId, sourceId) {
      if (!pending || requestId !== pending.requestId || !REQUEST_ID.test(requestId) ||
          !SOURCE_ID.test(String(sourceId || "")) || !isOwnStreamPage())
        throw new Error("Source request expired");
      // Refresh the selection map only against the *current* stream page.
      state();
      const streamId = streamByHandle.get(sourceId);
      if (!streamId || !streamScreen.streams.some(entry => String(entry.id) === streamId))
        throw new Error("Source is no longer available");
      pending.phase = "starting";
      await streamScreen.playStream(streamId);
      return { message: "TV preparing playback" };
    },
    cancel(requestId) {
      if (!pending || requestId !== pending.requestId) throw new Error("Source request expired");
      pending = null;
      handleById.clear();
      streamByHandle.clear();
      return { message: "Source selection cancelled" };
    }
  };
}
