#pragma once

#include <cstdint>
#include <cwctype>
#include <string>
#include <string_view>

namespace starlit_surround {

// Windows WAVEFORMATEXTENSIBLE channel masks; an absent or ambiguous
// speaker mask is not evidence that multichannel PCM reaches speakers.
constexpr uint32_t kFivePointOneBackMask = 0x003f;
constexpr uint32_t kFivePointOneSideMask = 0x060f;
constexpr uint32_t kSevenPointOneMask = 0x063f;
constexpr uint32_t kStereoMask = 0x0003;

// Exposed to Kotlin as off=0, physical speakers=1, headset 7.1=2.
enum class Mode : int {
    off = 0,
    speakersFivePointOne = 1,
    virtualHeadsetSevenPointOne = 2,
};

enum class Layout {
    unsupported,
    fivePointOneBack,
    fivePointOneSide,
    virtualHeadsetSevenPointOne,
};

inline Layout configuredLayout(unsigned channels, uint32_t speakerMask) {
    if (channels != 6) return Layout::unsupported;
    if (speakerMask == kFivePointOneSideMask) return Layout::fivePointOneSide;
    if (speakerMask == kFivePointOneBackMask) return Layout::fivePointOneBack;
    return Layout::unsupported;
}

// A stereo mix format does not mean the driver's per-stream surround APO
// cannot accept 7.1 PCM. Never infer this from IsFormatSupported alone:
// ordinary stereo endpoints can also accept and silently downmix eight channels.
inline Layout configuredVirtualHeadsetLayout(
    unsigned channels, uint32_t speakerMask,
    std::wstring_view endpointName, bool acceptsExactSevenPointOne
) {
    if (!acceptsExactSevenPointOne) return Layout::unsupported;
    std::wstring name(endpointName);
    for (auto &letter : name) letter = static_cast<wchar_t>(std::towlower(letter));
    // Restrict the initial opt-in to the verified family with a 7.1 iCUE DSP.
    if (name.find(L"corsair") == std::wstring::npos ||
        name.find(L"void") == std::wstring::npos) return Layout::unsupported;
    if ((channels == 2 && speakerMask == kStereoMask) ||
        (channels == 8 && speakerMask == kSevenPointOneMask)) {
        return Layout::virtualHeadsetSevenPointOne;
    }
    return Layout::unsupported;
}

inline bool acceptsStereoInput(std::string_view decodedChannels) {
    return decodedChannels == "stereo" || decodedChannels == "2.0";
}

inline std::string_view mpvOutputLayout(Layout layout) {
    switch (layout) {
        case Layout::fivePointOneSide: return "5.1(side)";
        case Layout::fivePointOneBack: return "5.1";
        case Layout::virtualHeadsetSevenPointOne: return "7.1";
        default: return {};
    }
}

// The LFE is deliberately silent: bass management is handled by the system
// or amplifier. The reduced level adds headroom for matrix-derived channels.
inline const char *mpvFilter(Layout layout) {
    switch (layout) {
        case Layout::fivePointOneSide:
            return "@starlit-surround:lavfi=[surround=chl_in=stereo:chl_out=5.1(side):lfe=0:level_out=0.65]";
        case Layout::fivePointOneBack:
            return "@starlit-surround:lavfi=[surround=chl_in=stereo:chl_out=5.1:lfe=0:level_out=0.65]";
        case Layout::virtualHeadsetSevenPointOne:
            return "@starlit-surround:lavfi=[surround=chl_in=stereo:chl_out=7.1:lfe=0:level_out=0.65]";
        default: return nullptr;
    }
}

constexpr const char *kFilterLabel = "@starlit-surround";

} // namespace starlit_surround
