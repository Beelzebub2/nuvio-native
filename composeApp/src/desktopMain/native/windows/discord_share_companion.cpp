// Starlit - Discord Share: separate-process, audio-only Windows companion.
//
// Build (from a VS x64 Developer Command Prompt):
//   cl /nologo /std:c++17 /EHsc /W4 /MT /utf-8 discord_share_companion.cpp /link /SUBSYSTEM:WINDOWS user32.lib gdi32.lib dwmapi.lib
//
// Put discord_share_companion.exe beside libmpv-2.dll and its runtime DLLs.
// Start with optional --source-hwnd=0xHEX; the parent also sends/validates HWND
// in the first packet. All sensitive strings travel only over inherited stdin.
//
// Wire protocol is defined by discord_share_protocol.h (little-endian):
//   u32 payload length (<= 1 MiB), then payload:
//   'I' + u32 version=1 + u64 source HWND + text(URL) + text(audio-device)
//       + u32 headerCount + text(header line) repeated headerCount times
//   'S' + i64 positionMs + f64 speed + f64 volume (0..1) + i64 mpv aid
//       + u8 paused (0/1)
//   'Q' (no other bytes); closing stdin also terminates the helper.
//   text = u32 byteLength + the exact UTF-8 bytes, without a terminator.
// The source HWND must be a top-level window. Starlit starts this process with
// SW_HIDE for screen sharing; its hidden HWND still handles pipe messages and
// timers. In the optional visible mode it mirrors the source via DWM.
// libmpv decodes AUDIO ONLY to an explicit, separate WASAPI device; the local
// player's original audio output is never changed.

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <dwmapi.h>

#include "discord_share_protocol.h"
#include "discord_share_safety.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <clocale>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cwchar>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#pragma comment(lib, "user32.lib")
#pragma comment(lib, "gdi32.lib")
#pragma comment(lib, "dwmapi.lib")

static_assert(sizeof(void *) == 8, "The Discord companion requires an x64 Windows build.");
static_assert(sizeof(double) == 8, "The pipe uses 64-bit IEEE-754 doubles.");

namespace {

constexpr wchar_t kWindowClass[] = L"StarlitDiscordShareCompanion";
constexpr wchar_t kWindowTitle[] = L"Starlit - Discord Share";
constexpr UINT kPacketMessage = WM_APP + 42;
constexpr UINT_PTR kSyncTimer = 1;
constexpr DWORD kSyncTimerMs = 100;
constexpr ULONGLONG kMissingSyncMs = 3000;
constexpr ULONGLONG kProtocolErrorGraceMs = 5000;
constexpr double kSeekThresholdSeconds = 0.40;
constexpr int kMpvFormatFlag = 3;
constexpr int kMpvFormatString = 1;
constexpr int kMpvFormatInt64 = 4;
constexpr int kMpvFormatDouble = 5;

std::wstring utf8ToWide(const std::string &input) {
    if (input.empty()) return {};
    int chars = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, input.data(),
                                    static_cast<int>(input.size()), nullptr, 0);
    if (chars <= 0) return L"Unrecognized media engine error.";
    std::wstring result(static_cast<size_t>(chars), L'\0');
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, input.data(),
                        static_cast<int>(input.size()), result.data(), chars);
    return result;
}

std::wstring hexError(HRESULT value) {
    wchar_t text[20] = {};
    std::swprintf(text, sizeof(text) / sizeof(text[0]), L"0x%08lX",
                  static_cast<unsigned long>(value));
    return text;
}

std::wstring executableFolder() {
    std::vector<wchar_t> path(MAX_PATH);
    while (true) {
        DWORD length = GetModuleFileNameW(nullptr, path.data(), static_cast<DWORD>(path.size()));
        if (length == 0) throw std::runtime_error("Unable to locate the companion executable.");
        if (length < path.size() - 1) {
            std::wstring value(path.data(), length);
            auto lastSlash = value.find_last_of(L"\\/");
            if (lastSlash == std::wstring::npos) {
                throw std::runtime_error("Companion executable has no directory.");
            }
            return value.substr(0, lastSlash);
        }
        if (path.size() >= 32768) throw std::runtime_error("Companion path is too long.");
        path.resize(path.size() * 2);
    }
}

bool containsUnsafeText(const std::string &text) {
    return text.find('\0') != std::string::npos ||
           text.find('\r') != std::string::npos ||
           text.find('\n') != std::string::npos;
}

class Bytes {
public:
    Bytes(const uint8_t *bytes, size_t length) : bytes_(bytes), length_(length) {}

    bool u8(uint8_t &value) {
        if (remaining() < 1) return false;
        value = bytes_[offset_++];
        return true;
    }

    bool u32(uint32_t &value) {
        if (remaining() < 4) return false;
        value = 0;
        for (unsigned index = 0; index < 4; ++index) {
            value |= uint32_t(bytes_[offset_++]) << (index * 8);
        }
        return true;
    }

    bool u64(uint64_t &value) {
        if (remaining() < 8) return false;
        value = 0;
        for (unsigned index = 0; index < 8; ++index) {
            value |= uint64_t(bytes_[offset_++]) << (index * 8);
        }
        return true;
    }

    bool i64(int64_t &value) {
        uint64_t bits = 0;
        if (!u64(bits)) return false;
        std::memcpy(&value, &bits, sizeof(value));
        return true;
    }

    bool f64(double &value) {
        uint64_t bits = 0;
        if (!u64(bits)) return false;
        std::memcpy(&value, &bits, sizeof(value));
        return true;
    }

    bool text(std::string &value, uint32_t limit) {
        uint32_t size = 0;
        if (!u32(size) || size > limit || size > remaining()) return false;
        value.assign(reinterpret_cast<const char *>(bytes_ + offset_), size);
        offset_ += size;
        return true;
    }

    bool finished() const { return offset_ == length_; }

private:
    size_t remaining() const { return length_ - offset_; }
    const uint8_t *bytes_;
    size_t length_;
    size_t offset_ = 0;
};

struct Initial {
    HWND sourceWindow = nullptr;
    std::string url;
    std::string device;
    std::vector<std::string> headers;
};

struct Sync {
    int64_t positionMs = 0;
    double speed = 1;
    double volume = 1;
    int64_t aid = -1;
    bool paused = true;
};

bool decodeInitial(const std::vector<uint8_t> &payload, Initial &initial) {
    Bytes reader(payload.data() + 1, payload.size() - 1);
    uint32_t version = 0;
    uint64_t window = 0;
    uint32_t headerCount = 0;
    if (!reader.u32(version) || version != discord_share::kProtocolVersion ||
        !reader.u64(window) || window == 0 ||
        window > static_cast<uint64_t>((std::numeric_limits<uintptr_t>::max)()) ||
        !reader.text(initial.url, 256 * 1024) ||
        !reader.text(initial.device, 8 * 1024) ||
        !reader.u32(headerCount) || headerCount > 64) {
        return false;
    }
    initial.sourceWindow = reinterpret_cast<HWND>(static_cast<uintptr_t>(window));
    for (uint32_t index = 0; index < headerCount; ++index) {
        std::string header;
        if (!reader.text(header, 16 * 1024) || containsUnsafeText(header) ||
            header.find(':') == std::string::npos || header.front() == ':') {
            return false;
        }
        initial.headers.push_back(std::move(header));
    }
    return reader.finished() && !initial.url.empty() &&
           !containsUnsafeText(initial.url) && !containsUnsafeText(initial.device);
}

bool decodeSync(const std::vector<uint8_t> &payload, Sync &sync) {
    Bytes reader(payload.data() + 1, payload.size() - 1);
    uint8_t paused = 0;
    if (!reader.i64(sync.positionMs) || !reader.f64(sync.speed) ||
        !reader.f64(sync.volume) || !reader.i64(sync.aid) ||
        !reader.u8(paused) || !reader.finished()) {
        return false;
    }
    if (sync.positionMs < 0 || !std::isfinite(sync.speed) ||
        sync.speed < 0.25 || sync.speed > 4 ||
        !std::isfinite(sync.volume) || sync.volume < 0 || sync.volume > 1 ||
        sync.aid < -2 || paused > 1) {
        return false;
    }
    sync.paused = paused != 0;
    return true;
}

std::string escapedHeaders(const std::vector<std::string> &headers) {
    std::string result;
    for (const auto &header : headers) {
        if (!result.empty()) result.push_back(',');
        for (char letter : header) {
            if (letter == '\\' || letter == ',') result.push_back('\\');
            result.push_back(letter);
        }
    }
    return result;
}

// libmpv ABI declarations kept private so the helper needs no SDK or import lib.
struct mpv_handle;

class AudioPlayer {
public:
    AudioPlayer() = default;
    AudioPlayer(const AudioPlayer &) = delete;
    AudioPlayer &operator=(const AudioPlayer &) = delete;
    ~AudioPlayer() { stop(); }

    void start(const Initial &initial, bool dialogueOnly) {
        try {
            dialogueOnly_ = dialogueOnly;
            const std::wstring path = executableFolder() + L"\\libmpv-2.dll";
            library_ = LoadLibraryExW(path.c_str(), nullptr,
                LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
            if (!library_) {
                throw std::runtime_error("Cannot load libmpv-2.dll from the companion directory.");
            }
            create_ = symbol<decltype(create_)>("mpv_create");
            initialize_ = symbol<decltype(initialize_)>("mpv_initialize");
            setOption_ = symbol<decltype(setOption_)>("mpv_set_option_string");
            setProperty_ = symbol<decltype(setProperty_)>("mpv_set_property");
            setPropertyString_ = symbol<decltype(setPropertyString_)>("mpv_set_property_string");
            getProperty_ = symbol<decltype(getProperty_)>("mpv_get_property");
            freeData_ = symbol<decltype(freeData_)>("mpv_free");
            command_ = symbol<decltype(command_)>("mpv_command");
            terminate_ = symbol<decltype(terminate_)>("mpv_terminate_destroy");
            errorString_ = symbol<decltype(errorString_)>("mpv_error_string");

            // mpv's numeric option parser requires the C decimal separator.
            std::setlocale(LC_NUMERIC, "C");
            mpv_ = create_();
            if (!mpv_) throw std::runtime_error("mpv_create failed.");

            option("config", "no");
            option("osc", "no");
            option("vid", "no");             // Do not start a second video decoder.
            option("vo", "null");
            option("ao", "wasapi");
            // Entire Screen already captures the original FL/FR music. Only
            // supplement the front-centre dialogue channel in hidden mode.
            // auto-safe (unlike stereo) keeps the decoder's native 5.1/7.1
            // layout available to the pan filter before WASAPI stereo output.
            option("audio-channels", dialogueOnly_ ? "auto-safe" : "stereo");
            if (dialogueOnly_) {
                option("ad-lavc-downmix", "no");
                option("af", discord_share::kCentrePanFilter);
            }
            option("audio-device", initial.device.c_str());
            option("volume-max", "200");
            option("volume", "0");           // First SYNC controls the audible level.
            option("pause", "yes");          // Never play before synchronizing.
            option("idle", "yes");
            if (!initial.headers.empty()) {
                const auto headers = escapedHeaders(initial.headers);
                option("http-header-fields", headers.c_str());
            }

            check(initialize_(mpv_), "mpv_initialize");
            const char *load[] = {"loadfile", initial.url.c_str(), "replace", nullptr};
            check(command_(mpv_, load), "mpv loadfile");
        } catch (...) {
            stop();
            throw;
        }
    }

    void stop() {
        if (mpv_) {
            if (terminate_) terminate_(mpv_);
            mpv_ = nullptr;
        }
        if (library_) {
            FreeLibrary(library_);
            library_ = nullptr;
        }
    }

    // Returns false while libmpv is still loading metadata/starting playback.
    bool timePosition(double &seconds) const {
        return mpv_ && getProperty_(mpv_, "time-pos", kMpvFormatDouble, &seconds) >= 0 &&
               std::isfinite(seconds);
    }

    bool hasCentreChannel() const {
        if (!mpv_) return false;
        // Confirm the decoded input layout, not the downmixed output layout.
        char *layout = nullptr;
        const int result = getProperty_(mpv_, "audio-params/channels", kMpvFormatString, &layout);
        if (result < 0 || !layout) return false;
        const std::string channels(layout);
        freeData_(layout);
        int64_t channelCount = 0;
        return getProperty_(mpv_, "audio-params/channel-count", kMpvFormatInt64, &channelCount) >= 0 &&
               discord_share::isKnownCentreLayout(channels, channelCount);
    }

    bool safeToUnmute(int64_t requestedAid) const {
        // The visible window intentionally carries full stereo. Only the hidden
        // centre-only supplement must pass these independent audio checks.
        if (!dialogueOnly_) return true;
        if (!hasCentreChannel()) return false;
        int64_t actualAid = -1;
        int64_t outputChannels = 0;
        if (getProperty_(mpv_, "aid", kMpvFormatInt64, &actualAid) < 0 ||
            getProperty_(mpv_, "audio-out-params/channel-count", kMpvFormatInt64, &outputChannels) < 0) {
            return false;
        }
        char *filterText = nullptr;
        const int filterResult = getProperty_(mpv_, "af", kMpvFormatString, &filterText);
        const std::string filters = filterResult >= 0 && filterText ? filterText : "";
        if (filterText) freeData_(filterText);
        return discord_share::centreOutputIsSafe(true, requestedAid, actualAid, outputChannels, filters);
    }

    void setPaused(bool paused) {
        int value = paused ? 1 : 0;
        check(setProperty_(mpv_, "pause", kMpvFormatFlag, &value), "mpv pause");
    }

    void setSpeed(double speed) {
        check(setProperty_(mpv_, "speed", kMpvFormatDouble, &speed), "mpv speed");
    }

    void setVolume(double normalized) {
        double percent = normalized * 100;
        check(setProperty_(mpv_, "volume", kMpvFormatDouble, &percent), "mpv volume");
    }

    bool setAid(int64_t id) {
        if (id == -1) return setPropertyString_(mpv_, "aid", "auto") >= 0;
        if (id == -2) return setPropertyString_(mpv_, "aid", "no") >= 0;
        return setProperty_(mpv_, "aid", kMpvFormatInt64, &id) >= 0;
    }

    void seek(double seconds) {
        char formatted[64] = {};
        std::snprintf(formatted, sizeof(formatted), "%.3f", seconds);
        const char *args[] = {"seek", formatted, "absolute+exact", nullptr};
        check(command_(mpv_, args), "mpv seek");
    }

private:
    template <typename Symbol>
    Symbol symbol(const char *name) {
        auto *address = GetProcAddress(library_, name);
        if (!address) {
            throw std::runtime_error(std::string("Missing libmpv export: ") + name);
        }
        return reinterpret_cast<Symbol>(address);
    }

    void check(int code, const char *operation) const {
        if (code >= 0) return;
        const char *reason = errorString_ ? errorString_(code) : nullptr;
        throw std::runtime_error(std::string(operation) + ": " +
                                 (reason ? reason : "unknown libmpv error"));
    }

    void option(const char *name, const char *value) {
        check(setOption_(mpv_, name, value), name);
    }

    HMODULE library_ = nullptr;
    bool dialogueOnly_ = false;
    mpv_handle *mpv_ = nullptr;
    mpv_handle *(*create_)() = nullptr;
    int (*initialize_)(mpv_handle *) = nullptr;
    int (*setOption_)(mpv_handle *, const char *, const char *) = nullptr;
    int (*setProperty_)(mpv_handle *, const char *, int, void *) = nullptr;
    int (*setPropertyString_)(mpv_handle *, const char *, const char *) = nullptr;
    int (*getProperty_)(mpv_handle *, const char *, int, void *) = nullptr;
    void (*freeData_)(void *) = nullptr;
    int (*command_)(mpv_handle *, const char **) = nullptr;
    void (*terminate_)(mpv_handle *) = nullptr;
    const char *(*errorString_)(int) = nullptr;
};

struct Packet {
    char type = '\0';
    std::vector<uint8_t> payload;
    std::wstring error;
};

void sendPacket(HWND window, std::unique_ptr<Packet> packet) {
    Packet *raw = packet.release();
    if (!PostMessageW(window, kPacketMessage, 0, reinterpret_cast<LPARAM>(raw))) {
        delete raw;
    }
}

enum class ReadResult { Ok, Eof, Truncated };

ReadResult readExact(HANDLE pipe, uint8_t *buffer, size_t count, bool allowEof) {
    size_t received = 0;
    while (received < count) {
        DWORD size = 0;
        BOOL ok = ReadFile(pipe, buffer + received,
                           static_cast<DWORD>(count - received), &size, nullptr);
        if (!ok || size == 0) {
            return received == 0 && allowEof ? ReadResult::Eof : ReadResult::Truncated;
        }
        received += size;
    }
    return ReadResult::Ok;
}

uint32_t frameLength(const uint8_t *bytes) {
    return uint32_t(bytes[0]) | (uint32_t(bytes[1]) << 8) |
           (uint32_t(bytes[2]) << 16) | (uint32_t(bytes[3]) << 24);
}

void readPipe(HWND window, std::atomic<bool> &closing) {
    auto error = [&](const wchar_t *message) {
        if (closing.load()) return;
        auto packet = std::make_unique<Packet>();
        packet->type = 'E';
        packet->error = message;
        sendPacket(window, std::move(packet));
    };
    HANDLE pipe = GetStdHandle(STD_INPUT_HANDLE);
    if (!pipe || pipe == INVALID_HANDLE_VALUE || GetFileType(pipe) != FILE_TYPE_PIPE) {
        error(L"Stdin must be an inherited anonymous pipe.");
        return;
    }

    while (!closing.load()) {
        uint8_t prefix[4] = {};
        ReadResult status = readExact(pipe, prefix, sizeof(prefix), true);
        if (closing.load()) return;
        if (status == ReadResult::Eof) {
            PostMessageW(window, WM_CLOSE, 0, 0);
            return;
        }
        if (status != ReadResult::Ok) {
            error(L"Stdin closed in the middle of a packet.");
            return;
        }

        const uint32_t length = frameLength(prefix);
        if (length == 0 || length > discord_share::kMaxPacketBytes) {
            error(L"Invalid or oversized Discord companion packet.");
            return;
        }
        std::vector<uint8_t> data(length);
        if (readExact(pipe, data.data(), data.size(), false) != ReadResult::Ok) {
            if (!closing.load()) error(L"Stdin closed in the middle of a packet.");
            return;
        }
        if (closing.load()) return;
        const char type = static_cast<char>(data.front());
        if ((type != 'I' && type != 'S' && type != 'Q') ||
            (type == 'Q' && length != 1)) {
            error(L"Unrecognized Discord companion packet.");
            return;
        }
        auto packet = std::make_unique<Packet>();
        packet->type = type;
        packet->payload = std::move(data);
        sendPacket(window, std::move(packet));
        if (type == 'Q') return;
    }
}

std::uint64_t optionalHwndArgument(const wchar_t *commandLine) {
    std::wstring value = commandLine ? commandLine : L"";
    auto begin = value.find_first_not_of(L" \t\r\n");
    if (begin == std::wstring::npos) return 0;
    value = value.substr(begin);
    constexpr wchar_t prefix[] = L"--source-hwnd=";
    if (value.rfind(prefix, 0) == 0) value.erase(0, sizeof(prefix) / sizeof(wchar_t) - 1);
    wchar_t *end = nullptr;
    unsigned long long hwnd = std::wcstoull(value.c_str(), &end, 0);
    if (end == value.c_str() || hwnd == 0 || *end != L'\0') {
        throw std::runtime_error("Invalid --source-hwnd argument.");
    }
    return static_cast<uint64_t>(hwnd);
}

class Companion {
public:
    Companion(std::uint64_t expectedSource, bool windowVisible)
        : expectedSource_(expectedSource), windowVisible_(windowVisible) {}
    ~Companion() { cleanup(); }

    void setWindow(HWND window) { window_ = window; }
    void tick() {
        if (!error_.empty()) {
            if (fatalExitAt_ && GetTickCount64() >= fatalExitAt_) {
                PostMessageW(window_, WM_CLOSE, 0, 0);
            }
            return;
        }
        if (source_ && !IsWindow(source_)) {
            showError(L"The Starlit source window was closed.");
            return;
        }
        if (thumbnail_) updateThumbnail();
        if (started_ && haveSync_) {
            try {
                applySync();
            } catch (const std::exception &failure) {
                showError(utf8ToWide(failure.what()));
            }
        }
    }

    void handlePacket(const Packet &packet) {
        if (packet.type == 'Q') {
            PostMessageW(window_, WM_CLOSE, 0, 0);
            return;
        }
        if (packet.type == 'E') {
            showError(packet.error, true);
            return;
        }
        if (!error_.empty()) return;
        if (packet.type == 'I') {
            if (started_) {
                showError(L"Duplicate initial packet; restart the companion.");
                return;
            }
            Initial initial;
            if (!decodeInitial(packet.payload, initial)) {
                showError(L"Invalid initial packet or protocol version.", true);
                return;
            }
            if (expectedSource_ && reinterpret_cast<uintptr_t>(initial.sourceWindow) != expectedSource_) {
                showError(L"The source window does not match the launch argument.");
                return;
            }
            if (initial.device.empty() || initial.device == "auto" ||
                initial.device.rfind("wasapi/", 0) != 0) {
                showError(L"An explicit WASAPI audio-device ID is required.");
                return;
            }
            if (!IsWindow(initial.sourceWindow) ||
                GetAncestor(initial.sourceWindow, GA_ROOT) != initial.sourceWindow ||
                initial.sourceWindow == window_) {
                showError(L"The source must be a valid Starlit top-level window.");
                return;
            }
            source_ = initial.sourceWindow;
            if (windowVisible_) {
                HRESULT mirrorResult = DwmRegisterThumbnail(window_, source_, &thumbnail_);
                if (FAILED(mirrorResult)) {
                    showError(L"Cannot mirror Starlit via DWM (" + hexError(mirrorResult) + L").");
                    return;
                }
                updateThumbnail();
                if (!error_.empty()) return;
            }
            try {
                audio_.start(initial, !windowVisible_);
                started_ = true;
            } catch (const std::exception &failure) {
                showError(utf8ToWide(failure.what()));
            }
            return;
        }
        if (packet.type == 'S') {
            if (!started_) {
                showError(L"Received playback sync before initialization.");
                return;
            }
            Sync next;
            if (!decodeSync(packet.payload, next)) {
                showError(L"Invalid playback synchronization packet.", true);
                return;
            }
            lastSync_ = next;
            lastSyncAt_ = GetTickCount64();
            haveSync_ = true;
            try {
                applySync();
            } catch (const std::exception &failure) {
                showError(utf8ToWide(failure.what()));
            }
        }
    }

    void paint() {
        PAINTSTRUCT paint = {};
        HDC dc = BeginPaint(window_, &paint);
        RECT rect = {};
        GetClientRect(window_, &rect);
        HBRUSH background = CreateSolidBrush(RGB(12, 14, 18));
        FillRect(dc, &rect, background);
        DeleteObject(background);

        // DWM draws a valid thumbnail over the client area after this paint.
        if (!error_.empty() || !thumbnail_) {
            SetBkMode(dc, TRANSPARENT);
            SetTextColor(dc, error_.empty() ? RGB(222, 225, 230) : RGB(255, 140, 140));
            RECT message = rect;
            InflateRect(&message, -32, -32);
            const std::wstring display = error_.empty()
                ? L"Preparing the Starlit Discord sharing window..."
                : L"Starlit - Discord Share\n\n" + error_;
            DrawTextW(dc, display.c_str(), -1, &message, DT_CENTER | DT_VCENTER | DT_WORDBREAK);
        }
        EndPaint(window_, &paint);
    }

    void cleanup() {
        audio_.stop();
        if (thumbnail_) {
            DwmUnregisterThumbnail(thumbnail_);
            thumbnail_ = nullptr;
        }
    }

private:
    void showError(std::wstring message, bool protocolFailure = false) {
        if (!error_.empty()) return;
        error_ = std::move(message);
        audio_.stop();
        if (thumbnail_) {
            DwmUnregisterThumbnail(thumbnail_);
            thumbnail_ = nullptr;
        }
        // Allow a visible-window error to be read; for hidden sharing exit
        // promptly so the parent can mark the option unavailable in settings.
        (void)protocolFailure;
        fatalExitAt_ = GetTickCount64() + (windowVisible_ ? kProtocolErrorGraceMs : 200);
        InvalidateRect(window_, nullptr, TRUE);
    }

    void updateThumbnail() {
        RECT area = {};
        GetClientRect(window_, &area);
        if (area.right <= 0 || area.bottom <= 0 || !thumbnail_) return;
        SIZE sourceSize = {};
        HRESULT result = DwmQueryThumbnailSourceSize(thumbnail_, &sourceSize);
        if (FAILED(result) || sourceSize.cx <= 0 || sourceSize.cy <= 0) {
            showError(L"Cannot query the source video size from DWM.");
            return;
        }
        const int width = area.right - area.left;
        const int height = area.bottom - area.top;
        RECT destination = area;
        if (static_cast<int64_t>(width) * sourceSize.cy >
            static_cast<int64_t>(height) * sourceSize.cx) {
            int fittedWidth = static_cast<int>(
                static_cast<int64_t>(height) * sourceSize.cx / sourceSize.cy);
            destination.left += (width - fittedWidth) / 2;
            destination.right = destination.left + fittedWidth;
        } else {
            int fittedHeight = static_cast<int>(
                static_cast<int64_t>(width) * sourceSize.cy / sourceSize.cx);
            destination.top += (height - fittedHeight) / 2;
            destination.bottom = destination.top + fittedHeight;
        }
        DWM_THUMBNAIL_PROPERTIES properties = {};
        properties.dwFlags = DWM_TNP_VISIBLE | DWM_TNP_RECTDESTINATION |
                             DWM_TNP_OPACITY | DWM_TNP_SOURCECLIENTAREAONLY;
        properties.fVisible = TRUE;
        properties.opacity = 255;
        properties.rcDestination = destination;
        properties.fSourceClientAreaOnly = TRUE;
        result = DwmUpdateThumbnailProperties(thumbnail_, &properties);
        if (FAILED(result)) {
            showError(L"Cannot update the Starlit window mirror (" + hexError(result) + L").");
        }
    }

    void applySync() {
        const ULONGLONG ageMs = GetTickCount64() - lastSyncAt_;
        if (ageMs > kMissingSyncMs) {
            audio_.setPaused(true);
            return; // Parent stalled: keep the companion quiet.
        }
        double actual = 0;
        if (!audio_.timePosition(actual)) {
            audio_.setPaused(true);
            return; // The source is not loaded; retry on the next timer tick.
        }
        double target = static_cast<double>(lastSync_.positionMs) / 1000.0;
        if (!lastSync_.paused) {
            target += static_cast<double>(ageMs) / 1000.0 * lastSync_.speed;
        }
        target = (std::max)(0.0, target);

        const ULONGLONG now = GetTickCount64();
        // A remote source may buffer after an exact seek. Avoid issuing another
        // disruptive seek every 100 ms while the previous one is still loading.
        if (!firstSeekDone_ ||
            (std::abs(actual - target) > kSeekThresholdSeconds && now - lastSeekAt_ >= 1000)) {
            audio_.setPaused(true);
            audio_.seek(target);
            firstSeekDone_ = true;
            lastSeekAt_ = now;
        }
        if (lastSpeed_ != lastSync_.speed) {
            audio_.setSpeed(lastSync_.speed);
            lastSpeed_ = lastSync_.speed;
        }
        if (lastAid_ != lastSync_.aid) {
            // Never change tracks while the previous track is still audible.
            audio_.setVolume(0.0);
            lastVolume_ = 0.0;
            audio_.setPaused(true);
            if (audio_.setAid(lastSync_.aid)) lastAid_ = lastSync_.aid;
        }
        const bool safeAudio = audio_.safeToUnmute(lastSync_.aid);
        const double safeVolume = safeAudio ? lastSync_.volume : 0.0;
        if (lastVolume_ != safeVolume) {
            audio_.setVolume(safeVolume);
            lastVolume_ = safeVolume;
        }
        audio_.setPaused(lastSync_.paused || !safeAudio);
    }

    HWND window_ = nullptr;
    HWND source_ = nullptr;
    std::uint64_t expectedSource_ = 0;
    bool windowVisible_ = false;
    HTHUMBNAIL thumbnail_ = nullptr;
    AudioPlayer audio_;
    bool started_ = false;
    bool haveSync_ = false;
    bool firstSeekDone_ = false;
    ULONGLONG lastSeekAt_ = 0;
    Sync lastSync_;
    ULONGLONG lastSyncAt_ = 0;
    double lastSpeed_ = -1;
    double lastVolume_ = -1;
    int64_t lastAid_ = (std::numeric_limits<int64_t>::min)();
    std::wstring error_;
    ULONGLONG fatalExitAt_ = 0;
};

LRESULT CALLBACK companionWndProc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    if (message == WM_NCCREATE) {
        auto *create = reinterpret_cast<CREATESTRUCTW *>(lparam);
        SetWindowLongPtrW(window, GWLP_USERDATA,
                          reinterpret_cast<LONG_PTR>(create->lpCreateParams));
        // Preserve the caption for the optional visible Discord window picker.
        return DefWindowProcW(window, message, wparam, lparam);
    }
    auto *app = reinterpret_cast<Companion *>(GetWindowLongPtrW(window, GWLP_USERDATA));
    switch (message) {
        case WM_ERASEBKGND:
            return 1;
        case WM_PAINT:
            if (app) app->paint();
            return 0;
        case WM_SIZE:
            if (app) app->tick();
            return 0;
        case WM_TIMER:
            if (wparam == kSyncTimer && app) app->tick();
            return 0;
        case kPacketMessage: {
            std::unique_ptr<Packet> packet(reinterpret_cast<Packet *>(lparam));
            if (app && packet) app->handlePacket(*packet);
            return 0;
        }
        case WM_CLOSE:
            DestroyWindow(window);
            return 0;
        case WM_DESTROY:
            KillTimer(window, kSyncTimer);
            if (app) app->cleanup();
            PostQuitMessage(0);
            return 0;
    }
    return DefWindowProcW(window, message, wparam, lparam);
}

} // namespace

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR commandLine, int show) {
    uint64_t expectedHwnd = 0;
    std::wstring commandError;
    try {
        expectedHwnd = optionalHwndArgument(commandLine);
    } catch (const std::exception &failure) {
        commandError = utf8ToWide(failure.what());
    }
    const bool windowVisible = show != SW_HIDE;
    Companion companion(expectedHwnd, windowVisible);
    WNDCLASSEXW windowClass = {};
    windowClass.cbSize = sizeof(windowClass);
    windowClass.hInstance = instance;
    windowClass.lpszClassName = kWindowClass;
    windowClass.lpfnWndProc = companionWndProc;
    windowClass.hCursor = LoadCursorW(nullptr, MAKEINTRESOURCEW(32512)); // IDC_ARROW
    windowClass.hbrBackground = reinterpret_cast<HBRUSH>(GetStockObject(BLACK_BRUSH));
    if (!RegisterClassExW(&windowClass)) return 1;

    HWND window = CreateWindowExW(
        windowVisible ? WS_EX_APPWINDOW : WS_EX_TOOLWINDOW, kWindowClass, kWindowTitle,
        WS_OVERLAPPEDWINDOW | (windowVisible ? WS_VISIBLE : 0),
        CW_USEDEFAULT, CW_USEDEFAULT, 1280, 720,
        nullptr, nullptr, instance, &companion
    );
    if (!window) return 2;
    companion.setWindow(window);
    if (windowVisible) {
        ShowWindow(window, show);
        UpdateWindow(window);
    }
    SetTimer(window, kSyncTimer, kSyncTimerMs, nullptr);

    std::atomic<bool> closing{false};
    std::thread pipeReader([&]() { readPipe(window, closing); });
    if (!commandError.empty()) {
        Packet error;
        error.type = 'E';
        error.error = commandError;
        companion.handlePacket(error);
    }

    MSG message = {};
    while (GetMessageW(&message, nullptr, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
    closing.store(true);
    CancelSynchronousIo(static_cast<HANDLE>(pipeReader.native_handle()));
    pipeReader.join();
    companion.cleanup();
    return static_cast<int>(message.wParam);
}
