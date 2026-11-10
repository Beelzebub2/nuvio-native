#pragma once

// Private, versioned stdin pipe protocol shared by the Starlit player and its
// audio-only Discord companion. Messages are little-endian, length-prefixed,
// and never place media URLs or request headers on a process command line.
#include <cstdint>
#include <cstring>
#include <string>
#include <type_traits>
#include <vector>

namespace discord_share {

constexpr uint32_t kProtocolVersion = 1;
constexpr uint32_t kMaxPacketBytes = 1u << 20;

template <typename T>
inline void appendValue(std::vector<uint8_t> &data, T value) {
    static_assert(std::is_trivially_copyable_v<T>, "Packet values must be plain data");
    auto *bytes = reinterpret_cast<const uint8_t *>(&value);
    data.insert(data.end(), bytes, bytes + sizeof(value));
}

inline void appendText(std::vector<uint8_t> &data, const std::string &value) {
    appendValue<uint32_t>(data, static_cast<uint32_t>(value.size()));
    data.insert(data.end(), value.begin(), value.end());
}

inline std::vector<uint8_t> frame(std::vector<uint8_t> payload) {
    if (payload.size() > kMaxPacketBytes) return {};
    std::vector<uint8_t> packet;
    appendValue<uint32_t>(packet, static_cast<uint32_t>(payload.size()));
    packet.insert(packet.end(), payload.begin(), payload.end());
    return packet;
}

inline std::vector<uint8_t> initial(
    uint64_t sourceWindow,
    const std::string &url,
    const std::string &audioDevice,
    const std::vector<std::string> &headers
) {
    std::vector<uint8_t> payload;
    appendValue<uint8_t>(payload, 'I');
    appendValue<uint32_t>(payload, kProtocolVersion);
    appendValue<uint64_t>(payload, sourceWindow);
    appendText(payload, url);
    appendText(payload, audioDevice);
    appendValue<uint32_t>(payload, static_cast<uint32_t>(headers.size()));
    for (const auto &header : headers) appendText(payload, header);
    return frame(std::move(payload));
}

inline std::vector<uint8_t> sync(
    int64_t positionMs,
    double speed,
    double volume,
    int64_t audioTrackId,
    bool paused
) {
    std::vector<uint8_t> payload;
    appendValue<uint8_t>(payload, 'S');
    appendValue<int64_t>(payload, positionMs);
    appendValue<double>(payload, speed);
    appendValue<double>(payload, volume);
    appendValue<int64_t>(payload, audioTrackId);
    appendValue<uint8_t>(payload, paused ? 1 : 0);
    return frame(std::move(payload));
}

inline std::vector<uint8_t> quit() {
    return frame(std::vector<uint8_t>{'Q'});
}

} // namespace discord_share
