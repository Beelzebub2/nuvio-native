/* eslint-disable no-unused-vars */
import * as internals from "./playerScreenContext.js";
import { PartyRepository } from "../../../core/party/partyRepository.js";
// One icon family with the desktop and phone players (24 px grid, 1.8 px round strokes).
const PLAYER_ICONS = {
  play: "assets/icons/ic_player_v2_play.svg",
  pause: "assets/icons/ic_player_v2_pause.svg",
  replay: "assets/icons/ic_player_v2_replay.svg",
  forward: "assets/icons/ic_player_v2_forward.svg",
  skipNext: "assets/icons/ic_player_v2_skip_next.svg",
  episodes: "assets/icons/ic_player_v2_episodes.svg",
  source: "assets/icons/ic_player_v2_source.svg",
  subtitles: "assets/icons/ic_player_v2_subtitles.svg",
  audio: "assets/icons/ic_player_v2_audio.svg",
  chapters: "assets/icons/ic_player_v2_chapters.svg",
  speed: "assets/icons/ic_player_v2_speed.svg",
  aspect: "assets/icons/ic_player_v2_aspect.svg",
  party: "assets/icons/ic_player_v2_party.svg",
  info: "assets/icons/ic_player_v2_info.svg"
};
export function createPlayerScreenMethods28() {
  const { PlayerController, t, clamp, escapeHtml } = internals;
  return {
    unbindVideoEvents() {
      this.videoListeners.forEach(({ target, eventName, handler }) => {
        target?.removeEventListener?.(eventName, handler);
      });
      this.videoListeners = [];
    },
    getPlaybackControlPaused() {
      const conversion = PlayerController.webOsDtsAudioState;
      // Native decoder pauses during a track reload are not user commands.
      if (conversion && (conversion.preparing || conversion.cancelReload || conversion.buffering)) {
        return conversion.controlPaused;
      }
      return this.paused;
    },
    /**
     * The control bar of the desktop and phone players: transport on the left, then
     * library, tracks and view, and extras on the right. The TV is wide enough to show
     * every button, so nothing folds into a More row; the focused button names itself.
     */
    getControlDefinitions() {
      const paused = this.getPlaybackControlPaused();
      const uiState = this.getPlayerUiState();
      const nextEpisode = this.resolveNextEpisodeInfo();
      const isLive = this.isLivePlayback?.() === true;
      const transport = [
        {
          action: "playPause",
          icon: paused ? PLAYER_ICONS.play : PLAYER_ICONS.pause,
          title: paused ? t("cd_play", {}, "Play") : t("cd_pause", {}, "Pause"),
          primary: true
        }
      ];
      if (this.isSeekBarAvailable() && !isLive) {
        transport.push(
          { action: "seekBack10", icon: PLAYER_ICONS.replay, amount: "10", title: t("player_seek_back_10", {}, "Back 10 seconds") },
          { action: "seekForward10", icon: PLAYER_ICONS.forward, amount: "10", title: t("player_seek_forward_10", {}, "Forward 10 seconds") }
        );
      }
      if (nextEpisode?.hasAired && !this.nextEpisodeLaunching) {
        transport.push({ action: "playNextEpisode", icon: PLAYER_ICONS.skipNext, title: t("next_episode_label", {}, "Next episode") });
      }
      const library = [];
      if (Array.isArray(uiState.episodesAll) && uiState.episodesAll.length) {
        library.push({ action: "episodes", icon: PLAYER_ICONS.episodes, title: t("episodes_panel_title", {}, "Episodes") });
      }
      library.push({ action: "source", icon: PLAYER_ICONS.source, title: t("sources_title", {}, "Sources") });
      const tracks = [];
      if (this.hasSubtitleTracksAvailable()) {
        tracks.push({ action: "subtitleDialog", icon: PLAYER_ICONS.subtitles, title: t("subtitle_dialog_title", {}, "Subtitles") });
      }
      if (this.hasAudioTracksAvailable()) {
        tracks.push({ action: "audioTrack", icon: PLAYER_ICONS.audio, title: t("audio_dialog_title", {}, "Audio") });
      }
      if (Array.isArray(this.playerChapters) && this.playerChapters.length > 1 && !isLive) {
        tracks.push({ action: "chapters", icon: PLAYER_ICONS.chapters, title: t("player_chapters", {}, "Chapters") });
      }
      const playbackSpeedOptions = typeof this.getPlaybackSpeedOptions === "function" ? this.getPlaybackSpeedOptions() : [];
      if (playbackSpeedOptions.length > 1) {
        const playbackSpeed = Number(this.getPlaybackSpeed?.() || 1);
        const speedText = `${String(Number(playbackSpeed.toFixed(2)))}×`;
        tracks.push({
          action: "speed",
          icon: PLAYER_ICONS.speed,
          title: `${t("player_playback_speed", {}, "Playback speed")} · ${speedText}`,
          badge: playbackSpeed === 1 ? "" : speedText
        });
      }
      tracks.push({ action: "aspect", icon: PLAYER_ICONS.aspect, title: t("player_more_aspect_ratio", {}, "Aspect Ratio") });
      const extras = [];
      if (this.isPartyButtonAvailable?.()) {
        extras.push({
          action: "party",
          icon: PLAYER_ICONS.party,
          title: t("party_title", {}, "Watch party"),
          className: PartyRepository.getState().party ? "is-party-active" : ""
        });
      }
      extras.push({ action: "streamInfo", icon: PLAYER_ICONS.info, title: t("cd_stream_info", {}, "Stream info") });
      return [
        ...transport.map((control) => ({ ...control, group: "transport" })),
        ...library.map((control) => ({ ...control, group: "library" })),
        ...tracks.map((control) => ({ ...control, group: "tracks" })),
        ...extras.map((control) => ({ ...control, group: "extras" }))
      ];
    },
    getControlRenderSignature(controls = this.getControlDefinitions()) {
      return JSON.stringify(
        controls.map((control) => [
          control.action || "",
          control.icon || "",
          control.title || "",
          Boolean(control.primary),
          control.className || "",
          control.group || "",
          control.amount || "",
          control.badge || ""
        ])
      );
    },
    renderControlButtons() {
      if (this.isExternalFrameMode()) {
        return;
      }
      const wrap = this.uiRefs?.controlButtons;
      if (!wrap) {
        return;
      }
      if (this.isPostPlayVisible() || this.isPostPlayLoading()) {
        wrap.innerHTML = "";
        this.renderedControlSignature = "";
        this.syncPlayerOverlayLayoutState();
        return;
      }
      const controls = this.getControlDefinitions();
      const controlRenderSignature = this.getControlRenderSignature(controls);
      if (this.stickyProgressFocus && this.controlsVisible && !this.isDialogOpen() && this.isSeekBarAvailable()) {
        this.controlFocusZone = "progress";
      }
      this.controlFocusIndex = clamp(this.controlFocusIndex, 0, Math.max(0, controls.length - 1));
      const mountedButtons = Array.from(wrap.querySelectorAll(".player-control-btn"));
      const controlsMatchDom =
        mountedButtons.length === controls.length &&
        mountedButtons.every((button, index) => button.dataset.action === String(controls[index]?.action || ""));
      if (!controlsMatchDom || this.renderedControlSignature !== controlRenderSignature) {
        // Right-hand groups are split by dividers; the first one is pushed to the right edge.
        // The focused button's name shows in the middle of the bar (the tip), where it
        // cannot cover the seek row the way a tooltip above the button would.
        wrap.innerHTML = controls
          .map((control, controlIndex) => {
            const previousGroup = controlIndex > 0 ? controls[controlIndex - 1].group : "";
            const startsGroup = control.group !== previousGroup;
            const startsRight = startsGroup && previousGroup === "transport";
            const divider = startsGroup && previousGroup && !startsRight ? `<span class="player-control-divider" aria-hidden="true"></span>` : "";
            const classes = [
              "player-control-btn",
              "focusable",
              control.primary ? "is-primary" : "",
              startsRight ? "is-right-start" : "",
              control.className || ""
            ].filter(Boolean);
            return `${divider}
            <button class="${escapeHtml(classes.join(" "))}"
                    data-action="${escapeHtml(control.action)}"
                    aria-label="${escapeHtml(control.title || "")}">
              <span class="player-control-glyph" aria-hidden="true"><span class="player-control-icon player-control-icon-mask" style="-webkit-mask-image:url('${escapeHtml(control.icon)}');mask-image:url('${escapeHtml(control.icon)}');"></span>${
                control.amount ? `<b class="player-control-amount">${escapeHtml(control.amount)}</b>` : ""
              }${control.badge ? `<b class="player-control-badge">${escapeHtml(control.badge)}</b>` : ""}</span>
              <span class="player-control-tip" aria-hidden="true">${escapeHtml(control.title || "")}</span>
            </button>
          `;
          })
          .join("");
      }
      this.renderedControlSignature = controlRenderSignature;
      const buttons = Array.from(wrap.querySelectorAll(".player-control-btn"));
      buttons.forEach((button, index) => {
        button.classList.toggle("focused", this.controlFocusZone === "buttons" && index === this.controlFocusIndex);
      });
      const progressShell = this.uiRefs?.progressShell;
      if (progressShell) {
        progressShell.classList.toggle("focused", this.controlFocusZone === "progress");
      }
      if (this.controlFocusZone === "progress") {
        buttons.forEach((button) => {
          if (typeof button.blur === "function") {
            button.blur();
          }
        });
        if (progressShell && document.activeElement !== progressShell && typeof progressShell.focus === "function") {
          progressShell.focus();
        }
      } else if (this.controlFocusZone === "buttons") {
        if (progressShell && document.activeElement === progressShell && typeof progressShell.blur === "function") {
          progressShell.blur();
        }
        const focusedButton = buttons[this.controlFocusIndex] || null;
        if (focusedButton && document.activeElement !== focusedButton && typeof focusedButton.focus === "function") {
          focusedButton.focus();
        }
      } else if (this.controlFocusZone === "skipIntro" || this.controlFocusZone === "nextEpisode") {
        buttons.forEach((button) => {
          if (typeof button.blur === "function") {
            button.blur();
          }
        });
        if (progressShell && document.activeElement === progressShell && typeof progressShell.blur === "function") {
          progressShell.blur();
        }
      }
      this.syncSkipIntroFocusState();
      this.renderNextEpisodeCard();
      this.syncNextEpisodeCardFocusState();
      this.syncPlayerOverlayLayoutState();
      this.renderBitmapSubtitleAtCurrentTime();
    },
    syncControlFocusDom() {
      if (this.isExternalFrameMode()) {
        return;
      }
      const wrap = this.uiRefs?.controlButtons;
      if (!wrap) {
        return;
      }
      const controls = this.getControlDefinitions();
      const buttons = Array.from(wrap.querySelectorAll(".player-control-btn"));
      const controlsMatchDom = buttons.every((button, index) => button.dataset.action === String(controls[index]?.action || ""));
      // This path is used only when focus moves. If playback state changed the
      // available controls without rendering them first, fall back to the full
      // state render instead of focusing a stale button.
      if (
        buttons.length !== controls.length ||
        !controlsMatchDom ||
        this.renderedControlSignature !== this.getControlRenderSignature(controls)
      ) {
        this.renderControlButtons();
        return;
      }
      this.controlFocusIndex = clamp(this.controlFocusIndex, 0, Math.max(0, controls.length - 1));
      buttons.forEach((button, index) => {
        button.classList.toggle("focused", this.controlFocusZone === "buttons" && index === this.controlFocusIndex);
      });
      const progressShell = this.uiRefs?.progressShell;
      progressShell?.classList.toggle("focused", this.controlFocusZone === "progress");
      if (this.controlFocusZone === "progress") {
        buttons.forEach((button) => button.blur?.());
        if (progressShell && document.activeElement !== progressShell) {
          progressShell.focus?.();
        }
      } else if (this.controlFocusZone === "buttons") {
        if (progressShell && document.activeElement === progressShell) {
          progressShell.blur?.();
        }
        const focusedButton = buttons[this.controlFocusIndex] || null;
        if (focusedButton && document.activeElement !== focusedButton) {
          focusedButton.focus?.();
        }
      } else if (this.controlFocusZone === "skipIntro" || this.controlFocusZone === "nextEpisode") {
        buttons.forEach((button) => button.blur?.());
        if (progressShell && document.activeElement === progressShell) {
          progressShell.blur?.();
        }
      }
      // Preserve the non-markup side effects of renderControlButtons().
      this.syncSkipIntroFocusState();
      this.renderNextEpisodeCard();
      this.syncNextEpisodeCardFocusState();
      this.syncPlayerOverlayLayoutState();
      this.renderBitmapSubtitleAtCurrentTime();
    },
    isDialogOpen() {
      return (
        this.subtitleDialogVisible ||
        this.subtitleTimingDialogVisible ||
        this.subtitleDelayOverlayVisible ||
        this.audioDialogVisible ||
        this.sourcesPanelVisible ||
        this.chaptersPanelVisible ||
        this.episodePanelVisible ||
        this.speedDialogVisible ||
        Boolean(this.partyPanelVisible)
      );
    },
    syncPlayerOverlayLayoutState() {
      const root = this.uiRefs?.root;
      if (!root) {
        return;
      }
      const postPlayState = this.getPostPlayState();
      const postPlayVisible = Boolean(postPlayState.isVisible);
      const postPlayTrailer = Boolean(postPlayState.isTrailerPlaying);
      const postPlayTrailerConsumed = Boolean(postPlayVisible && postPlayState.hasAutoPlayedTrailer);
      root.classList.toggle("controls-visible", Boolean(this.controlsVisible) && !this.isExternalFrameMode() && !postPlayVisible);
      root.classList.toggle("post-play-visible", postPlayVisible);
      root.classList.toggle("post-play-loading", Boolean(postPlayState.isLoadingRecommendation));
      root.classList.toggle("post-play-trailer", postPlayTrailer);
      root.classList.toggle("post-play-trailer-consumed", postPlayTrailerConsumed);
      root.classList.toggle("post-play-returning", Boolean(postPlayState.hasReturnedToPlayer));
      this.container?.classList.toggle("post-play-visible", postPlayVisible);
      this.container?.classList.toggle("post-play-trailer", postPlayTrailer);
      this.container?.classList.toggle("post-play-trailer-consumed", postPlayTrailerConsumed);
      this.container?.classList.toggle("post-play-returning", Boolean(postPlayState.hasReturnedToPlayer));
      this.syncPostPlayPlayerSurface(postPlayState);
      this.setPostPlaySubtitleOutputHidden(postPlayVisible);
      this.syncPlayerActionOverlayOffset();
    },
    cancelPostPlayPlayerSurfaceAnimation() {
      if (this.postPlayPlayerSurfaceAnimationFrame == null) {
        return;
      }
      if (this.postPlayPlayerSurfaceAnimationUsesRaf) {
        globalThis.cancelAnimationFrame?.(this.postPlayPlayerSurfaceAnimationFrame);
      } else {
        clearTimeout(this.postPlayPlayerSurfaceAnimationFrame);
      }
      this.postPlayPlayerSurfaceAnimationFrame = null;
    }
  };
}
