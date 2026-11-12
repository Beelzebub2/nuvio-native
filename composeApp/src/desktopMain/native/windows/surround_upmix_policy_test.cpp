#include "surround_upmix_policy.h"

#include <cassert>
#include <string_view>

int main() {
    using starlit_surround::Layout;
    using starlit_surround::configuredLayout;
    using starlit_surround::acceptsStereoInput;
    assert(configuredLayout(6, starlit_surround::kFivePointOneSideMask) == Layout::fivePointOneSide);
    assert(configuredLayout(6, starlit_surround::kFivePointOneBackMask) == Layout::fivePointOneBack);
    assert(configuredLayout(8, starlit_surround::kSevenPointOneMask) == Layout::unsupported);
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
    assert(starlit_surround::mpvOutputLayout(Layout::unsupported).empty());
    assert(std::string_view(starlit_surround::mpvFilter(Layout::fivePointOneSide)).find("lfe=0") != std::string_view::npos);
    assert(std::string_view(starlit_surround::mpvFilter(Layout::fivePointOneBack)).find("chl_out=5.1:") != std::string_view::npos);
    assert(starlit_surround::mpvFilter(Layout::unsupported) == nullptr);
    return 0;
}
