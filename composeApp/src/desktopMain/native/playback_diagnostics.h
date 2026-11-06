#pragma once

#include <cmath>
#include <cstring>
#include <cstdint>

// Read only while the user has opened diagnostics. Negative measurements mean
// unavailable; zero cache ahead is a valid measurement, never a failure default.
struct PlaybackDiagnosticsMetrics {
    long long width = 0, height = 0;
    double bufferAhead = -1, speed = -1;
    int decoder = -1;
    bool seeking = false;
};

inline PlaybackDiagnosticsMetrics readPlaybackDiagnostics(
    mpv_handle *mpv, bool enabled,
    int (*getProperty)(mpv_handle *, const char *, mpv_format, void *),
    void (*freeValue)(void *)) {
    PlaybackDiagnosticsMetrics result;
    if (!enabled || !mpv) return result;
    auto number = [mpv, getProperty](const char *name) {
        double value = -1;
        return getProperty(mpv, name, MPV_FORMAT_DOUBLE, &value) >= 0 &&
            std::isfinite(value) && value >= 0 ? value : -1.0;
    };
    int64_t width = 0, height = 0;
    getProperty(mpv, "video-params/w", MPV_FORMAT_INT64, &width);
    getProperty(mpv, "video-params/h", MPV_FORMAT_INT64, &height);
    result.width = width;
    result.height = height;
    const double position = number("time-pos");
    const double end = number("demuxer-cache-time");
    result.bufferAhead = position >= 0 && end >= 0 ? std::fmax(0.0, end - position)
        : number("demuxer-cache-duration");
    result.speed = number("speed");
    int seeking = 0;
    if (getProperty(mpv, "seeking", MPV_FORMAT_FLAG, &seeking) >= 0) result.seeking = seeking != 0;
    char *decoder = nullptr;
    getProperty(mpv, "hwdec-current", MPV_FORMAT_STRING, &decoder);
    if (width > 0 && height > 0 && decoder && *decoder) result.decoder = std::strcmp(decoder, "no") == 0 ? 0 : 1;
    if (decoder) freeValue(decoder);
    return result;
}
