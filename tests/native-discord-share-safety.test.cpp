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

    assert(isVbCableInput(L"CABLE Input (VB-Audio Virtual Cable)"));
    assert(isVbCableInput(L"cable input (vb-audio cable)"));
    assert(!isVbCableInput(L"CABLE Output (VB-Audio Virtual Cable)"));
    assert(!isVbCableInput(L"CABLE-A Input (VB-Audio Cable A)"));
    assert(!isVbCableInput(L"CABLE Input (Not VB-Audio)"));
    assert(!isVbCableInput(L"Speakers (VB-Audio Virtual Cable)"));
    assert(!isVbCableInput(L"CABLE Input"));

    const RenderEndpoint vb{L"vb", L"CABLE Input (VB-Audio Virtual Cable)"};
    assert(uniqueUnusedDiscordEndpoint({active[0], vb, active[1]},
                                       L"headphones", L"headphones", L"headphones") == L"vb");
    assert(uniqueUnusedDiscordEndpoint(active, L"headphones", L"headphones", L"headphones") == L"steam");
    assert(uniqueUnusedDiscordEndpoint({vb, active[1]},
                                       L"headphones", L"headphones", L"headphones") == L"vb");
    assert(uniqueUnusedDiscordEndpoint({vb, active[0]}, L"vb", L"headphones", L"headphones").empty());
    assert(uniqueUnusedDiscordEndpoint({vb, active[0]}, L"headphones", L"vb", L"headphones").empty());
    assert(uniqueUnusedDiscordEndpoint({vb, active[0]}, L"headphones", L"headphones", L"vb").empty());
    assert(uniqueUnusedDiscordEndpoint({vb, active[0]}, L"", L"headphones", L"headphones").empty());
    assert(uniqueUnusedDiscordEndpoint({vb, {L"other", L"CABLE INPUT (VB-AUDIO VIRTUAL CABLE)"}, active[0]},
                                       L"headphones", L"headphones", L"headphones").empty());
    assert(uniqueUnusedDiscordEndpoint({{L"", vb.friendlyName}, active[0]},
                                       L"headphones", L"headphones", L"headphones").empty());
    // Recording endpoints must never be mistaken for output endpoints.
    assert(uniqueUnusedDiscordEndpoint({{L"capture", L"CABLE Output (VB-Audio Virtual Cable)"}, active[0]},
                                       L"headphones", L"headphones", L"headphones") == L"steam");
}
