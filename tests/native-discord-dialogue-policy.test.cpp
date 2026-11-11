#include "../composeApp/src/desktopMain/native/windows/discord_dialogue_policy.h"
#include <cassert>

int main() {
    using namespace starlit::discord_dialogue;
    assert(hasFrontCentre("5.1", 6));
    assert(hasFrontCentre("5.1(side)", 6));
    assert(hasFrontCentre("7.1", 8));
    assert(hasFrontCentre("7.1(wide)", 8));
    assert(!hasFrontCentre("5.1", 2));
    assert(!hasFrontCentre("stereo", 2));
    assert(!hasFrontCentre("5.0", 5));
    assert(!hasFrontCentre("6.0", 6));
    assert(!hasFrontCentre("", 6));
    assert(!hasFrontCentre("5.1", 8));
    assert(isSteamStreamingSpeakers(L"Speakers (Steam Streaming Speakers)"));
    assert(!isSteamStreamingSpeakers(L"Speakers (Realtek Audio)"));
    assert(isUnusedRenderEndpoint(L"steam", L"realtek", L"realtek", L"microphone"));
    assert(!isUnusedRenderEndpoint(L"steam", L"steam", L"realtek", L"headset"));
    assert(!isUnusedRenderEndpoint(L"steam", L"headset", L"steam", L"realtek"));
    assert(!isUnusedRenderEndpoint(L"steam", L"headset", L"realtek", L"steam"));
    assert(!isUnusedRenderEndpoint(L"", L"headset", L"realtek", L"microphone"));
}
