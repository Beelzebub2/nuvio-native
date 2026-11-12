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

// The Starlit driver exposes a dedicated render device; reject partial-name
// matches so that similarly named physical devices are never selected.
inline bool isStarlitVirtualAudio(const std::wstring &name) {
    const auto lower = lowercaseEndpointName(name);
    return lower == L"starlit virtual audio" ||
           lower == L"speakers (starlit virtual audio)";
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

    // A present but unsafe/ambiguous Starlit endpoint must fail closed rather
    // than silently switching to another output. Steam is only a legacy fallback
    // while the optional Starlit driver is not installed.
    bool hasStarlit = false;
    std::wstring starlit;
    for (const auto &endpoint : active) {
        if (!isStarlitVirtualAudio(endpoint.friendlyName)) continue;
        if (hasStarlit || endpoint.id.empty() || endpoint.id == console ||
            endpoint.id == multimedia || endpoint.id == communications) return {};
        hasStarlit = true;
        starlit = endpoint.id;
    }
    if (hasStarlit) return starlit;
    return uniqueUnusedSteamEndpoint(active, console, multimedia, communications);
}

} // namespace discord_share
