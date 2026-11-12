#pragma once

#include <cstdint>
#include <string_view>

namespace starlit_surround {

// Windows WAVEFORMATEXTENSIBLE channel masks; an absent or ambiguous
// speaker mask is not evidence that multichannel PCM reaches speakers.
constexpr uint32_t kFivePointOneBackMask = 0x003f;
constexpr uint32_t kFivePointOneSideMask = 0x060f;
constexpr uint32_t kSevenPointOneMask = 0x063f;

enum class Layout {
    unsupported,
    fivePointOneBack,
    fivePointOneSide,
};

inline Layout configuredLayout(unsigned channels, uint32_t speakerMask) {
    if (channels != 6) return Layout::unsupported;
    if (speakerMask == kFivePointOneSideMask) return Layout::fivePointOneSide;
    if (speakerMask == kFivePointOneBackMask) return Layout::fivePointOneBack;
    return Layout::unsupported;
}

inline bool acceptsStereoInput(std::string_view decodedChannels) {
    return decodedChannels == "stereo" || decodedChannels == "2.0";
}

inline std::string_view mpvOutputLayout(Layout layout) {
    switch (layout) {
        case Layout::fivePointOneSide: return "5.1(side)";
        case Layout::fivePointOneBack: return "5.1";
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
        default: return nullptr;
    }
}

constexpr const char *kFilterLabel = "@starlit-surround";

} // namespace starlit_surround
