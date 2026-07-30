// Linux player bridge for Nuvio Desktop.
//
// Phase 1: embed libmpv into the host AWT Canvas's X11 window (via mpv's
// "wid" option) and implement the playback/track/subtitle JNI surface the
// Kotlin NativePlayerBridge declares. Playback state is polled by the
// Kotlin side through the getter methods; the event sink is used only for
// the (stubbed) webview control overlay, so Phase 1 forwards nothing.
//
// Parity note: addon/debrid streams reach this bridge already resolved to
// a URL plus HTTP header lines. We forward headerLines verbatim to mpv's
// http-header-fields, exactly like the macOS/Windows bridges, so header-
// gated addons and debrid links behave identically.

#include <jni.h>
#include <mpv/client.h>

#include <gtk/gtk.h>
#include <gdk/gdkx.h>
#include <webkit2/webkit2.h>
#include <X11/Xlib.h>
#include <X11/extensions/Xcomposite.h>

#include <atomic>
#include <clocale>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <functional>
#include <mutex>
#include <cstdlib>
#include <set>
#include <string>
#include <thread>
#include <vector>

// Diagnostic logging is opt-in via NUVIO_BRIDGE_DEBUG=1 so a normal run is quiet;
// genuine errors always log via NUVIO_ERR.
static bool nuvioDebug() {
    static const bool on = std::getenv("NUVIO_BRIDGE_DEBUG") != nullptr;
    return on;
}
#define NUVIO_ERR(...) do { fprintf(stderr, "[nuvio-bridge] " __VA_ARGS__); fputc('\n', stderr); fflush(stderr); } while (0)
#define NUVIO_LOG(...) do { if (nuvioDebug()) { NUVIO_ERR(__VA_ARGS__); } } while (0)

namespace {

JavaVM *gVm = nullptr;

struct Player {
    mpv_handle *mpv = nullptr;
    std::thread eventThread;
    std::atomic<bool> running{false};
    std::atomic<bool> ended{false};
    jobject eventSink = nullptr;    // global ref, JS control events dispatch here
    jmethodID eventMethod = nullptr; // onPlayerEvent(String, double)
    // Phase 2: WebKitGTK controls overlay, all touched only on the GTK thread
    GtkWidget *gtkWindow = nullptr;
    WebKitWebView *webview = nullptr;
    Window hostXid = 0;
    Window overlayXid = 0;   // controls window, composite-redirected offscreen
                             // (invisible on screen but still receives input)
    guint updateTimer = 0;    // 200ms: state push + input raise
    guint compositeTimer = 0; // fast: snapshot controls page -> mpv overlay
    bool overlayActive = true;   // controls currently visible/interacting
    int fadeTicks = 0;           // extra composite ticks to render the fade-out
    bool overlayPushed = false;  // an overlay is currently set on mpv
    // Async WebKit snapshot of the controls page (premultiplied ARGB32 with real
    // alpha). Reading the redirected window's X pixmap instead is renderer- and
    // driver-dependent: on NVIDIA the dmabuf renderer leaves the pixmap empty and
    // the fallback renderer fills the page background opaque, so the overlay
    // either vanishes or blacks out the video underneath.
    cairo_surface_t *snapSurf = nullptr;      // buffer mpv's overlay points at
    cairo_surface_t *snapSurfPrev = nullptr;  // kept one push longer: mpv may
                                              // still sample it mid-frame
    bool snapInFlight = false;
    std::atomic<bool> firstFrameShown{false};  // gates the loading-screen composite
};

// ---- Player liveness -----------------------------------------------------
// The GTK timers (compositeTick / pushPlayerUpdate) hold a Player* and run on
// the detached GTK thread. They must never touch a Player that dispose() has
// freed, nor a half-unloaded process during exit. Guard every callback: skip if
// the process is shutting down or the Player is no longer registered as live.
std::mutex gLiveMutex;
std::set<Player *> gLivePlayers;
std::atomic<bool> gShuttingDown{false};

bool playerAlive(Player *p) {
    if (gShuttingDown.load()) return false;
    std::lock_guard<std::mutex> lk(gLiveMutex);
    return gLivePlayers.find(p) != gLivePlayers.end();
}

// ---- GTK thread ----------------------------------------------------------
// GTK is not thread-safe: it is initialised on a dedicated thread that owns
// the default main context + loop, and every GTK/WebKit call is marshalled
// there via g_main_context_invoke.

std::once_flag gGtkOnce;
std::atomic<bool> gGtkReady{false};
std::thread gGtkThread;

void gtkThreadMain() {
    // Force the X11 GDK backend. On Wayland sessions (e.g. KDE Plasma) GTK would
    // otherwise pick the Wayland backend and the controls window would be a
    // Wayland surface — but we drive it with X11/XComposite (the AWT host is an
    // XWayland X11 window), which fails with BadMatch on Composite. XWayland
    // always provides X11, so this is safe and matches GDK_BACKEND=x11.
    gdk_set_allowed_backends("x11");
    gtk_init(nullptr, nullptr);
    gGtkReady.store(true);
    gtk_main();
}

void ensureGtk() {
    std::call_once(gGtkOnce, [] {
        gGtkThread = std::thread(gtkThreadMain);
        gGtkThread.detach();
        while (!gGtkReady.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
    });
}

// Escape a UTF-8 string as a JS string literal (single-quoted).
std::string jsLiteral(const std::string &s) {
    std::string out = "'";
    for (char c : s) {
        switch (c) {
            case '\\': out += "\\\\"; break;
            case '\'': out += "\\'"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out.push_back(c);
        }
    }
    out.push_back('\'');
    return out;
}

void evalJs(WebKitWebView *webview, const std::string &script) {
    if (!webview) return;
    webkit_web_view_evaluate_javascript(webview, script.c_str(), -1, nullptr,
                                        nullptr, nullptr, nullptr, nullptr);
}

// Run fn on the GTK thread and block until it completes.
struct SyncCall {
    std::function<void()> fn;
    std::mutex m;
    std::condition_variable cv;
    bool done = false;
};

gboolean syncTrampoline(gpointer data) {
    auto *s = static_cast<SyncCall *>(data);
    s->fn();
    {
        std::lock_guard<std::mutex> lock(s->m);
        s->done = true;
    }
    s->cv.notify_one();
    return G_SOURCE_REMOVE;
}

void gtkSync(std::function<void()> fn) {
    if (!gGtkReady.load()) return;
    SyncCall s;
    s.fn = std::move(fn);
    g_main_context_invoke(nullptr, syncTrampoline, &s);
    std::unique_lock<std::mutex> lock(s.m);
    s.cv.wait(lock, [&] { return s.done; });
}

// ---- small helpers -------------------------------------------------------

std::string jstringToUtf8(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring utf8ToJstring(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}

double mpvGetDouble(mpv_handle *mpv, const char *name) {
    double out = 0.0;
    if (mpv_get_property(mpv, name, MPV_FORMAT_DOUBLE, &out) < 0) return 0.0;
    return out;
}

int64_t mpvGetInt(mpv_handle *mpv, const char *name) {
    int64_t out = 0;
    if (mpv_get_property(mpv, name, MPV_FORMAT_INT64, &out) < 0) return 0;
    return out;
}

bool mpvGetFlag(mpv_handle *mpv, const char *name) {
    int flag = 0;
    if (mpv_get_property(mpv, name, MPV_FORMAT_FLAG, &flag) < 0) return false;
    return flag != 0;
}

void mpvSetFlag(mpv_handle *mpv, const char *name, bool value) {
    int flag = value ? 1 : 0;
    mpv_set_property(mpv, name, MPV_FORMAT_FLAG, &flag);
}

// "Loading" for the UI, mirroring the macOS bridge (rawLoadingWithPaused). Stays
// true through the whole file-open phase (no duration/tracks yet) so Nuvio keeps
// its loading screen up instead of revealing mpv's black frame — e.g. while a
// non-faststart MP4 fetches its moov and seeks to the resume point.
bool computeLoading(mpv_handle *mpv) {
    if (!mpv) return true;
    bool paused = mpvGetFlag(mpv, "pause");
    bool eof = mpvGetFlag(mpv, "eof-reached");
    bool idle = mpvGetFlag(mpv, "core-idle");
    bool bufferingCache = mpvGetFlag(mpv, "paused-for-cache");
    bool fileReady = mpvGetDouble(mpv, "duration") > 0.0 ||
                     mpvGetInt(mpv, "track-list/count") > 0;
    return !fileReady || (idle && !paused && !eof) || bufferingCache;
}

// Loading for the whole initial open: stay true until the FIRST FRAME is actually
// shown, so Nuvio's opening overlay (dismissed the first time isLoading is false,
// a one-way latch) survives mpv's flag flicker during open + resume-seek. After
// the first frame, fall back to computeLoading so mid-playback rebuffers still show.
bool playerLoading(Player *p) {
    if (!p || !p->mpv) return true;
    return !p->firstFrameShown.load() || computeLoading(p->mpv);
}

void mpvSetDouble(mpv_handle *mpv, const char *name, double value) {
    mpv_set_property(mpv, name, MPV_FORMAT_DOUBLE, &value);
}

// mpv http-header-fields wants a comma-separated list; commas and
// backslashes inside a header value must be backslash-escaped.
std::string joinHeaderFields(const std::vector<std::string> &headers) {
    std::string joined;
    for (size_t i = 0; i < headers.size(); ++i) {
        if (i > 0) joined.push_back(',');
        for (char c : headers[i]) {
            if (c == '\\' || c == ',') joined.push_back('\\');
            joined.push_back(c);
        }
    }
    return joined;
}

Player *asPlayer(jlong handle) { return reinterpret_cast<Player *>(handle); }

// ---- WebKitGTK controls overlay -----------------------------------------
// The controls are the SAME shared HTML page macOS/Windows use. JS talks to
// us via window.webkit.messageHandlers.player.postMessage({type,value}) — a
// WebKit convention WebKitGTK implements natively — and we push state back
// via window.playerControls()/window.playerUpdate(), identical to WKWebView.

JNIEnv *attachGtkThread() {
    JNIEnv *env = nullptr;
    if (!gVm) return nullptr;
    if (gVm->GetEnv((void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if (gVm->AttachCurrentThread((void **)&env, nullptr) == JNI_OK) return env;
    return nullptr;
}

// JS -> native: forward {type, value} to NativePlayerEventSink.onPlayerEvent.
void onPlayerMessage(WebKitUserContentManager *, WebKitJavascriptResult *js, gpointer data) {
    auto *player = static_cast<Player *>(data);
    if (!player->eventSink || !player->eventMethod) return;
    JSCValue *msg = webkit_javascript_result_get_js_value(js);
    if (!msg || !jsc_value_is_object(msg)) return;
    JSCValue *typeV = jsc_value_object_get_property(msg, "type");
    JSCValue *valV = jsc_value_object_get_property(msg, "value");
    char *type = typeV ? jsc_value_to_string(typeV) : nullptr;
    double value = (valV && jsc_value_is_number(valV)) ? jsc_value_to_double(valV) : 0.0;
    // Track whether the controls chrome is on screen so we only pay the pixmap
    // readback + overlay cost while it is actually visible. hideChrome means the
    // chrome faded out; every other event (cursor/keep-visible/toggle/...) means
    // it is up. cursorActivity also arrives while hidden (mouse woke the UI) and
    // must re-activate compositing so the fade-in is actually shown.
    if (type) {
        if (strcmp(type, "hideChrome") == 0) {
            player->overlayActive = false;
            player->fadeTicks = 18;  // keep compositing ~0.5s to render the fade-out
        } else {
            player->overlayActive = true;
            player->fadeTicks = 0;
        }
    }
    JNIEnv *env = attachGtkThread();
    if (env && type) {
        jstring jtype = env->NewStringUTF(type);
        env->CallVoidMethod(player->eventSink, player->eventMethod, jtype, (jdouble)value);
        env->DeleteLocalRef(jtype);
    }
    if (type) g_free(type);
    if (typeV) g_object_unref(typeV);
    if (valV) g_object_unref(valV);
}

// Free the snapshot buffers (safe to call with none allocated). Only after
// overlay-remove or teardown — mpv's overlay points into snapSurf's data.
void releaseSnapshots(Player *player) {
    if (player->snapSurf) cairo_surface_destroy(player->snapSurf);
    if (player->snapSurfPrev) cairo_surface_destroy(player->snapSurfPrev);
    player->snapSurf = player->snapSurfPrev = nullptr;
}

// Completion of the async controls snapshot: hand the premultiplied BGRA pixels
// to mpv as an OSD overlay. Runs on the GTK thread like the tick that issued it.
void onOverlaySnapshot(GObject *src, GAsyncResult *res, gpointer data) {
    auto *player = static_cast<Player *>(data);
    GError *err = nullptr;
    cairo_surface_t *surf =
        webkit_web_view_get_snapshot_finish(WEBKIT_WEB_VIEW(src), res, &err);
    if (err) g_error_free(err);
    if (!playerAlive(player)) {
        if (surf) cairo_surface_destroy(surf);
        return;
    }
    player->snapInFlight = false;
    if (!surf) return;
    if (cairo_image_surface_get_format(surf) != CAIRO_FORMAT_ARGB32 ||
        cairo_image_surface_get_width(surf) <= 0 ||
        cairo_image_surface_get_height(surf) <= 0 || !player->mpv) {
        cairo_surface_destroy(surf);
        return;
    }
    cairo_surface_flush(surf);
    char addr[32], sw[16], sh[16], sstride[16];
    snprintf(addr, sizeof addr, "&%zu",
             (size_t)(uintptr_t)cairo_image_surface_get_data(surf));
    snprintf(sw, sizeof sw, "%d", cairo_image_surface_get_width(surf));
    snprintf(sh, sizeof sh, "%d", cairo_image_surface_get_height(surf));
    snprintf(sstride, sizeof sstride, "%d", cairo_image_surface_get_stride(surf));
    const char *cmd[] = {"overlay-add", "0", "0", "0", addr, "0",
                         "bgra", sw, sh, sstride, nullptr};
    mpv_command(player->mpv, cmd);
    player->overlayPushed = true;
    if (player->snapSurfPrev) cairo_surface_destroy(player->snapSurfPrev);
    player->snapSurfPrev = player->snapSurf;
    player->snapSurf = surf;
}

// Snapshot the controls page (premultiplied BGRA with real alpha) and hand it
// to mpv as an OSD overlay, so mpv blends the HTML controls over the video in its
// single window (XWayland won't alpha-blend sibling windows; mpv does the compose
// that Core Animation / DWM do on macOS / Windows). Only runs while the chrome is
// visible (plus a short fade-out grace) so normal watching pays nothing.
void compositeOverlay(Player *player) {
    if (!player->overlayXid || !player->mpv || !player->gtkWindow) return;
    GdkWindow *gw = gtk_widget_get_window(player->gtkWindow);
    if (!gw) return;
    Display *dpy = GDK_WINDOW_XDISPLAY(gw);
    // Also composite while loading (before the first frame, or during a rebuffer)
    // so Nuvio's loading screen — poster, title, spinner — shows over mpv's black
    // instead of a bare black screen. mpv is not decoding then, so it is free.
    bool loading = playerLoading(player);
    bool active = loading || player->overlayActive || player->fadeTicks > 0;
    if (!active) {
        if (player->overlayPushed) {
            const char *rm[] = {"overlay-remove", "0", nullptr};
            mpv_command(player->mpv, rm);
            player->overlayPushed = false;
            releaseSnapshots(player);
        }
        return;
    }
    // Track the host (video) size: on resize/fullscreen the host canvas changes
    // size but the overlay does not, so the controls + their click hit-area drift
    // out of alignment. Resize the overlay to match, then compose next tick.
    XWindowAttributes hostWa;
    if (XGetWindowAttributes(dpy, player->hostXid, &hostWa) && hostWa.width > 0 &&
        hostWa.height > 0) {
        XWindowAttributes ovWa0;
        if (XGetWindowAttributes(dpy, player->overlayXid, &ovWa0) &&
            (ovWa0.width != hostWa.width || ovWa0.height != hostWa.height)) {
            gtk_window_resize(GTK_WINDOW(player->gtkWindow), hostWa.width, hostWa.height);
            return;
        }
    }
    if (!player->snapInFlight && player->webview) {
        player->snapInFlight = true;
        webkit_web_view_get_snapshot(player->webview, WEBKIT_SNAPSHOT_REGION_VISIBLE,
                                     WEBKIT_SNAPSHOT_OPTIONS_TRANSPARENT_BACKGROUND,
                                     nullptr, onOverlaySnapshot, player);
    }
    // The redirected (invisible) overlay window is never presented, so on some
    // compositors (mutter's XWayland) its GTK frame clock stalls — freezing the
    // page's CSS fades/spinners mid-flight, so hideChrome never fires and the
    // loading screen never animates. Keep the clock ticking while we composite.
    {
        GdkWindow *ovGw = gtk_widget_get_window(player->gtkWindow);
        GdkFrameClock *fc = ovGw ? gdk_window_get_frame_clock(ovGw) : nullptr;
        if (fc) gdk_frame_clock_request_phase(fc, GDK_FRAME_CLOCK_PHASE_UPDATE);
    }
    if (!player->overlayActive && player->fadeTicks > 0) player->fadeTicks--;
}

// Fast timer: composite the controls over the video (cheap while hidden).
gboolean compositeTick(gpointer data) {
    auto *player = static_cast<Player *>(data);
    if (!playerAlive(player)) return G_SOURCE_REMOVE;
    if (player->mpv && player->gtkWindow) compositeOverlay(player);
    return G_SOURCE_CONTINUE;
}

// JSON-escape a UTF-8 string for embedding in the track JSON we hand the
// controls webview / Kotlin decoder.
std::string jsonEscape(const std::string &s) {
    std::string o;
    o.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            default:
                if (c < 0x20) {
                    char b[8];
                    snprintf(b, sizeof(b), "\\u%04x", c);
                    o += b;
                } else {
                    o += static_cast<char>(c);
                }
        }
    }
    return o;
}

// Read an mpv string property, trimmed; "" if unset.
std::string mpvGetStr(mpv_handle *mpv, const std::string &name) {
    char *v = mpv_get_property_string(mpv, name.c_str());
    std::string out = v ? v : "";
    if (v) mpv_free(v);
    size_t a = out.find_first_not_of(" \t\r\n");
    if (a == std::string::npos) return "";
    size_t b = out.find_last_not_of(" \t\r\n");
    return out.substr(a, b - a + 1);
}

// Build the formatted track list both the controls webview and the Kotlin
// NativeMpvTrack decoder expect (macOS parity — mirrors tracksJsonForType):
// [{"index":N,"id":"..","label":"..","language":"..","selected":bool,"forced":bool}]
// (raw mpv track-list JSON does NOT match: id is an int, no index/label, lang!=language.)
std::string buildTracksJson(mpv_handle *mpv, const char *wantedType) {
    if (!mpv) return "[]";
    int64_t count = mpvGetInt(mpv, "track-list/count");
    bool isSub = std::string(wantedType) == "sub";
    bool isAudio = std::string(wantedType) == "audio";
    std::string out = "[";
    int logicalIndex = 0;
    bool first = true;
    for (int64_t i = 0; i < count; i++) {
        std::string pfx = "track-list/" + std::to_string(i);
        if (mpvGetStr(mpv, pfx + "/type") != wantedType) continue;
        int64_t id = mpvGetInt(mpv, (pfx + "/id").c_str());
        std::string title = mpvGetStr(mpv, pfx + "/title");
        std::string lang = mpvGetStr(mpv, pfx + "/lang");
        std::string codec = mpvGetStr(mpv, pfx + "/codec");
        // Clean channel-layout name. mpv names unknown layouts "unknownN" (e.g.
        // "unknown2"); map from the channel count to a friendly name instead.
        std::string channels;
        if (isAudio) {
            std::string rawCh = mpvGetStr(mpv, pfx + "/demux-channels");
            if (!rawCh.empty() && rawCh.rfind("unknown", 0) != 0) {
                channels = rawCh == "mono" ? "Mono"
                         : (rawCh == "stereo" ? "Stereo" : rawCh);
            } else {
                int64_t nch = mpvGetInt(mpv, (pfx + "/demux-channel-count").c_str());
                if (nch == 1) channels = "Mono";
                else if (nch == 2) channels = "Stereo";
                else if (nch == 6) channels = "5.1";
                else if (nch == 8) channels = "7.1";
                else if (nch > 0) channels = std::to_string(nch) + "ch";
            }
        }
        bool selected = mpvGetFlag(mpv, (pfx + "/selected").c_str());
        bool forced = mpvGetFlag(mpv, (pfx + "/forced").c_str());

        std::string base = !title.empty() ? title
                         : (!lang.empty() ? lang
                         : ((isSub ? "Subtitle " : "Track ") + std::to_string(logicalIndex + 1)));
        std::string extra;
        auto appendDetail = [&](const std::string &d) {
            if (d.empty() || d == "unknown") return;
            if (base.find(d) != std::string::npos) return;
            if (!extra.empty()) extra += ", ";
            extra += d;
        };
        if (isAudio) appendDetail(channels);
        appendDetail(codec);
        std::string label = extra.empty() ? base : base + " (" + extra + ")";

        if (!first) out += ",";
        first = false;
        out += "{\"index\":" + std::to_string(logicalIndex)
             + ",\"id\":\"" + std::to_string(id) + "\""
             + ",\"label\":\"" + jsonEscape(label) + "\""
             + ",\"language\":\"" + jsonEscape(lang) + "\""
             + ",\"selected\":" + (selected ? "true" : "false")
             + ",\"forced\":" + (forced ? "true" : "false")
             + "}";
        logicalIndex++;
    }
    out += "]";
    return out;
}

gboolean pushPlayerUpdate(gpointer data) {
    auto *player = static_cast<Player *>(data);
    if (!playerAlive(player)) return G_SOURCE_REMOVE;
    if (!player->webview || !player->mpv) return G_SOURCE_CONTINUE;
    // Keep the (redirected, invisible) overlay window topmost so pointer/click
    // events reach it instead of mpv's video window below. Redirection keeps it
    // hidden from the screen regardless of stacking; raising only affects input.
    if (player->gtkWindow) {
        GdkWindow *ov = gtk_widget_get_window(player->gtkWindow);
        if (ov) XRaiseWindow(GDK_WINDOW_XDISPLAY(ov), player->overlayXid);
    }
    double duration = mpvGetDouble(player->mpv, "duration");
    double position = mpvGetDouble(player->mpv, "time-pos");
    bool paused = mpvGetFlag(player->mpv, "pause");
    bool loading = playerLoading(player);
    std::string audioTracks = buildTracksJson(player->mpv, "audio");
    std::string subtitleTracks = buildTracksJson(player->mpv, "sub");
    char head[192];
    snprintf(head, sizeof(head),
             "window.playerUpdate&&window.playerUpdate({duration:%0.3f,position:%0.3f,paused:%s,loading:%s,audioTracks:",
             duration, position, paused ? "true" : "false", loading ? "true" : "false");
    std::string js = std::string(head) + audioTracks +
                     ",subtitleTracks:" + subtitleTracks + "})";
    evalJs(player->webview, js);
    return G_SOURCE_CONTINUE;
}

struct WebviewSetup {
    Player *player;
    Window hostXid;
    std::string url;
};

// Surface controls-page load progress (debug only) so a blank/erroring page is
// diagnosable; failures always log via onLoadFailed.
void onLoadChanged(WebKitWebView * /*wv*/, WebKitLoadEvent event, gpointer /*data*/) {
    const char *name = event == WEBKIT_LOAD_STARTED ? "started"
                     : event == WEBKIT_LOAD_REDIRECTED ? "redirected"
                     : event == WEBKIT_LOAD_COMMITTED ? "committed"
                     : event == WEBKIT_LOAD_FINISHED ? "finished"
                     : "unknown";
    NUVIO_LOG("webview load-changed: %s", name);
}

gboolean onLoadFailed(WebKitWebView * /*wv*/, WebKitLoadEvent /*event*/,
                      gchar *uri, GError *error, gpointer /*data*/) {
    NUVIO_ERR("webview load-FAILED uri=%s error=%s", uri ? uri : "(null)",
              error ? error->message : "(null)");
    return FALSE;
}

// Runs on the GTK thread: build a transparent WebKitGTK window, reparent it
// as a child of the host AWT/X11 window (over the mpv video), load the
// controls page, and start the state-push timer.
gboolean createWebviewOnGtk(gpointer data) {
    auto *s = static_cast<WebviewSetup *>(data);
    Player *player = s->player;

    GtkWidget *win = gtk_window_new(GTK_WINDOW_TOPLEVEL);
    gtk_window_set_decorated(GTK_WINDOW(win), FALSE);
    gtk_widget_set_app_paintable(win, TRUE);
    GdkScreen *screen = gtk_widget_get_screen(win);
    GdkVisual *rgba = gdk_screen_get_rgba_visual(screen);
    if (rgba) gtk_widget_set_visual(win, rgba);

    WebKitUserContentManager *ucm = webkit_user_content_manager_new();
    webkit_user_content_manager_register_script_message_handler(ucm, "player");
    g_signal_connect(ucm, "script-message-received::player",
                     G_CALLBACK(onPlayerMessage), player);

    // Ephemeral (in-memory) web context: the controls page is a trusted local
    // file:// UI that needs no cookies/cache/IndexedDB. Using the default context
    // creates a disk-backed WebKitWebsiteDataStore whose finalize() races the
    // detached gtk_main thread at process exit -> SIGABRT in
    // webkit_web_context_finalize on quit. Ephemeral has no persistent store.
    WebKitWebContext *webContext = webkit_web_context_new_ephemeral();
    WebKitWebView *wv = WEBKIT_WEB_VIEW(g_object_new(
        WEBKIT_TYPE_WEB_VIEW,
        "web-context", webContext,
        "user-content-manager", ucm,
        nullptr));
    g_object_unref(webContext);  // wv holds its own ref
    GdkRGBA transparent = {0.0, 0.0, 0.0, 0.0};
    webkit_web_view_set_background_color(wv, &transparent);

    // The controls page is loaded from file:// and its JS pulls sibling assets
    // (js/css/fonts) plus talks to native; without file-access + console piping a
    // JS failure is silent. Mirror the capabilities the macOS/Windows webviews grant.
    WebKitSettings *settings = webkit_web_view_get_settings(wv);
    webkit_settings_set_enable_write_console_messages_to_stdout(settings, TRUE);
    webkit_settings_set_allow_file_access_from_file_urls(settings, TRUE);
    webkit_settings_set_allow_universal_access_from_file_urls(settings, TRUE);
    webkit_settings_set_enable_developer_extras(settings, TRUE);
    webkit_settings_set_javascript_can_access_clipboard(settings, TRUE);

    g_signal_connect(wv, "load-changed", G_CALLBACK(onLoadChanged), nullptr);
    g_signal_connect(wv, "load-failed", G_CALLBACK(onLoadFailed), nullptr);

    gtk_container_add(GTK_CONTAINER(win), GTK_WIDGET(wv));

    // Make sure the overlay actually asks the X server for pointer/keyboard
    // events; without an explicit mask WebKit gets no DOM pointer events once the
    // window is a child of a foreign (AWT) parent.
    gtk_widget_add_events(win,
                          GDK_POINTER_MOTION_MASK | GDK_BUTTON_PRESS_MASK |
                          GDK_BUTTON_RELEASE_MASK | GDK_SCROLL_MASK |
                          GDK_ENTER_NOTIFY_MASK | GDK_LEAVE_NOTIFY_MASK |
                          GDK_KEY_PRESS_MASK | GDK_KEY_RELEASE_MASK);
    gtk_widget_realize(win);
    GdkWindow *gdkWin = gtk_widget_get_window(win);
    Display *dpy = GDK_WINDOW_XDISPLAY(gdkWin);
    Window gtkXid = GDK_WINDOW_XID(gdkWin);

    // size to the host window
    XWindowAttributes attrs;
    if (XGetWindowAttributes(dpy, s->hostXid, &attrs)) {
        gtk_window_resize(GTK_WINDOW(win), attrs.width, attrs.height);
    }

    // Reparent THROUGH GDK (not raw XReparentWindow): GDK must know the window is
    // now a child of the host, otherwise it never dispatches pointer events to it
    // and the controls page receives no mousemove -> chrome never shows.
    GdkDisplay *gdkDisplay = gdk_window_get_display(gdkWin);
    GdkWindow *hostGdk = gdk_x11_window_foreign_new_for_display(gdkDisplay, s->hostXid);
    if (hostGdk) {
        gdk_window_reparent(gdkWin, hostGdk, 0, 0);
    } else {
        NUVIO_ERR("foreign host GdkWindow wrap failed; falling back to XReparentWindow");
        XReparentWindow(dpy, gtkXid, s->hostXid, 0, 0);
    }
    gtk_widget_show_all(win);
    gdk_window_raise(gdkWin);

    // FEASIBILITY TEST: redirect the overlay window offscreen via the Composite
    // extension. If this hides it from the screen (revealing the video below)
    // while it keeps rendering + receiving input, we can read its pixmap and
    // blend it over the video via mpv overlay-add (no window-stacking blend).
    int compEventBase = 0, compErrorBase = 0;
    if (XCompositeQueryExtension(dpy, &compEventBase, &compErrorBase)) {
        int major = 0, minor = 0;
        XCompositeQueryVersion(dpy, &major, &minor);
        XCompositeRedirectWindow(dpy, gtkXid, CompositeRedirectManual);
        player->overlayXid = gtkXid;
        NUVIO_LOG("XComposite %d.%d present; redirected overlay 0x%lx (manual)",
                  major, minor, gtkXid);
    } else {
        NUVIO_ERR("XComposite NOT available");
    }
    XFlush(dpy);

    webkit_web_view_load_uri(wv, s->url.c_str());

    player->gtkWindow = win;
    player->webview = wv;
    player->hostXid = s->hostXid;
    player->updateTimer = g_timeout_add(200, pushPlayerUpdate, player);
    player->compositeTimer = g_timeout_add(33, compositeTick, player);  // ~30fps when active

    NUVIO_LOG("webview created + reparented into host 0x%lx", s->hostXid);
    delete s;
    return G_SOURCE_REMOVE;
}

struct ControlsUpdate {
    WebKitWebView *webview;
    std::string json;
};

// native -> JS: push a fresh controls state (identical call to WKWebView).
gboolean applyControlsOnGtk(gpointer data) {
    auto *u = static_cast<ControlsUpdate *>(data);
    std::string script = "window.playerControls&&window.playerControls(JSON.parse(" + jsLiteral(u->json) + "))";
    evalJs(u->webview, script);
    delete u;
    return G_SOURCE_REMOVE;
}

// Tear the webview down on the GTK thread (owns all GTK/WebKit state).
gboolean destroyWebviewOnGtk(gpointer data) {
    auto *player = static_cast<Player *>(data);
    if (player->updateTimer) {
        g_source_remove(player->updateTimer);
        player->updateTimer = 0;
    }
    if (player->compositeTimer) {
        g_source_remove(player->compositeTimer);
        player->compositeTimer = 0;
    }
    if (player->gtkWindow) {
        releaseSnapshots(player);
        gtk_widget_destroy(player->gtkWindow);
        player->gtkWindow = nullptr;
        player->webview = nullptr;
        player->overlayXid = 0;
    }
    return G_SOURCE_REMOVE;
}

// Drains the mpv event queue so the core keeps running and tracks EOF.
void runEventLoop(Player *player) {
    while (player->running.load()) {
        mpv_event *event = mpv_wait_event(player->mpv, 0.05);
        if (!event || event->event_id == MPV_EVENT_NONE) continue;
        switch (event->event_id) {
            case MPV_EVENT_LOG_MESSAGE: {
                auto *msg = static_cast<mpv_event_log_message *>(event->data);
                if (msg) NUVIO_LOG("mpv[%s] %s: %s", msg->level, msg->prefix, msg->text);
                break;
            }
            case MPV_EVENT_END_FILE: {
                auto *end = static_cast<mpv_event_end_file *>(event->data);
                if (end && end->reason == MPV_END_FILE_REASON_EOF) {
                    player->ended.store(true);
                }
                break;
            }
            case MPV_EVENT_START_FILE:
                player->ended.store(false);
                player->firstFrameShown.store(false);  // re-show loading for the new file
                break;
            case MPV_EVENT_PLAYBACK_RESTART:
                player->firstFrameShown.store(true);
                break;
            case MPV_EVENT_SHUTDOWN:
                player->running.store(false);
                break;
            default:
                break;
        }
    }
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    gVm = vm;
    // On process exit the detached GTK thread keeps running gtk_main and firing
    // timers while libraries unload; flag shutdown so those callbacks bail.
    std::atexit([] { gShuttingDown.store(true); });
    return JNI_VERSION_1_6;
}

extern "C" {

#define NP(name) \
    Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_##name

JNIEXPORT jlong JNICALL NP(create)(
    JNIEnv *env, jobject /*thiz*/, jlong hostViewPtr, jstring sourceUrl,
    jobjectArray headerLines, jboolean playWhenReady, jlong initialPositionMs,
    jstring controlsPageUrl, jint decoderPriority,
    jboolean /*nvidiaRtxSuperResolutionEnabled*/, jobject eventSink) {

    // libmpv requires LC_NUMERIC=C (e.g. non-"C" locales with comma
    // decimals make mpv_create fail); the JVM uses java.util.Locale, so
    // this C-level change does not affect Java number formatting.
    setlocale(LC_NUMERIC, "C");

    auto *player = new Player();
    player->mpv = mpv_create();
    if (!player->mpv) {
        NUVIO_ERR("mpv_create() returned NULL");
        delete player;
        return 0;
    }
    // Config shared by both init attempts (see below).
    std::string wid;
    if (hostViewPtr != 0) {
        wid = std::to_string(static_cast<int64_t>(hostViewPtr));
        NUVIO_LOG("embedding into X11 wid=%s", wid.c_str());
    } else {
        NUVIO_ERR("hostViewPtr is 0 — no window to embed into");
    }

    // Forward addon/debrid HTTP headers verbatim.
    std::string headerFields;
    if (headerLines != nullptr) {
        jsize count = env->GetArrayLength(headerLines);
        std::vector<std::string> headers;
        headers.reserve(count);
        for (jsize i = 0; i < count; ++i) {
            auto line = static_cast<jstring>(env->GetObjectArrayElement(headerLines, i));
            headers.push_back(jstringToUtf8(env, line));
            if (line) env->DeleteLocalRef(line);
        }
        if (!headers.empty()) headerFields = joinHeaderFields(headers);
    }

    auto configure = [&](mpv_handle *m, const char *gpuCtx, const char *hwdec) {
        // Surface mpv's own diagnostics (drained by the event thread).
        mpv_request_log_messages(m, nuvioDebug() ? "v" : "no");
        // Embed into the host AWT Canvas's X11 window.
        if (!wid.empty()) mpv_set_option_string(m, "wid", wid.c_str());

        // Nuvio renders its own controls; keep mpv silent and non-interactive.
        mpv_set_option_string(m, "osc", "no");
        mpv_set_option_string(m, "osd-level", "0");
        mpv_set_option_string(m, "input-default-bindings", "no");
        mpv_set_option_string(m, "input-vo-keyboard", "no");
        mpv_set_option_string(m, "input-cursor", "no");
        mpv_set_option_string(m, "cursor-autohide", "no");
        mpv_set_option_string(m, "keep-open", "yes");
        mpv_set_option_string(m, "idle", "yes");
        mpv_set_option_string(m, "vo", "gpu");
        // Bring the VO/OSD up immediately (before the first decoded frame) so the
        // controls overlay — including the loading screen — can render via
        // overlay-add while a slow/non-faststart file is still opening, instead of
        // a black gap. (This also makes mpv_initialize itself surface GPU-context
        // failures, which the attempt loop below relies on.)
        mpv_set_option_string(m, "force-window", "immediate");
        // Force an X11 GPU context so mpv embeds into the host window's X11
        // "wid". Under a Wayland session mpv would otherwise pick its native
        // Wayland backend, which cannot embed into a foreign surface and opens
        // a separate window instead. The host AWT window is X11 (XWayland), so
        // X11 embedding composites correctly inside the Nuvio window.
        mpv_set_option_string(m, "gpu-context", gpuCtx);
        mpv_set_option_string(m, "force-seekable", "yes");

        // Decoder config mirrors the macOS bridge for parity (mac: hwdec=auto +
        // gpu-hwdec-interop=auto + decoderPriority handling). gpu-hwdec-interop=auto
        // lets vo=gpu use direct hardware decode instead of the slow copy-back path.
        mpv_set_option_string(m, "audio-channels", "auto");
        mpv_set_option_string(m, "hwdec", hwdec);
        mpv_set_option_string(m, "gpu-hwdec-interop", "auto");
        if (decoderPriority == 0) {
            mpv_set_option_string(m, "vd-lavc-software-fallback", "no");
        } else if (decoderPriority == 2) {
            mpv_set_option_string(m, "hwdec", "no");
            mpv_set_option_string(m, "vd-lavc-software-fallback", "yes");
        } else {
            mpv_set_option_string(m, "vd-lavc-software-fallback", "yes");
        }
        mpv_set_option_string(m, "vd-lavc-threads", "0");
        mpv_set_option_string(m, "target-colorspace-hint", "yes");
        mpv_set_option_string(m, "target-colorspace-hint-mode", "source");

        if (!headerFields.empty()) {
            mpv_set_option_string(m, "http-header-fields", headerFields.c_str());
        }
        if (initialPositionMs > 0) {
            std::string start = std::to_string(initialPositionMs / 1000.0);
            mpv_set_option_string(m, "start", start.c_str());
        }
        if (!playWhenReady) {
            mpv_set_option_string(m, "pause", "yes");
        }
    };

    // Attempt 1: x11egl — the proven path on Mesa (Intel/AMD). Attempt 2: x11vk —
    // NVIDIA's proprietary EGL refuses to make a context current on the foreign
    // AWT window (mpv_initialize fails via force-window=immediate), while Vulkan
    // embeds fine there; its native hwdec interop corrupts frames on NVIDIA, so
    // the retry pairs it with copy-back NVDEC (harmless elsewhere: unavailable
    // hwdec just falls back to software). NUVIO_MPV_GPU_CONTEXT / NUVIO_MPV_HWDEC
    // env overrides pin a single attempt for testing.
    const char *gpuCtxEnv = getenv("NUVIO_MPV_GPU_CONTEXT");
    const char *hwdecEnv = getenv("NUVIO_MPV_HWDEC");
    bool ctxPinned = gpuCtxEnv && *gpuCtxEnv;
    struct Attempt { const char *ctx; const char *hwdec; };
    Attempt attempts[2] = {
        {ctxPinned ? gpuCtxEnv : "x11egl", (hwdecEnv && *hwdecEnv) ? hwdecEnv : "auto"},
        {"x11vk", (hwdecEnv && *hwdecEnv) ? hwdecEnv : "nvdec-copy"},
    };
    int nAttempts = ctxPinned ? 1 : 2;
    int initResult = MPV_ERROR_GENERIC;
    for (int a = 0; a < nAttempts; ++a) {
        if (!player->mpv) player->mpv = mpv_create();
        if (!player->mpv) {
            NUVIO_ERR("mpv_create() returned NULL on retry");
            break;
        }
        configure(player->mpv, attempts[a].ctx, attempts[a].hwdec);
        initResult = mpv_initialize(player->mpv);
        if (initResult >= 0) {
            if (a > 0) {
                NUVIO_ERR("gpu-context %s failed to initialize; using %s instead",
                          attempts[0].ctx, attempts[a].ctx);
                // The x11egl failure doubles as NVIDIA detection: the same
                // driver's WebKit DMABUF renderer fails GBM buffer creation,
                // yielding stale/opaque controls snapshots. Disable it before
                // the controls webview (below) spawns WebKit's processes.
                // overwrite=0 keeps an explicit user setting authoritative.
                setenv("WEBKIT_DISABLE_DMABUF_RENDERER", "1", 0);
            }
            break;
        }
        NUVIO_ERR("mpv_initialize failed (gpu-context=%s): %s", attempts[a].ctx,
                  mpv_error_string(initResult));
        // Drain any queued log messages explaining the failure.
        for (int i = 0; i < 50; ++i) {
            mpv_event *ev = mpv_wait_event(player->mpv, 0.0);
            if (!ev || ev->event_id == MPV_EVENT_NONE) break;
            if (ev->event_id == MPV_EVENT_LOG_MESSAGE) {
                auto *m = static_cast<mpv_event_log_message *>(ev->data);
                if (m) NUVIO_LOG("mpv[%s] %s: %s", m->level, m->prefix, m->text);
            }
        }
        mpv_destroy(player->mpv);
        player->mpv = nullptr;
    }
    if (!player->mpv || initResult < 0) {
        delete player;
        return 0;
    }
    NUVIO_LOG("mpv initialized OK");

    if (eventSink != nullptr) {
        player->eventSink = env->NewGlobalRef(eventSink);
        jclass sinkClass = env->GetObjectClass(eventSink);
        player->eventMethod = env->GetMethodID(sinkClass, "onPlayerEvent", "(Ljava/lang/String;D)V");
        env->DeleteLocalRef(sinkClass);
    }

    // Register as live before starting the event thread / overlay timers so their
    // callbacks can validate the pointer (see playerAlive).
    {
        std::lock_guard<std::mutex> lk(gLiveMutex);
        gLivePlayers.insert(player);
    }

    player->running.store(true);
    player->eventThread = std::thread(runEventLoop, player);

    std::string url = jstringToUtf8(env, sourceUrl);
    const char *cmd[] = {"loadfile", url.c_str(), nullptr};
    mpv_command(player->mpv, cmd);

    // Bring up the WebKitGTK controls overlay now so it can render the loading
    // screen (poster + title + spinner) over mpv's black frame while the stream
    // buffers, matching macOS / Windows.
    if (hostViewPtr != 0) {
        ensureGtk();
        auto *setup = new WebviewSetup{player, static_cast<Window>(hostViewPtr),
                                       jstringToUtf8(env, controlsPageUrl)};
        g_main_context_invoke(nullptr, createWebviewOnGtk, setup);
    }

    return reinterpret_cast<jlong>(player);
}

JNIEXPORT void JNICALL NP(dispose)(JNIEnv *env, jobject, jlong handle) {
    Player *player = asPlayer(handle);
    if (!player) return;
    // Mark not-live first so any in-flight GTK timer bails before we free it.
    {
        std::lock_guard<std::mutex> lk(gLiveMutex);
        gLivePlayers.erase(player);
    }
    // Tear the overlay down on the GTK thread before freeing the player.
    if (player->gtkWindow) {
        gtkSync([player] { destroyWebviewOnGtk(player); });
    }
    player->running.store(false);
    if (player->mpv) mpv_wakeup(player->mpv);
    if (player->eventThread.joinable()) player->eventThread.join();
    if (player->eventSink) env->DeleteGlobalRef(player->eventSink);
    if (player->mpv) mpv_terminate_destroy(player->mpv);
    delete player;
}

JNIEXPORT void JNICALL NP(setPaused)(JNIEnv *, jobject, jlong handle, jboolean paused) {
    Player *p = asPlayer(handle);
    if (p) mpvSetFlag(p->mpv, "pause", paused == JNI_TRUE);
}

JNIEXPORT void JNICALL NP(seekTo)(JNIEnv *, jobject, jlong handle, jlong positionMs) {
    Player *p = asPlayer(handle);
    if (!p) return;
    std::string target = std::to_string(positionMs / 1000.0);
    const char *cmd[] = {"seek", target.c_str(), "absolute", nullptr};
    mpv_command(p->mpv, cmd);
    p->ended.store(false);
}

JNIEXPORT void JNICALL NP(seekBy)(JNIEnv *, jobject, jlong handle, jlong offsetMs) {
    Player *p = asPlayer(handle);
    if (!p) return;
    std::string delta = std::to_string(offsetMs / 1000.0);
    const char *cmd[] = {"seek", delta.c_str(), "relative", nullptr};
    mpv_command(p->mpv, cmd);
    p->ended.store(false);
}

JNIEXPORT void JNICALL NP(setSpeed)(JNIEnv *, jobject, jlong handle, jfloat speed) {
    Player *p = asPlayer(handle);
    if (p) mpvSetDouble(p->mpv, "speed", speed);
}

JNIEXPORT void JNICALL NP(setVolume)(JNIEnv *, jobject, jlong handle, jfloat level) {
    Player *p = asPlayer(handle);
    if (p) mpvSetDouble(p->mpv, "volume", level * 100.0); // Kotlin 0..1 -> mpv 0..100
}

JNIEXPORT void JNICALL NP(adjustVolume)(JNIEnv *, jobject, jlong handle, jfloat delta) {
    Player *p = asPlayer(handle);
    if (!p) return;
    double current = mpvGetDouble(p->mpv, "volume");
    double next = current + delta * 100.0;
    if (next < 0) next = 0;
    if (next > 100) next = 100;
    mpvSetDouble(p->mpv, "volume", next);
}

JNIEXPORT jfloat JNICALL NP(volume)(JNIEnv *, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return 0.0f;
    return static_cast<jfloat>(mpvGetDouble(p->mpv, "volume") / 100.0);
}

JNIEXPORT jlong JNICALL NP(durationMs)(JNIEnv *, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return 0;
    return static_cast<jlong>(mpvGetDouble(p->mpv, "duration") * 1000.0);
}

JNIEXPORT jlong JNICALL NP(positionMs)(JNIEnv *, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return 0;
    return static_cast<jlong>(mpvGetDouble(p->mpv, "time-pos") * 1000.0);
}

JNIEXPORT jlong JNICALL NP(bufferedPositionMs)(JNIEnv *, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return 0;
    double pos = mpvGetDouble(p->mpv, "time-pos");
    double cache = mpvGetDouble(p->mpv, "demuxer-cache-time");
    double buffered = cache > pos ? cache : pos;
    return static_cast<jlong>(buffered * 1000.0);
}

JNIEXPORT jboolean JNICALL NP(isLoading)(JNIEnv *, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return JNI_TRUE;
    return playerLoading(p) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL NP(isEnded)(JNIEnv *, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return JNI_FALSE;
    // keep-open=yes makes mpv PAUSE at EOF instead of unloading, so
    // MPV_EVENT_END_FILE never fires — the `eof-reached` property is what flips.
    // Mirror the macOS bridge (rawIsEnded reads eof-reached) so Nuvio's
    // next-episode / autoplay logic actually triggers at the end of a file.
    return (mpvGetFlag(p->mpv, "eof-reached") || p->ended.load()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL NP(isPaused)(JNIEnv *, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return JNI_FALSE;
    return mpvGetFlag(p->mpv, "pause") ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL NP(speed)(JNIEnv *, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return 1.0f;
    return static_cast<jfloat>(mpvGetDouble(p->mpv, "speed"));
}

JNIEXPORT void JNICALL NP(setResizeMode)(JNIEnv *, jobject, jlong handle, jint mode) {
    Player *p = asPlayer(handle);
    if (!p) return;
    // 0 fit, 1 fill/zoom, 2 fixed-width, 3 stretch (best-effort mpv mapping)
    switch (mode) {
        case 3: mpv_set_option_string(p->mpv, "keepaspect", "no"); break;
        case 1: mpv_set_option_string(p->mpv, "keepaspect", "yes");
                mpv_set_option_string(p->mpv, "panscan", "1.0"); break;
        default: mpv_set_option_string(p->mpv, "keepaspect", "yes");
                 mpv_set_option_string(p->mpv, "panscan", "0.0"); break;
    }
}

// ---- tracks & subtitles (mpv properties) --------------------------------

JNIEXPORT jstring JNICALL NP(audioTracksJson)(JNIEnv *env, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return utf8ToJstring(env, "[]");
    return utf8ToJstring(env, buildTracksJson(p->mpv, "audio"));
}

JNIEXPORT jstring JNICALL NP(subtitleTracksJson)(JNIEnv *env, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return utf8ToJstring(env, "[]");
    return utf8ToJstring(env, buildTracksJson(p->mpv, "sub"));
}

JNIEXPORT void JNICALL NP(selectAudioTrack)(JNIEnv *, jobject, jlong handle, jint trackId) {
    Player *p = asPlayer(handle);
    if (!p) return;
    int64_t id = trackId;
    if (trackId < 0) mpv_set_property_string(p->mpv, "aid", "no");
    else mpv_set_property(p->mpv, "aid", MPV_FORMAT_INT64, &id);
}

JNIEXPORT void JNICALL NP(selectSubtitleTrack)(JNIEnv *, jobject, jlong handle, jint trackId) {
    Player *p = asPlayer(handle);
    if (!p) return;
    int64_t id = trackId;
    if (trackId < 0) mpv_set_property_string(p->mpv, "sid", "no");
    else mpv_set_property(p->mpv, "sid", MPV_FORMAT_INT64, &id);
}

JNIEXPORT void JNICALL NP(addSubtitleUrl)(JNIEnv *env, jobject, jlong handle, jstring url) {
    Player *p = asPlayer(handle);
    if (!p) return;
    std::string sub = jstringToUtf8(env, url);
    const char *cmd[] = {"sub-add", sub.c_str(), "select", nullptr};
    mpv_command(p->mpv, cmd);
}

JNIEXPORT void JNICALL NP(clearExternalSubtitles)(JNIEnv *, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return;
    const char *cmd[] = {"sub-remove", nullptr};
    mpv_command(p->mpv, cmd);
}

JNIEXPORT void JNICALL NP(clearExternalSubtitlesAndSelect)(JNIEnv *, jobject, jlong handle, jint trackId) {
    Player *p = asPlayer(handle);
    if (!p) return;
    const char *cmd[] = {"sub-remove", nullptr};
    mpv_command(p->mpv, cmd);
    int64_t id = trackId;
    if (trackId < 0) mpv_set_property_string(p->mpv, "sid", "no");
    else mpv_set_property(p->mpv, "sid", MPV_FORMAT_INT64, &id);
}

JNIEXPORT void JNICALL NP(setSubtitleDelayMs)(JNIEnv *, jobject, jlong handle, jint delayMs) {
    Player *p = asPlayer(handle);
    if (p) mpvSetDouble(p->mpv, "sub-delay", delayMs / 1000.0);
}

JNIEXPORT void JNICALL NP(applySubtitleStyle)(
    JNIEnv *env, jobject, jlong handle, jstring textColor, jstring /*backgroundColor*/,
    jstring outlineColor, jfloat outlineSize, jboolean bold, jfloat fontSize, jint subPos) {
    Player *p = asPlayer(handle);
    if (!p) return;
    mpv_set_property_string(p->mpv, "sub-color", jstringToUtf8(env, textColor).c_str());
    mpv_set_property_string(p->mpv, "sub-border-color", jstringToUtf8(env, outlineColor).c_str());
    std::string border = std::to_string(outlineSize);
    mpv_set_property_string(p->mpv, "sub-border-size", border.c_str());
    mpv_set_property_string(p->mpv, "sub-bold", bold == JNI_TRUE ? "yes" : "no");
    std::string size = std::to_string(fontSize);
    mpv_set_property_string(p->mpv, "sub-font-size", size.c_str());
    std::string pos = std::to_string(subPos);
    mpv_set_property_string(p->mpv, "sub-pos", pos.c_str());
}

// ---- Phase 2 stubs: webview controls / window chrome / focus ------------

JNIEXPORT void JNICALL NP(updateControls)(JNIEnv *env, jobject, jlong handle, jstring controlsJson) {
    Player *p = asPlayer(handle);
    if (!p || !p->webview) return;
    auto *u = new ControlsUpdate{p->webview, jstringToUtf8(env, controlsJson)};
    g_main_context_invoke(nullptr, applyControlsOnGtk, u);
}
JNIEXPORT void JNICALL NP(requestFocus)(JNIEnv *, jobject, jlong) {}
JNIEXPORT void JNICALL NP(applyWindowChrome)(JNIEnv *, jobject, jlong, jboolean, jint, jint, jint) {}
JNIEXPORT void JNICALL NP(setWindowBorderlessFullscreen)(
    JNIEnv *, jobject, jlong, jboolean, jint, jint, jint, jint) {}
JNIEXPORT jboolean JNICALL NP(warmupWebView2)(JNIEnv *, jobject, jstring) { return JNI_FALSE; }
JNIEXPORT void JNICALL NP(shutdownWebView2Warmup)(JNIEnv *, jobject) {}
JNIEXPORT jboolean JNICALL NP(setWindowsDisplaySleepInhibited)(JNIEnv *, jobject, jboolean) {
    return JNI_FALSE;
}

#undef NP
} // extern "C"
