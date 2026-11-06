#include <cassert>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <map>
#include <string>

struct mpv_handle {};
enum mpv_format { MPV_FORMAT_STRING, MPV_FORMAT_DOUBLE, MPV_FORMAT_INT64, MPV_FORMAT_FLAG };
#include "../composeApp/src/desktopMain/native/playback_diagnostics.h"

static std::map<std::string, double> values;
static int reads = 0;
static const char *decoder = nullptr;
static int property(mpv_handle *, const char *name, mpv_format format, void *out) {
    ++reads;
    if (format == MPV_FORMAT_STRING) {
        if (!decoder) return -1;
        auto copy = static_cast<char *>(std::malloc(std::strlen(decoder) + 1));
        std::strcpy(copy, decoder);
        *static_cast<char **>(out) = copy;
        return 0;
    }
    auto found = values.find(name);
    if (found == values.end()) return -1;
    if (format == MPV_FORMAT_INT64) *static_cast<int64_t *>(out) = static_cast<int64_t>(found->second);
    else if (format == MPV_FORMAT_FLAG) *static_cast<int *>(out) = static_cast<int>(found->second);
    else *static_cast<double *>(out) = found->second;
    return 0;
}

int main() {
    mpv_handle player;
    auto metrics = readPlaybackDiagnostics(&player, false, property, std::free);
    assert(reads == 0); // Opening the panel is the opt-in to native sampling.
    assert(metrics.bufferAhead == -1 && metrics.speed == -1 && metrics.decoder == -1);
    metrics = readPlaybackDiagnostics(&player, true, property, std::free);
    assert(metrics.bufferAhead == -1 && metrics.speed == -1);
    values = {{"time-pos", 15}, {"demuxer-cache-time", 15}, {"speed", 1.25},
              {"video-params/w", 1920}, {"video-params/h", 1080}, {"seeking", 1}};
    decoder = "no";
    metrics = readPlaybackDiagnostics(&player, true, property, std::free);
    assert(metrics.bufferAhead == 0 && metrics.speed == 1.25 && metrics.decoder == 0);
    assert(metrics.width == 1920 && metrics.height == 1080 && metrics.seeking);
    values["demuxer-cache-time"] = 22;
    decoder = "d3d11va";
    metrics = readPlaybackDiagnostics(&player, true, property, std::free);
    assert(metrics.bufferAhead == 7 && metrics.decoder == 1);
    values["demuxer-cache-time"] = 10; // Old cache endpoint after seeking.
    assert(readPlaybackDiagnostics(&player, true, property, std::free).bufferAhead == 0);
    values.erase("demuxer-cache-time");
    values["demuxer-cache-duration"] = 4;
    values["speed"] = std::numeric_limits<double>::infinity();
    metrics = readPlaybackDiagnostics(&player, true, property, std::free);
    assert(metrics.bufferAhead == 4 && metrics.speed == -1);
    values["demuxer-cache-duration"] = std::numeric_limits<double>::quiet_NaN();
    assert(readPlaybackDiagnostics(&player, true, property, std::free).bufferAhead == -1);
}
