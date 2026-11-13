#pragma once

#include <cstdint>
#include <string_view>

namespace starlit_surround {

// Windows WAVEFORMATEXTENSIBLE channel masks; an absent or ambiguous
// speaker mask is not evidence that multichannel PCM reaches speakers.
constexpr uint32_t kFivePointOneBackMask = 0x003f;
constexpr uint32_t kFivePointOneSideMask = 0x060f;
constexpr uint32_t kSevenPointOneMask = 0x063f;
constexpr uint32_t kStereoMask = 0x0003;

// Keep existing native/Kotlin mode numbers stable for saved settings.
enum class Mode : int {
    off = 0,
    speakersFivePointOne = 1,
    virtualHeadsetSevenPointOne = 2,
    virtualHeadsetFivePointOne = 3,
};

enum class Layout {
    unsupported,
    fivePointOneBack,
    fivePointOneSide,
    virtualHeadsetSevenPointOne,
    virtualHeadsetFivePointOneBack,
    virtualHeadsetFivePointOneSide,
};

inline Layout configuredLayout(unsigned channels, uint32_t speakerMask) {
    if (channels != 6) return Layout::unsupported;
    if (speakerMask == kFivePointOneSideMask) return Layout::fivePointOneSide;
    if (speakerMask == kFivePointOneBackMask) return Layout::fivePointOneBack;
    return Layout::unsupported;
}

// A headset driver can accept 5.1/7.1 PCM and virtualize it through its own
// APO even when the shared-mode mix is stereo. Selection is deliberately
// explicit, not brand-based: format support alone does NOT prove that the
// driver provides HRTF rather than ordinary stereo downmixing.
inline Layout configuredVirtualHeadsetLayout(
    Mode mode, unsigned channels, uint32_t speakerMask,
    bool acceptsFivePointOneSide, bool acceptsFivePointOneBack,
    bool acceptsSevenPointOne
) {
    const bool stereoMix = channels == 2 && speakerMask == kStereoMask;
    if (mode == Mode::virtualHeadsetSevenPointOne) {
        if (acceptsSevenPointOne &&
            (stereoMix || (channels == 8 && speakerMask == kSevenPointOneMask))) {
            return Layout::virtualHeadsetSevenPointOne;
        }
    } else if (mode == Mode::virtualHeadsetFivePointOne) {
        // Preserve the actual 5.1 speaker mask when the mix is six channels,
        // preferring the common 5.1(side) route for a stereo-mix headset.
        if (acceptsFivePointOneSide &&
            (stereoMix || (channels == 6 && speakerMask == kFivePointOneSideMask))) {
            return Layout::virtualHeadsetFivePointOneSide;
        }
        if (acceptsFivePointOneBack &&
            (stereoMix || (channels == 6 && speakerMask == kFivePointOneBackMask))) {
            return Layout::virtualHeadsetFivePointOneBack;
        }
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
        case Layout::virtualHeadsetFivePointOneSide: return "5.1(side)";
        case Layout::virtualHeadsetFivePointOneBack: return "5.1";
        case Layout::virtualHeadsetSevenPointOne: return "7.1";
        default: return {};
    }
}

// The LFE is deliberately silent: bass management is handled by the system
// or amplifier. The reduced level adds headroom for matrix-derived channels.
inline const char *mpvFilter(Layout layout) {
    switch (layout) {
        case Layout::fivePointOneSide:
        case Layout::virtualHeadsetFivePointOneSide:
            return "@starlit-surround:lavfi=[surround=chl_in=stereo:chl_out=5.1(side):lfe=0:level_out=0.65]";
        case Layout::fivePointOneBack:
        case Layout::virtualHeadsetFivePointOneBack:
            return "@starlit-surround:lavfi=[surround=chl_in=stereo:chl_out=5.1:lfe=0:level_out=0.65]";
        case Layout::virtualHeadsetSevenPointOne:
            return "@starlit-surround:lavfi=[surround=chl_in=stereo:chl_out=7.1:lfe=0:level_out=0.65]";
        default: return nullptr;
    }
}

constexpr const char *kFilterLabel = "@starlit-surround";

} // namespace starlit_surround
