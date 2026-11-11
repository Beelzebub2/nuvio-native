#include "../composeApp/src/desktopMain/native/windows/discord_share_safety.h"

#include <cassert>
#include <vector>

int main() {
    using namespace discord_share;

    assert(isKnownCentreLayout("5.1", 6));
    assert(isKnownCentreLayout("5.1(side)", 6));
    assert(isKnownCentreLayout("7.1(wide)", 8));
    assert(!isKnownCentreLayout("5.0", 5));
    assert(!isKnownCentreLayout("stereo", 2));
    assert(!isKnownCentreLayout("5.1", 2));
    assert(!isKnownCentreLayout("6.0", 6));
    assert(!isKnownCentreLayout("5.10", 6));

    assert(centreOutputIsSafe(true, 2, 2, 2, kCentrePanFilter));
    assert(centreOutputIsSafe(true, 2, 2, 2, kCentrePanFilterReported));
    assert(!centreOutputIsSafe(true, 2, 2, 2, "lavfi=graph=%44%pan=stereo|c0=FL|c1=FR"));
    assert(!centreOutputIsSafe(true, 2, 1, 2, kCentrePanFilter)); // Wrong track.
    assert(!centreOutputIsSafe(true, -1, 1, 2, kCentrePanFilter)); // Parent not ready.
    assert(!centreOutputIsSafe(true, 2, 2, 6, kCentrePanFilter)); // Not stereo output.
    assert(!centreOutputIsSafe(true, 2, 2, 2, "")); // Filter missing.
    assert(!centreOutputIsSafe(true, 2, 2, 2, "lavfi=[pan=stereo|c0=FL|c1=FR]"));
    assert(!centreOutputIsSafe(false, 2, 2, 2, kCentrePanFilter)); // No FC.

    const std::vector<RenderEndpoint> active = {
        {L"steam", L"Speakers (Steam Streaming Speakers)"},
        {L"headphones", L"Headphones (Realtek)"},
    };
    assert(uniqueUnusedSteamEndpoint(active, L"headphones", L"headphones", L"headphones") == L"steam");
    assert(uniqueUnusedSteamEndpoint(active, L"steam", L"headphones", L"headphones").empty());
    assert(uniqueUnusedSteamEndpoint(active, L"headphones", L"steam", L"headphones").empty());
    assert(uniqueUnusedSteamEndpoint(active, L"headphones", L"headphones", L"steam").empty());
    assert(uniqueUnusedSteamEndpoint(active, L"", L"headphones", L"headphones").empty());
    assert(uniqueUnusedSteamEndpoint({active[0], {L"steam2", L"Steam Streaming Speakers"}},
                                     L"headphones", L"headphones", L"headphones").empty());
    assert(uniqueUnusedSteamEndpoint({active[1]}, L"headphones", L"headphones", L"headphones").empty());
}
