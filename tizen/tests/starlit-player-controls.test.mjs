// The TV control bar follows the desktop and phone players: transport on the
// left, library | tracks and view | extras on the right, a seek bar split into
// chapters, and the focused button naming itself.
import assert from "node:assert/strict";
import { test } from "node:test";
import {
  TIMELINE_SEGMENT_LABELS,
  buildTimelineBoundaries,
  timelineSegmentCategoryAt
} from "../js/ui/screens/player/playerTimelineSegments.js";

const { createPlayerScreenMethods28 } = await import("../js/ui/screens/player/playerScreenMethods-28-unbind-video-events.js");

const skipIntervals = [
  { startTime: 0, endTime: 60, type: "recap", provider: "introdb" },
  { startTime: 90, endTime: 180, type: "op", provider: "aniskip" },
  { startTime: 1320, endTime: 1420, type: "ed", provider: "aniskip" },
  { startTime: 200, endTime: 200.4, type: "intro", provider: "introdb" },
  { startTime: 300, endTime: 330, type: "preview", provider: "introdb" }
];

test("the bar splits at recap, intro and credits edges and chapter starts", () => {
  assert.deepEqual(buildTimelineBoundaries([], skipIntervals, 1440), [60, 90, 180, 1320, 1420]);
  const chapters = [{ startMs: 0 }, { startMs: 600_000 }, { startMs: 180_500 }];
  // A chapter start half a second from the intro end merges into one gap.
  assert.deepEqual(buildTimelineBoundaries(chapters, skipIntervals, 1440), [60, 90, 180, 600, 1320, 1420]);
  assert.deepEqual(buildTimelineBoundaries(chapters, skipIntervals, 0), []);
  assert.deepEqual(buildTimelineBoundaries([{ startMs: 30_000 }], [], 1440), [], "a lone chapter is no split");
});

test("the playhead is named by the segment it is in", () => {
  assert.equal(timelineSegmentCategoryAt(skipIntervals, 30), "recap");
  assert.equal(timelineSegmentCategoryAt(skipIntervals, 120), "opening");
  assert.equal(timelineSegmentCategoryAt(skipIntervals, 1400), "ending");
  assert.equal(timelineSegmentCategoryAt(skipIntervals, 180), null, "segments end exclusively");
  assert.equal(timelineSegmentCategoryAt(skipIntervals, 310), null, "previews are not chapters");
  assert.equal(timelineSegmentCategoryAt(null, 30), null);
  assert.deepEqual(Object.keys(TIMELINE_SEGMENT_LABELS).sort(), ["ending", "opening", "recap"]);
});

function screen(overrides = {}) {
  return {
    ...createPlayerScreenMethods28(),
    getPlaybackControlPaused: () => true,
    getPlayerUiState: () => ({ episodesAll: [{ id: "e1" }] }),
    resolveNextEpisodeInfo: () => ({ hasAired: true }),
    isSeekBarAvailable: () => true,
    isLivePlayback: () => false,
    hasSubtitleTracksAvailable: () => true,
    hasAudioTracksAvailable: () => true,
    getPlaybackSpeed: () => 1.5,
    getPlaybackSpeedOptions: () => [1, 1.5, 2],
    isPartyButtonAvailable: () => false,
    playerChapters: [{ startMs: 0 }, { startMs: 60_000 }],
    ...overrides
  };
}

test("controls are grouped like the desktop bar with nothing hidden behind More", () => {
  const controls = screen().getControlDefinitions();
  assert.deepEqual(
    controls.map((control) => `${control.group}:${control.action}`),
    [
      "transport:playPause",
      "transport:seekBack10",
      "transport:seekForward10",
      "transport:playNextEpisode",
      "library:episodes",
      "library:source",
      "tracks:subtitleDialog",
      "tracks:audioTrack",
      "tracks:chapters",
      "tracks:speed",
      "tracks:aspect",
      "extras:streamInfo"
    ]
  );
  assert.ok(controls.every((control) => control.icon.startsWith("assets/icons/ic_player_v2_")));
  assert.equal(controls[0].title, "Play");
  assert.equal(controls[1].amount, "10");
  assert.equal(controls.find((control) => control.action === "speed").badge, "1.5×");
});

test("live playback keeps play and the tracks but drops seeking and chapters", () => {
  const controls = screen({ isLivePlayback: () => true, resolveNextEpisodeInfo: () => null, getPlaybackSpeed: () => 1 })
    .getControlDefinitions()
    .map((control) => control.action);
  assert.deepEqual(controls, ["playPause", "episodes", "source", "subtitleDialog", "audioTrack", "speed", "aspect", "streamInfo"]);
});

test("the bar renders dividers between right-hand groups, tooltips and seek digits", () => {
  const wrap = { innerHTML: "", querySelectorAll: () => [] };
  const s = screen({
    uiRefs: { controlButtons: wrap },
    controlFocusIndex: 0,
    controlFocusZone: "buttons",
    isExternalFrameMode: () => false,
    isPostPlayVisible: () => false,
    isPostPlayLoading: () => false,
    isDialogOpen: () => false,
    syncSkipIntroFocusState() {},
    renderNextEpisodeCard() {},
    syncNextEpisodeCardFocusState() {},
    syncPlayerOverlayLayoutState() {},
    renderBitmapSubtitleAtCurrentTime() {}
  });
  s.renderControlButtons();
  const html = wrap.innerHTML;
  assert.equal((html.match(/class="player-control-btn /g) || []).length, 12);
  assert.equal((html.match(/player-control-divider/g) || []).length, 2, "library | tracks | extras");
  assert.match(html, /class="player-control-btn focusable is-right-start"\s+data-action="episodes"/);
  assert.match(html, /data-action="playPause"[\s\S]*?<span class="player-control-tip" aria-hidden="true">Play<\/span>/);
  assert.match(html, /<span class="player-control-glyph" aria-hidden="true">[^]*?<b class="player-control-amount">10<\/b><\/span>/);
  assert.match(html, /<b class="player-control-badge">1.5×<\/b>/);
  assert.doesNotMatch(html, /data-action="more"/);
});
