// Real libmpv decode smoke, isolated from app accounts and UI. Arguments are a
// bundled libmpv DLL and an ffmpeg-generated test video. No network is needed.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <cassert>
#include <chrono>
#include <iostream>
#include <thread>
#include "../composeApp/src/desktopMain/native/macos/include/mpv/client.h"
#include "../composeApp/src/desktopMain/native/playback_diagnostics.h"

template <typename T> T symbol(HMODULE library, const char *name) {
    auto address = GetProcAddress(library, name);
    if (!address) throw name;
    return reinterpret_cast<T>(address);
}

int main(int argc, char **argv) {
    if (argc != 3) return 2;
    auto library = LoadLibraryA(argv[1]);
    if (!library) return 3;
    auto create = symbol<decltype(&mpv_create)>(library, "mpv_create");
    auto option = symbol<decltype(&mpv_set_option_string)>(library, "mpv_set_option_string");
    auto initialize = symbol<decltype(&mpv_initialize)>(library, "mpv_initialize");
    auto command = symbol<decltype(&mpv_command)>(library, "mpv_command");
    auto property = symbol<decltype(&mpv_get_property)>(library, "mpv_get_property");
    auto setProperty = symbol<decltype(&mpv_set_property)>(library, "mpv_set_property");
    auto freeValue = symbol<decltype(&mpv_free)>(library, "mpv_free");
    auto destroy = symbol<decltype(&mpv_terminate_destroy)>(library, "mpv_terminate_destroy");
    auto player = create();
    assert(player);
    assert(option(player, "config", "no") == 0);
    assert(option(player, "vo", "null") == 0);
    assert(option(player, "ao", "null") == 0);
    assert(option(player, "hwdec", "no") == 0);
    assert(initialize(player) == 0);
    const char *load[] = {"loadfile", argv[2], nullptr};
    assert(command(player, load) == 0);
    PlaybackDiagnosticsMetrics metrics;
    double position = 0;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    while (std::chrono::steady_clock::now() < deadline) {
        metrics = readPlaybackDiagnostics(player, true, property, freeValue);
        property(player, "time-pos", MPV_FORMAT_DOUBLE, &position);
        if (position > 1 && metrics.width == 320 && metrics.height == 180) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
    assert(position > 1 && metrics.width == 320 && metrics.height == 180);
    assert(metrics.decoder == 0 && metrics.speed == 1);
    double requestedSpeed = 1.25;
    assert(setProperty(player, "speed", MPV_FORMAT_DOUBLE, &requestedSpeed) == 0);
    metrics = readPlaybackDiagnostics(player, true, property, freeValue);
    assert(metrics.speed == requestedSpeed);
    auto disabled = readPlaybackDiagnostics(player, false, property, freeValue);
    assert(disabled.width == 0 && disabled.decoder == -1 && disabled.speed == -1 && disabled.bufferAhead == -1);
    int pause = 1;
    assert(setProperty(player, "pause", MPV_FORMAT_FLAG, &pause) == 0);
    const char *seek[] = {"seek", "3", "absolute", nullptr};
    assert(command(player, seek) == 0);
    std::cout << "Real libmpv decoded " << metrics.width << "x" << metrics.height
              << ", software decoder, speed=" << metrics.speed << ", position>1s; opt-out cleared metrics; pause/seek accepted\n";
    destroy(player);
    FreeLibrary(library);
}
