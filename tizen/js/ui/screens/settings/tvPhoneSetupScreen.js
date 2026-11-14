import { Router } from "../../navigation/routerState.js";
import { ScreenUtils } from "../../navigation/screen.js";
import { I18n } from "../../../i18n/index.js";
import { QrCodeGenerator } from "../../../core/qr/qrCodeGenerator.js";
import { ProfileManager } from "../../../core/profile/profileManager.js";
import { PluginManager } from "../../../core/player/pluginManager.js";
import { createTvSetupRelayClient, TV_SETUP_URL } from "../../../core/phoneSetup/tvSetupRelayClient.js";
import { createTvSetupExecutor } from "../../../core/phoneSetup/tvSetupActions.js";

const tr = (key, fallback, params = {}) => I18n.t(key, params, { fallback });
const escape = (value) => String(value ?? "").replace(/[&<>"']/g,
  char => ({ "&":"&amp;", "<":"&lt;", ">":"&gt;", '"':"&quot;", "'":"&#39;" })[char]);
const connectionError = () => tr("tv_phone_connection_error", "Could not connect. Check your TV's internet connection and try again.");

export const TvPhoneSetupScreen = {
  async mount() {
    this.container = document.getElementById("tvPhoneSetup");
    const profile = String(ProfileManager.getActiveProfileId());
    const existingSession = this.phase === "connected" && this.client?.active === true &&
      this.profile === profile;
    if (!existingSession) {
      this.profile = profile;
      this.executor = createTvSetupExecutor(this.profile);
      this.client = createTvSetupRelayClient();
      this.generation = (this.generation || 0) + 1;
      this.phase = "starting";
      this.qr = null;
      this.verification = "";
      this.lastAction = "";
      this.error = "";
      this.pendingAck = null;
      this.networkFailures = 0;
      this.polling = false;
      this.lastCommandId = 0;
      this.lastPublishedState = "";
      this.lastStateCheckAt = 0;
      this.lastPublishedSourcePhase = "idle";
    }
    ScreenUtils.show(this.container);
    this.onClickBound = (event) => {
      const action = event.target?.closest?.("[data-phone-action]")?.dataset.phoneAction;
      if (action === "approve") void this.approve();
      else if (action === "retry") void this.restart();
      else if (action === "continue") void Router.back();
      else if (action === "back") this.disconnect();
    };
    this.container.addEventListener("click", this.onClickBound);
    this.render();
    if (!existingSession) await this.start(this.generation);
  },
  visible() {
    return Boolean(this.container) && Router.getCurrent() === "tvPhoneSetup";
  },
  active(generation) {
    return this.generation === generation && (this.phase === "connected" || this.visible()) &&
      String(ProfileManager.getActiveProfileId()) === this.profile;
  },
  disconnect() {
    this.generation++;
    clearTimeout(this.timer);
    const client = this.client;
    this.client = null;
    this.phase = "disconnected";
    void client?.stop().catch(() => client.forget());
    if (this.visible()) void Router.back();
  },
  async start(generation) {
    try {
      this.qr = await this.client.start("Starlit TV", this.profile);
      if (!this.active(generation)) return;
      this.phase = "waiting";
      this.render();
      // Runtime self-test happens after the QR becomes visible, never during
      // the first render or while navigating.
      void PluginManager.getRuntimeStatus({ probe: true }).catch(() => {});
      this.timer = setTimeout(() => this.poll(generation), 1600);
    } catch (_) {
      if (!this.active(generation)) return;
      this.phase = "error";
      this.error = connectionError();
      this.render();
    }
  },
  async restart() {
    this.generation++;
    clearTimeout(this.timer);
    try { await this.client.stop(); } catch (_) { this.client.forget(); }
    this.client = createTvSetupRelayClient();
    this.executor = createTvSetupExecutor(this.profile);
    this.phase = "starting";
    this.qr = null;
    this.verification = "";
    this.lastCommandId = 0;
    this.lastAction = "";
    this.pendingAck = null;
    this.networkFailures = 0;
    this.lastPublishedState = "";
    this.lastStateCheckAt = 0;
    this.lastPublishedSourcePhase = "idle";
    const generation = this.generation;
    this.render();
    await this.start(generation);
  },
  async approve() {
    if (this.phase !== "approve") return;
    const generation = this.generation;
    this.phase = "approving";
    this.render();
    try {
      await this.client.approve(true);
      if (!this.active(generation)) return;
      this.phase = "connected";
      await this.publishState(true);
      this.render();
      clearTimeout(this.timer);
      this.timer = setTimeout(() => this.poll(generation), 1100);
    } catch (_) {
      if (!this.active(generation)) return;
      this.phase = "error";
      this.error = connectionError();
      this.render();
    }
  },
  async publishState(force = false) {
    // On older TVs collecting all installed providers can be expensive.
    // Keep source-search updates responsive but avoid rebuilding and posting
    // the entire add-on/plugin catalog every 1.8s during idle playback.
    const now = Date.now();
    const interval = this.lastPublishedSourcePhase === "searching" ? 1600 : 8500;
    if (!force && now - this.lastStateCheckAt < interval) return;
    const state = this.executor.state();
    const serialized = JSON.stringify(state);
    this.lastStateCheckAt = now;
    this.lastPublishedSourcePhase = state.sourcePicker?.phase || "idle";
    if (force || serialized !== this.lastPublishedState) {
      await this.client.state(state);
      this.lastPublishedState = serialized;
    }
  },
  async poll(generation) {
    if (this.phase === "connected" &&
        String(ProfileManager.getActiveProfileId()) !== this.profile) {
      this.disconnect();
      return;
    }
    if (!this.active(generation) || this.polling) return;
    this.polling = true;
    try {
      const remote = await this.client.inspect();
      if (!this.active(generation)) return;
      if (this.phase === "waiting" && remote.claimed && remote.verification) {
        this.verification = remote.verification;
        this.phase = "approve";
        this.render();
      }
      if (this.phase === "connected") {
        // If the TV applied an install but the acknowledgement was lost to a
        // transient network problem, retry only its ACK. Never rerun the
        // installation or erase the result during a reconnect.
        if (this.pendingAck) {
          await this.client.ack(this.pendingAck.id, this.pendingAck.result);
          this.pendingAck = null;
        }
        const command = remote.commands?.[0];
        let changed = false;
        if (command && command.id !== this.lastCommandId) {
          changed = true;
          this.lastCommandId = command.id;
          let response;
          try {
            response = await this.executor.execute(command);
            this.lastAction = tr("tv_phone_change_applied", "Change applied from your phone.");
          } catch (_) {
            // Never return raw third-party fetch errors, which can embed private
            // repository addresses or credentials, to the relay.
            response = null;
            this.lastAction = tr("tv_phone_action_failed", "Could not apply this change.");
          }
          this.pendingAck = {
            id: command.id,
            result: {
              ok: Boolean(response),
              message: response ? this.lastAction : tr("tv_phone_action_failed", "Could not apply this change."),
              detail: response?.detail || null
            }
          };
          await this.client.ack(this.pendingAck.id, this.pendingAck.result);
          this.pendingAck = null;
          if (!this.active(generation)) return;
          if (this.visible()) this.render();
        }
        await this.publishState(changed);
      }
      this.networkFailures = 0;
    } catch (error) {
      if (this.active(generation)) {
        this.networkFailures++;
        if (this.networkFailures >= 5 || /expir|revoke|unauthoriz/i.test(String(error?.message || ""))) {
          this.phase = "error";
          this.error = connectionError();
        } else {
          this.lastAction = connectionError();
        }
        if (this.visible()) this.render();
      }
    } finally {
      this.polling = false;
      if (this.active(generation) && !["error", "approving"].includes(this.phase))
        this.timer = setTimeout(() => this.poll(generation), 1800);
    }
  },
  render() {
    if (!this.container) return;
    const qrVisible = Boolean(this.qr) && ["waiting", "approve", "approving"].includes(this.phase);
    const selectedAction = this.container.querySelector(".focusable.focused")?.dataset.phoneAction || "back";
    const title = tr("tv_phone_manage_title", "Manage with phone");
    this.container.innerHTML = `
      <main class="tv-phone-screen">
        <header class="tv-phone-heading">
          <span class="tv-phone-eyebrow">STARLIT TV</span>
          <h1>${escape(title)}</h1>
          <p>${escape(tr("tv_phone_scan_instruction", "Scan the QR code with your phone to manage add-ons and plugins."))}</p>
        </header>
        <div class="tv-phone-layout">
          <section class="tv-phone-qr-card">
            ${qrVisible ? '<canvas id="tv-phone-qr" role="img"></canvas>' : this.phase === "connected" ? '<div class="tv-phone-paired" aria-hidden="true">✓</div>' : '<div class="tv-phone-qr-wait"></div>'}
            ${qrVisible ? "<p>starlit.rrmtools.uk/tv/connect</p>" : ""}
            ${qrVisible ? `<div class="tv-phone-code">${escape(tr("tv_phone_pairing_code", "Code: {{code}}", { code: this.qr.code }))}</div>` : ""}
            ${this.phase === "connected" ? `<p>${escape(tr("tv_phone_connected", "Phone connected"))}</p>` : ""}
          </section>
          <section class="tv-phone-status-card">
            ${this.phase === "starting" ? `<h2>${escape(tr("tv_phone_preparing", "Preparing a secure connection…"))}</h2>` : ""}
            ${this.phase === "waiting" ? `<h2>${escape(tr("tv_phone_waiting", "Waiting for your phone"))}</h2><p>${escape(tr("tv_phone_approval_required", "Approve on the TV before the phone can make changes."))}</p>` : ""}
            ${this.phase === "approve" || this.phase === "approving" ? `<h2>${escape(tr("tv_phone_verify_title", "Approve this phone?"))}</h2><p>${escape(tr("tv_phone_verify_code", "Only approve if your phone shows the same code:"))}</p><strong class="tv-phone-verify">${escape(this.verification)}</strong><button class="focusable tv-phone-primary" data-phone-action="approve" ${this.phase === "approving" ? "disabled" : ""}>${escape(tr("tv_phone_approve", "Approve connection"))}</button>` : ""}
            ${this.phase === "connected" ? `<h2>${escape(tr("tv_phone_connected", "Phone connected"))}</h2>${this.lastAction ? `<p class="tv-phone-last">${escape(this.lastAction)}</p>` : ""}<button class="focusable" data-phone-action="continue">${escape(tr("action_back", "Back"))}</button>` : ""}
            ${this.phase === "error" ? `<h2>${escape(tr("tv_phone_unavailable", "Connection unavailable"))}</h2><p>${escape(this.error)}</p><button class="focusable" data-phone-action="retry">${escape(tr("action_retry", "Try again"))}</button>` : ""}
            <button class="focusable tv-phone-back" data-phone-action="back">${escape(tr("tv_phone_disconnect", "Disconnect and go back"))}</button>
          </section>
        </div>
      </main>`;
    if (qrVisible) {
      const canvas = this.container.querySelector("#tv-phone-qr");
      canvas.setAttribute("aria-label", title);
      Promise.resolve(QrCodeGenerator.generate(canvas, this.qr.qrUrl, 408)).catch(() => {});
    }
    const items = [...this.container.querySelectorAll(".focusable:not([disabled])")];
    items.forEach((item, index) => { item.dataset.index = String(index); });
    const focused = items.find(x => x.dataset.phoneAction === selectedAction) || items[0];
    focused?.classList.add("focused");
    focused?.focus();
  },
  onKeyDown(event) {
    const key = event?.key || "";
    const code = Number(event?.keyCode || event?.which || 0);
    if ([37, 38, 39, 40].includes(code) || ["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(key)) {
      event.preventDefault();
      ScreenUtils.moveFocus(this.container,
        code === 37 || code === 38 || key === "ArrowLeft" || key === "ArrowUp" ? -1 : 1);
    }
    if (code === 13 || key === "Enter") {
      event.preventDefault();
      if (!event.repeat) this.container.querySelector(".focusable.focused")?.click();
    }
  },
  cleanup() {
    this.container?.removeEventListener("click", this.onClickBound);
    if (this.container) ScreenUtils.hide(this.container);
    this.container = null;
    if (this.phase === "connected" && this.client?.active) {
      // The already-approved connection is shared with TV source selection and
      // playback, so navigating out of Settings must not revoke the phone.
      return;
    }
    this.generation++;
    clearTimeout(this.timer);
    const client = this.client;
    this.client = null;
    void client?.stop().catch(() => client.forget());
  }
};
