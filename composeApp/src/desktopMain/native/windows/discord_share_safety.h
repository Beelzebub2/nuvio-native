#pragma once

#include <cstdint>
#include <cwctype>
#include <string>
#include <vector>

namespace discord_share {

constexpr char kCentrePanFilter[] = "lavfi=[pan=stereo|c0=0.70710678*FC|c1=0.70710678*FC]";
// libmpv normalizes the same `af` option when reading it back as a property.
constexpr char kCentrePanFilterReported[] =
    "lavfi=graph=%44%pan=stereo|c0=0.70710678*FC|c1=0.70710678*FC";

inline bool centreOutputIsSafe(bool hasCentre, int64_t requestedAid, int64_t actualAid,
                              int64_t outputChannels, const std::string &filters) {
    return hasCentre && requestedAid > 0 && actualAid == requestedAid &&
           outputChannels == 2 &&
           (filters == kCentrePanFilterReported || filters == kCentrePanFilter);
}

inline bool isKnownCentreLayout(const std::string &layout, int64_t channels) {
    return (channels == 6 && (layout == "5.1" || layout.rfind("5.1(", 0) == 0)) ||
           (channels == 8 && (layout == "7.1" || layout.rfind("7.1(", 0) == 0));
}

struct RenderEndpoint {
    std::wstring id;
    std::wstring friendlyName;
};

inline std::wstring lowercaseEndpointName(std::wstring name) {
    for (auto &letter : name) letter = static_cast<wchar_t>(std::towlower(letter));
    return name;
}

// Only the original VB-CABLE playback endpoint is supported. The A/B/C/D
// variants and its CABLE Output recording endpoint must not match.
inline bool isVbCableInput(const std::wstring &name) {
    const auto lower = lowercaseEndpointName(name);
    return lower == L"cable input (vb-audio virtual cable)" ||
           lower == L"cable input (vb-audio cable)";
}

inline bool isSteamStreamingSpeakers(const std::wstring &name) {
    return lowercaseEndpointName(name).find(L"steam streaming speakers") != std::wstring::npos;
}

// Call with only active Windows render endpoints. Ambiguous or default devices
// are unsafe even when another eligible endpoint also exists.
inline std::wstring uniqueUnusedSteamEndpoint(const std::vector<RenderEndpoint> &active,
                                               const std::wstring &console,
                                               const std::wstring &multimedia,
                                               const std::wstring &communications) {
    if (console.empty() || multimedia.empty() || communications.empty()) return {};
    std::wstring selected;
    for (const auto &endpoint : active) {
        if (!isSteamStreamingSpeakers(endpoint.friendlyName)) continue;
        if (endpoint.id.empty() || endpoint.id == console || endpoint.id == multimedia ||
            endpoint.id == communications || !selected.empty()) return {};
        selected = endpoint.id;
    }
    return selected;
}

inline std::wstring uniqueUnusedDiscordEndpoint(const std::vector<RenderEndpoint> &active,
                                                const std::wstring &console,
                                                const std::wstring &multimedia,
                                                const std::wstring &communications) {
    if (console.empty() || multimedia.empty() || communications.empty()) return {};
    bool foundVbCable = false;
    std::wstring selected;
    for (const auto &endpoint : active) {
        if (!isVbCableInput(endpoint.friendlyName)) continue;
        if (foundVbCable || endpoint.id.empty() || endpoint.id == console ||
            endpoint.id == multimedia || endpoint.id == communications) return {};
        foundVbCable = true;
        selected = endpoint.id;
    }
    // Do not fall back to Steam when VB-CABLE is present but unsafe.
    if (foundVbCable) return selected;
    return uniqueUnusedSteamEndpoint(active, console, multimedia, communications);
}

} // namespace discord_share
