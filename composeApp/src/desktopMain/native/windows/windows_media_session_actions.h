#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <utility>

namespace starlit {

enum class MediaCommand { None, Play, Pause, SeekForward, SeekBackward, Next, SeekTo };

// Numeric values are Windows::Media::SystemMediaTransportControlsButton's stable ABI values.
// The UI owns the skip/next rules; only advertise Next when a playable successor exists.
inline MediaCommand mediaCommandForButton(int button, bool nextPlayable) noexcept {
    switch (button) {
        case 0: return MediaCommand::Play;
        case 1: return MediaCommand::Pause;
        case 4: return MediaCommand::SeekForward;
        case 5: return MediaCommand::SeekBackward;
        case 6: return nextPlayable ? MediaCommand::Next : MediaCommand::None;
        default: return MediaCommand::None;
    }
}

// Wire event names are already handled by the shared player runtime. Keep the
// operating-system commands on that route instead of creating separate actions.
inline const char* mediaPlayerEventName(MediaCommand command) noexcept {
    switch (command) {
        case MediaCommand::Play:
        case MediaCommand::Pause: return "setPlaybackStateQuiet";
        case MediaCommand::SeekForward: return "seekForward";
        case MediaCommand::SeekBackward: return "seekBack";
        case MediaCommand::Next: return "playNextEpisode";
        case MediaCommand::SeekTo: return "scrubFinish";
        default: return nullptr;
    }
}

// WinRT TimeSpan values are in 100-nanosecond ticks. Ignore invalid/unknown seeks.
inline int64_t mediaSeekMilliseconds(int64_t timeSpanTicks, int64_t durationMs) noexcept {
    if (timeSpanTicks < 0 || durationMs <= 0) return -1;
    const int64_t ms = timeSpanTicks / 10000;
    return ms > durationMs ? durationMs : ms;
}

// WinRT event callbacks can finish after teardown begins. Reject them before invoking JNI.
class MediaCommandGate {
public:
    explicit MediaCommandGate(std::function<void(MediaCommand, int64_t)> handler)
        : handler_(std::move(handler)) {}

    void emit(MediaCommand command, int64_t milliseconds = 0) const {
        if (!active_.load(std::memory_order_acquire) || command == MediaCommand::None) return;
        handler_(command, milliseconds);
    }

    void close() noexcept { active_.store(false, std::memory_order_release); }
    bool active() const noexcept { return active_.load(std::memory_order_acquire); }
    void setNextPlayable(bool playable) noexcept { nextPlayable_.store(playable, std::memory_order_release); }
    bool nextPlayable() const noexcept { return nextPlayable_.load(std::memory_order_acquire); }
    void setDurationMs(int64_t duration) noexcept {
        durationMs_.store(duration > 0 ? duration : 0, std::memory_order_release);
    }
    int64_t durationMs() const noexcept { return durationMs_.load(std::memory_order_acquire); }

private:
    std::atomic<bool> active_{true};
    std::atomic<bool> nextPlayable_{false};
    std::atomic<int64_t> durationMs_{0};
    std::function<void(MediaCommand, int64_t)> handler_;
};

} // namespace starlit
