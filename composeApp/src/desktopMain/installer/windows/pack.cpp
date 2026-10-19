// Build-time packer for the Nuvio Native setup payload.
//
//   pack.exe <app image dir> <payload.bin> [key=value ...]
//
// Writes the blocks, index and trailer described in payload.h; the build script
// appends the result to setup.exe. key=value pairs become index metadata that
// the stub reads at runtime (name, version, publisher, exe, legacyUpgradeCode).
#include "payload.h"

#include <compressapi.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cwctype>
#include <filesystem>
#include <thread>

namespace fs = std::filesystem;

namespace {

std::string utf8(const std::wstring& value) {
    if (value.empty()) return {};
    int size = WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0, nullptr, nullptr);
    std::string out(static_cast<size_t>(size), '\0');
    WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), out.data(), size, nullptr, nullptr);
    return out;
}

std::wstring lower(std::wstring value) {
    std::transform(value.begin(), value.end(), value.begin(), [](wchar_t c) { return static_cast<wchar_t>(std::towlower(c)); });
    return value;
}

[[noreturn]] void fail(const char* message, const std::wstring& detail = {}) {
    std::fprintf(stderr, "pack: %s %s\n", message, utf8(detail).c_str());
    std::exit(1);
}

struct SourceFile {
    std::wstring relative;
    fs::path absolute;
    uint64_t size = 0;
    uint64_t lastWrite = 0;
};

}  // namespace

int wmain(int argc, wchar_t** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: pack.exe <app image dir> <payload.bin> [key=value ...]\n");
        return 2;
    }
    const auto started = std::chrono::steady_clock::now();
    const fs::path root = fs::absolute(argv[1]);
    const fs::path output = fs::absolute(argv[2]);
    if (!fs::is_directory(root)) fail("app image directory not found:", root.wstring());

    payload::Index index;
    for (int i = 3; i < argc; ++i) {
        std::wstring arg = argv[i];
        size_t eq = arg.find(L'=');
        if (eq == std::wstring::npos || eq == 0) fail("metadata must be key=value:", arg);
        index.meta.emplace_back(utf8(arg.substr(0, eq)), utf8(arg.substr(eq + 1)));
    }

    std::vector<SourceFile> files;
    for (const auto& entry : fs::recursive_directory_iterator(root)) {
        std::wstring relative = entry.path().lexically_relative(root).make_preferred().wstring();
        if (entry.is_directory()) {
            index.directories.push_back(utf8(relative));
            continue;
        }
        if (!entry.is_regular_file()) continue;
        WIN32_FILE_ATTRIBUTE_DATA attributes{};
        if (!GetFileAttributesExW(entry.path().c_str(), GetFileExInfoStandard, &attributes)) {
            fail("cannot stat", entry.path().wstring());
        }
        SourceFile file;
        file.relative = relative;
        file.absolute = entry.path();
        file.size = (static_cast<uint64_t>(attributes.nFileSizeHigh) << 32) | attributes.nFileSizeLow;
        file.lastWrite = (static_cast<uint64_t>(attributes.ftLastWriteTime.dwHighDateTime) << 32) |
            attributes.ftLastWriteTime.dwLowDateTime;
        files.push_back(std::move(file));
    }
    if (files.empty()) fail("app image is empty:", root.wstring());

    // Similar files next to each other compress noticeably better (jars with jars, DLLs with DLLs).
    std::sort(files.begin(), files.end(), [](const SourceFile& a, const SourceFile& b) {
        auto extA = lower(a.absolute.extension().wstring());
        auto extB = lower(b.absolute.extension().wstring());
        if (extA != extB) return extA < extB;
        return lower(a.relative) < lower(b.relative);
    });
    std::sort(index.directories.begin(), index.directories.end());

    std::vector<uint8_t> stream;
    for (const auto& file : files) index.totalRaw += file.size;
    stream.resize(static_cast<size_t>(index.totalRaw));
    size_t cursor = 0;
    for (const auto& file : files) {
        index.files.push_back({utf8(file.relative), file.size, file.lastWrite});
        if (!payload::isSafeRelativePath(index.files.back().path)) fail("unsupported path:", file.relative);
        HANDLE handle = CreateFileW(file.absolute.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING,
                                    FILE_FLAG_SEQUENTIAL_SCAN, nullptr);
        if (handle == INVALID_HANDLE_VALUE) fail("cannot open", file.absolute.wstring());
        uint64_t remaining = file.size;
        while (remaining > 0) {
            DWORD chunk = static_cast<DWORD>(std::min<uint64_t>(remaining, 64u * 1024u * 1024u));
            DWORD read = 0;
            if (!ReadFile(handle, stream.data() + cursor, chunk, &read, nullptr) || read == 0) {
                CloseHandle(handle);
                fail("cannot read", file.absolute.wstring());
            }
            cursor += read;
            remaining -= read;
        }
        CloseHandle(handle);
    }

    const size_t blockCount = static_cast<size_t>((index.totalRaw + payload::kBlockSize - 1) / payload::kBlockSize);
    std::vector<std::vector<uint8_t>> compressed(blockCount);
    index.blocks.resize(blockCount);
    std::atomic<size_t> next{0};
    std::atomic<bool> failed{false};
    auto worker = [&]() {
        COMPRESSOR_HANDLE compressor = nullptr;
        if (!CreateCompressor(COMPRESS_ALGORITHM_LZMS, nullptr, &compressor)) { failed = true; return; }
        DWORD blockSize = payload::kBlockSize;
        SetCompressorInformation(compressor, COMPRESS_INFORMATION_CLASS_BLOCK_SIZE, &blockSize, sizeof blockSize);
        for (size_t i = next++; i < blockCount && !failed; i = next++) {
            const uint8_t* raw = stream.data() + i * payload::kBlockSize;
            size_t rawSize = static_cast<size_t>(std::min<uint64_t>(payload::kBlockSize, index.totalRaw - i * payload::kBlockSize));
            SIZE_T needed = 0;
            Compress(compressor, raw, rawSize, nullptr, 0, &needed);
            if (needed == 0) { failed = true; break; }
            compressed[i].resize(needed);
            SIZE_T written = 0;
            if (!Compress(compressor, raw, rawSize, compressed[i].data(), needed, &written)) { failed = true; break; }
            compressed[i].resize(written);
            index.blocks[i].rawSize = static_cast<uint32_t>(rawSize);
            index.blocks[i].compressedSize = static_cast<uint32_t>(written);
            index.blocks[i].crc = payload::crc32(raw, rawSize);
        }
        CloseCompressor(compressor);
    };
    const size_t threadCount = std::max<size_t>(1, std::min<size_t>(std::thread::hardware_concurrency(), blockCount));
    std::vector<std::thread> threads;
    for (size_t i = 0; i < threadCount; ++i) threads.emplace_back(worker);
    for (auto& thread : threads) thread.join();
    if (failed) fail("LZMS compression failed");

    FILE* out = _wfopen(output.c_str(), L"wb");
    if (!out) fail("cannot write", output.wstring());
    uint64_t offset = 0;
    for (size_t i = 0; i < blockCount; ++i) {
        index.blocks[i].offset = offset;
        if (std::fwrite(compressed[i].data(), 1, compressed[i].size(), out) != compressed[i].size()) fail("write failed");
        offset += compressed[i].size();
    }
    const auto indexBytes = payload::serialize(index);
    if (std::fwrite(indexBytes.data(), 1, indexBytes.size(), out) != indexBytes.size()) fail("write failed");

    payload::Trailer trailer{};
    std::memcpy(trailer.magic, payload::kMagic, sizeof trailer.magic);
    trailer.formatVersion = payload::kFormatVersion;
    trailer.indexCrc = payload::crc32(indexBytes.data(), indexBytes.size());
    trailer.indexOffset = offset;
    trailer.indexSize = indexBytes.size();
    trailer.payloadSize = offset + indexBytes.size();
    if (std::fwrite(&trailer, 1, sizeof trailer, out) != sizeof trailer) fail("write failed");
    std::fclose(out);

    const double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - started).count();
    std::printf("pack: %zu files, %.1f MB -> %.1f MB (%.0f%%) in %zu blocks, %.1fs\n", files.size(),
                index.totalRaw / 1048576.0, offset / 1048576.0, 100.0 * offset / std::max<uint64_t>(1, index.totalRaw),
                blockCount, seconds);
    return 0;
}
