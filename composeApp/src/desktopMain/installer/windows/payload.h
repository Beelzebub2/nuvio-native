// Payload format shared by the packer (pack.cpp) and the setup stub (setup.cpp).
//
// The release installer is the setup stub with the packed app image appended:
//
//   [setup stub PE] [block 0] [block 1] ... [index] [trailer]
//
// Every file of the app image is concatenated into one raw stream (grouped by
// extension so similar data compresses together) and the stream is cut into
// fixed-size blocks, each compressed independently with the Windows LZMS codec
// (Cabinet.dll, Windows 8+), so the stub needs no compression library of its own.
// The index lists metadata, directories, files (in stream order) and blocks.
#pragma once

#include <windows.h>

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace payload {

constexpr char kMagic[8] = {'N', 'V', 'S', 'E', 'T', 'U', 'P', '1'};
constexpr uint32_t kFormatVersion = 1;
constexpr uint32_t kBlockSize = 16u * 1024u * 1024u;

#pragma pack(push, 1)
struct Trailer {
    char magic[8];
    uint32_t formatVersion;
    uint32_t indexCrc;
    uint64_t payloadSize;  // blocks + index, i.e. everything between the stub and this trailer
    uint64_t indexOffset;  // relative to the start of the payload
    uint64_t indexSize;
};
#pragma pack(pop)

struct FileEntry {
    std::string path;  // UTF-8, backslash separated, relative to the install root
    uint64_t size = 0;
    uint64_t lastWrite = 0;  // FILETIME as a 64-bit value
};

struct BlockEntry {
    uint64_t offset = 0;  // relative to the start of the payload
    uint32_t compressedSize = 0;
    uint32_t rawSize = 0;
    uint32_t crc = 0;  // CRC-32 of the raw bytes
};

struct Index {
    std::vector<std::pair<std::string, std::string>> meta;
    std::vector<std::string> directories;
    std::vector<FileEntry> files;
    std::vector<BlockEntry> blocks;
    uint64_t totalRaw = 0;

    std::string get(const char* key) const {
        for (const auto& entry : meta) {
            if (entry.first == key) return entry.second;
        }
        return {};
    }
};

inline uint32_t crc32Update(uint32_t crc, const uint8_t* data, size_t size) {
    static uint32_t table[256];
    static bool ready = false;
    if (!ready) {
        for (uint32_t i = 0; i < 256; ++i) {
            uint32_t c = i;
            for (int k = 0; k < 8; ++k) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            table[i] = c;
        }
        ready = true;
    }
    crc = ~crc;
    for (size_t i = 0; i < size; ++i) crc = table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
    return ~crc;
}

inline uint32_t crc32(const uint8_t* data, size_t size) { return crc32Update(0, data, size); }

class Writer {
public:
    std::vector<uint8_t> bytes;

    void u32(uint32_t v) { raw(&v, sizeof v); }
    void u64(uint64_t v) { raw(&v, sizeof v); }
    void str(const std::string& s) {
        u32(static_cast<uint32_t>(s.size()));
        raw(s.data(), s.size());
    }
    void raw(const void* data, size_t size) {
        const auto* p = static_cast<const uint8_t*>(data);
        bytes.insert(bytes.end(), p, p + size);
    }
};

class Reader {
public:
    Reader(const uint8_t* data, size_t size) : data_(data), size_(size) {}

    bool ok() const { return ok_; }
    uint32_t u32() { uint32_t v = 0; raw(&v, sizeof v); return v; }
    uint64_t u64() { uint64_t v = 0; raw(&v, sizeof v); return v; }
    std::string str() {
        uint32_t n = u32();
        if (!ok_ || n > size_ - pos_) { ok_ = false; return {}; }
        std::string s(reinterpret_cast<const char*>(data_ + pos_), n);
        pos_ += n;
        return s;
    }

private:
    void raw(void* out, size_t n) {
        if (!ok_ || n > size_ - pos_) { ok_ = false; std::memset(out, 0, n); return; }
        std::memcpy(out, data_ + pos_, n);
        pos_ += n;
    }

    const uint8_t* data_;
    size_t size_;
    size_t pos_ = 0;
    bool ok_ = true;
};

inline std::vector<uint8_t> serialize(const Index& index) {
    Writer w;
    w.u32(static_cast<uint32_t>(index.meta.size()));
    for (const auto& entry : index.meta) { w.str(entry.first); w.str(entry.second); }
    w.u32(static_cast<uint32_t>(index.directories.size()));
    for (const auto& dir : index.directories) w.str(dir);
    w.u32(static_cast<uint32_t>(index.files.size()));
    for (const auto& file : index.files) { w.str(file.path); w.u64(file.size); w.u64(file.lastWrite); }
    w.u64(index.totalRaw);
    w.u32(static_cast<uint32_t>(index.blocks.size()));
    for (const auto& block : index.blocks) {
        w.u64(block.offset); w.u32(block.compressedSize); w.u32(block.rawSize); w.u32(block.crc);
    }
    return w.bytes;
}

inline bool deserialize(const uint8_t* data, size_t size, Index& index) {
    Reader r(data, size);
    uint32_t count = r.u32();
    for (uint32_t i = 0; i < count && r.ok(); ++i) {
        std::string key = r.str();
        std::string value = r.str();
        index.meta.emplace_back(std::move(key), std::move(value));
    }
    count = r.u32();
    for (uint32_t i = 0; i < count && r.ok(); ++i) index.directories.push_back(r.str());
    count = r.u32();
    for (uint32_t i = 0; i < count && r.ok(); ++i) {
        FileEntry file;
        file.path = r.str();
        file.size = r.u64();
        file.lastWrite = r.u64();
        index.files.push_back(std::move(file));
    }
    index.totalRaw = r.u64();
    count = r.u32();
    for (uint32_t i = 0; i < count && r.ok(); ++i) {
        BlockEntry block;
        block.offset = r.u64();
        block.compressedSize = r.u32();
        block.rawSize = r.u32();
        block.crc = r.u32();
        index.blocks.push_back(block);
    }
    return r.ok();
}

// Paths in the index come from the build machine, but the stub still refuses
// anything that could escape the install root.
inline bool isSafeRelativePath(const std::string& path) {
    if (path.empty() || path.size() > 512) return false;
    if (path[0] == '\\' || path[0] == '/' || path.find(':') != std::string::npos) return false;
    size_t start = 0;
    while (start <= path.size()) {
        size_t end = path.find('\\', start);
        if (end == std::string::npos) end = path.size();
        std::string part = path.substr(start, end - start);
        if (part.empty() || part == "." || part == ".." || part.find('/') != std::string::npos) return false;
        start = end + 1;
    }
    return true;
}

}  // namespace payload
