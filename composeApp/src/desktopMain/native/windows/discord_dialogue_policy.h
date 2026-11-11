#pragma once

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <string>
#include <cwctype>

namespace starlit {
namespace discord_dialogue {

// Only allow layouts known to contain FC. Channel count alone is not enough:
// a six-channel layout can omit the centre channel.
inline bool hasFrontCentre(const std::string &layout, std::int64_t channels) {
    if (channels != 6 && channels != 8) return false;
    std::string lower(layout);
    std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    const std::string expected = channels == 6 ? "5.1" : "7.1";
    return lower == expected ||
        (lower.size() > expected.size() && lower.compare(0, expected.size(), expected) == 0 &&
            lower[expected.size()] == '(');
}

inline bool isSteamStreamingSpeakers(const std::wstring &deviceName) {
    std::wstring normalized(deviceName);
    std::transform(normalized.begin(), normalized.end(), normalized.begin(), [](wchar_t c) {
        return static_cast<wchar_t>(std::towlower(c));
    });
    return normalized.find(L"steam streaming speakers") != std::wstring::npos;
}

// Do not use another role's default output; that could audibly duplicate dialogue
// on a normal listening device instead of the separate virtual endpoint.
inline bool isUnusedRenderEndpoint(
    const std::wstring &candidateId,
    const std::wstring &consoleDefaultId,
    const std::wstring &multimediaDefaultId,
    const std::wstring &communicationsDefaultId
) {
    if (candidateId.empty()) return false;
    return candidateId != consoleDefaultId &&
        candidateId != multimediaDefaultId &&
        candidateId != communicationsDefaultId;
}

}  // namespace discord_dialogue
}  // namespace starlit
