#include "surround_upmix_policy.h"

#include <cassert>
#include <string_view>

int main() {
    using starlit_surround::Layout;
    using starlit_surround::Mode;
    using starlit_surround::configuredLayout;
    using starlit_surround::acceptsStereoInput;
    assert(configuredLayout(6, starlit_surround::kFivePointOneSideMask) == Layout::fivePointOneSide);
    assert(configuredLayout(6, starlit_surround::kFivePointOneBackMask) == Layout::fivePointOneBack);
    assert(configuredLayout(8, starlit_surround::kSevenPointOneMask) == Layout::unsupported);
    const auto virtualLayout = starlit_surround::configuredVirtualHeadsetLayout;
    const auto stereoMask = starlit_surround::kStereoMask;
    const auto sideMask = starlit_surround::kFivePointOneSideMask;
    const auto backMask = starlit_surround::kFivePointOneBackMask;
    const auto sevenMask = starlit_surround::kSevenPointOneMask;
    // No brand or friendly-name requirements. User explicitly selects the mode.
    assert(virtualLayout(Mode::virtualHeadsetSevenPointOne, 2, stereoMask, false, false, true) ==
           Layout::virtualHeadsetSevenPointOne);
    assert(virtualLayout(Mode::virtualHeadsetSevenPointOne, 8, sevenMask, false, false, true) ==
           Layout::virtualHeadsetSevenPointOne);
    assert(virtualLayout(Mode::virtualHeadsetSevenPointOne, 2, stereoMask, false, false, false) ==
           Layout::unsupported);
    assert(virtualLayout(Mode::virtualHeadsetSevenPointOne, 6, sideMask, false, false, true) ==
           Layout::unsupported);
    assert(virtualLayout(Mode::virtualHeadsetSevenPointOne, 2, 0, false, false, true) ==
           Layout::unsupported);
    // A stereo mix with exact 5.1(side) support prefers side speakers.
    assert(virtualLayout(Mode::virtualHeadsetFivePointOne, 2, stereoMask, true, true, false) ==
           Layout::virtualHeadsetFivePointOneSide);
    assert(virtualLayout(Mode::virtualHeadsetFivePointOne, 2, stereoMask, false, true, false) ==
           Layout::virtualHeadsetFivePointOneBack);
    assert(virtualLayout(Mode::virtualHeadsetFivePointOne, 2, stereoMask, false, false, true) ==
           Layout::unsupported);
    // A six-channel mix must retain its own mask, not pick another layout.
    assert(virtualLayout(Mode::virtualHeadsetFivePointOne, 6, sideMask, true, true, false) ==
           Layout::virtualHeadsetFivePointOneSide);
    assert(virtualLayout(Mode::virtualHeadsetFivePointOne, 6, backMask, true, true, false) ==
           Layout::virtualHeadsetFivePointOneBack);
    assert(virtualLayout(Mode::virtualHeadsetFivePointOne, 6, backMask, true, false, false) ==
           Layout::unsupported);
    assert(virtualLayout(Mode::virtualHeadsetFivePointOne, 8, sevenMask, true, true, false) ==
           Layout::unsupported);
    assert(virtualLayout(Mode::speakersFivePointOne, 2, stereoMask, true, true, true) ==
           Layout::unsupported); // Physical and virtual paths never cross.
    assert(configuredLayout(6, 0) == Layout::unsupported);
    assert(configuredLayout(2, starlit_surround::kFivePointOneSideMask) == Layout::unsupported);
    assert(configuredLayout(6, 0x000f) == Layout::unsupported);
    assert(acceptsStereoInput("stereo"));
    assert(acceptsStereoInput("2.0"));
    assert(!acceptsStereoInput("mono"));
    assert(!acceptsStereoInput("5.1"));
    assert(!acceptsStereoInput("7.1"));
    assert(!acceptsStereoInput(""));
    assert(starlit_surround::mpvOutputLayout(Layout::fivePointOneSide) == "5.1(side)");
    assert(starlit_surround::mpvOutputLayout(Layout::fivePointOneBack) == "5.1");
    assert(starlit_surround::mpvOutputLayout(Layout::virtualHeadsetSevenPointOne) == "7.1");
    assert(starlit_surround::mpvOutputLayout(Layout::virtualHeadsetFivePointOneSide) == "5.1(side)");
    assert(starlit_surround::mpvOutputLayout(Layout::virtualHeadsetFivePointOneBack) == "5.1");
    assert(starlit_surround::mpvOutputLayout(Layout::unsupported).empty());
    assert(std::string_view(starlit_surround::mpvFilter(Layout::fivePointOneSide)).find("lfe=0") != std::string_view::npos);
    assert(std::string_view(starlit_surround::mpvFilter(Layout::fivePointOneBack)).find("chl_out=5.1:") != std::string_view::npos);
    assert(std::string_view(starlit_surround::mpvFilter(Layout::virtualHeadsetSevenPointOne)).find("chl_out=7.1:") != std::string_view::npos);
    assert(std::string_view(starlit_surround::mpvFilter(Layout::virtualHeadsetSevenPointOne)).find("lfe=0") != std::string_view::npos);
    assert(std::string_view(starlit_surround::mpvFilter(Layout::virtualHeadsetFivePointOneSide)).find("chl_out=5.1(side):") != std::string_view::npos);
    assert(std::string_view(starlit_surround::mpvFilter(Layout::virtualHeadsetFivePointOneBack)).find("chl_out=5.1:") != std::string_view::npos);
    assert(starlit_surround::mpvFilter(Layout::unsupported) == nullptr);
    return 0;
}
