#include "setup_engine.h"

#include <compressapi.h>
#include <knownfolders.h>
#include <msi.h>
#include <shellapi.h>
#include <shlobj.h>
#include <shlwapi.h>
#include <shobjidl.h>
#include <tlhelp32.h>
#include <wrl/client.h>

#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <cwctype>
#include <functional>

using Microsoft::WRL::ComPtr;

namespace setup {
namespace {

constexpr wchar_t kArpRoot[] = L"Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\";
constexpr wchar_t kArpKeyName[] = L"NuvioNative";
constexpr wchar_t kSandboxSuffix[] = L" (setup test)";
constexpr wchar_t kShortcutDescription[] = L"Browse, organize, and play media from sources you add.";

// Share of the progress bar each install phase owns.
constexpr double kExtractStart = 0.02;
constexpr double kExtractEnd = 0.90;
constexpr double kLegacyEnd = 0.96;

std::mutex gLogMutex;

std::wstring lower(std::wstring value) {
    std::transform(value.begin(), value.end(), value.begin(), [](wchar_t c) { return static_cast<wchar_t>(std::towlower(c)); });
    return value;
}

bool samePath(const std::wstring& a, const std::wstring& b) {
    return !a.empty() && lower(normalizeDir(a)) == lower(normalizeDir(b));
}

bool isInside(const std::wstring& path, const std::wstring& dir) {
    if (dir.empty()) return false;
    std::wstring prefix = lower(dir);
    if (prefix.back() != L'\\') prefix += L'\\';
    return lower(path).compare(0, prefix.size(), prefix) == 0;
}

struct ScopedHandle {
    HANDLE handle = INVALID_HANDLE_VALUE;
    explicit ScopedHandle(HANDLE h) : handle(h) {}
    ~ScopedHandle() { if (valid()) CloseHandle(handle); }
    ScopedHandle(const ScopedHandle&) = delete;
    ScopedHandle& operator=(const ScopedHandle&) = delete;
    bool valid() const { return handle != INVALID_HANDLE_VALUE && handle != nullptr; }
};

std::wstring regString(HKEY root, const std::wstring& key, const wchar_t* name) {
    DWORD size = 0;
    if (RegGetValueW(root, key.c_str(), name, RRF_RT_REG_SZ, nullptr, nullptr, &size) != ERROR_SUCCESS || size == 0) return {};
    std::wstring value(size / sizeof(wchar_t), L'\0');
    if (RegGetValueW(root, key.c_str(), name, RRF_RT_REG_SZ, nullptr, value.data(), &size) != ERROR_SUCCESS) return {};
    value.resize(wcslen(value.c_str()));
    return value;
}

bool regDword(HKEY root, const std::wstring& key, const wchar_t* name, DWORD& out) {
    DWORD size = sizeof out;
    return RegGetValueW(root, key.c_str(), name, RRF_RT_REG_DWORD, nullptr, &out, &size) == ERROR_SUCCESS;
}

void setString(HKEY key, const wchar_t* name, const std::wstring& value) {
    RegSetValueExW(key, name, 0, REG_SZ, reinterpret_cast<const BYTE*>(value.c_str()),
                   static_cast<DWORD>((value.size() + 1) * sizeof(wchar_t)));
}

void setDword(HKEY key, const wchar_t* name, DWORD value) {
    RegSetValueExW(key, name, 0, REG_DWORD, reinterpret_cast<const BYTE*>(&value), sizeof value);
}

bool ensureDir(const std::wstring& dir) {
    int result = SHCreateDirectoryExW(nullptr, dir.c_str(), nullptr);
    return result == ERROR_SUCCESS || result == ERROR_ALREADY_EXISTS || result == ERROR_FILE_EXISTS;
}

bool isDirEmpty(const std::wstring& dir) {
    return PathIsDirectoryEmptyW(dir.c_str()) != FALSE;
}

bool moveWithRetry(const std::wstring& from, const std::wstring& to) {
    for (int attempt = 0; attempt < 20; ++attempt) {
        if (MoveFileExW(from.c_str(), to.c_str(), 0)) return true;
        DWORD error = GetLastError();
        if (error != ERROR_ACCESS_DENIED && error != ERROR_SHARING_VIOLATION) return false;
        Sleep(250);
    }
    return false;
}

bool readExact(HANDLE file, uint64_t offset, void* buffer, size_t size) {
    LARGE_INTEGER position;
    position.QuadPart = static_cast<LONGLONG>(offset);
    if (!SetFilePointerEx(file, position, nullptr, FILE_BEGIN)) return false;
    auto* out = static_cast<uint8_t*>(buffer);
    while (size > 0) {
        DWORD chunk = static_cast<DWORD>(std::min<size_t>(size, 64u * 1024u * 1024u));
        DWORD read = 0;
        if (!ReadFile(file, out, chunk, &read, nullptr) || read == 0) return false;
        out += read;
        size -= read;
    }
    return true;
}

// `ignore` is the uninstaller's file name: a silent uninstall leaves it running
// in the install folder while it waits for its %TEMP% copy to finish.
std::vector<DWORD> processesUnder(const std::vector<std::wstring>& dirs, const std::wstring& ignore) {
    std::vector<DWORD> found;
    ScopedHandle snapshot(CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0));
    if (!snapshot.valid()) return found;
    PROCESSENTRY32W entry{};
    entry.dwSize = sizeof entry;
    const DWORD self = GetCurrentProcessId();
    for (BOOL more = Process32FirstW(snapshot.handle, &entry); more; more = Process32NextW(snapshot.handle, &entry)) {
        if (entry.th32ProcessID == self || entry.th32ProcessID == 0) continue;
        HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, entry.th32ProcessID);
        if (!process) continue;
        wchar_t image[MAX_PATH * 2];
        DWORD size = ARRAYSIZE(image);
        if (QueryFullProcessImageNameW(process, 0, image, &size) && lstrcmpiW(fileName(image).c_str(), ignore.c_str()) != 0) {
            for (const auto& dir : dirs) {
                if (isInside(image, dir)) {
                    found.push_back(entry.th32ProcessID);
                    break;
                }
            }
        }
        CloseHandle(process);
    }
    return found;
}

BOOL CALLBACK closeWindowsOf(HWND hwnd, LPARAM lParam) {
    const auto* pids = reinterpret_cast<const std::vector<DWORD>*>(lParam);
    DWORD pid = 0;
    GetWindowThreadProcessId(hwnd, &pid);
    if (std::find(pids->begin(), pids->end(), pid) != pids->end() && IsWindowVisible(hwnd)) {
        PostMessageW(hwnd, WM_CLOSE, 0, 0);
    }
    return TRUE;
}

bool createShortcut(const std::wstring& link, const std::wstring& target, const std::wstring& workingDir) {
    ComPtr<IShellLinkW> shellLink;
    if (FAILED(CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&shellLink)))) return false;
    shellLink->SetPath(target.c_str());
    shellLink->SetWorkingDirectory(workingDir.c_str());
    shellLink->SetIconLocation(target.c_str(), 0);
    shellLink->SetDescription(kShortcutDescription);
    ComPtr<IPersistFile> file;
    if (FAILED(shellLink.As(&file))) return false;
    ensureDir(parentDir(link));
    return SUCCEEDED(file->Save(link.c_str(), TRUE));
}

std::wstring bracedGuid(const std::wstring& guid) {
    std::wstring value = guid;
    std::transform(value.begin(), value.end(), value.begin(), [](wchar_t c) { return static_cast<wchar_t>(std::towupper(c)); });
    if (!value.empty() && value.front() != L'{') value = L"{" + value + L"}";
    return value;
}

std::wstring msiProperty(const std::wstring& productCode, const wchar_t* property) {
    wchar_t buffer[MAX_PATH * 2];
    DWORD size = ARRAYSIZE(buffer);
    if (MsiGetProductInfoW(productCode.c_str(), property, buffer, &size) != ERROR_SUCCESS) return {};
    return buffer;
}

std::wstring megabytes(uint64_t bytes) {
    return std::to_wstring((bytes + 1048575) / 1048576) + L" MB";
}

bool deleteTreeImpl(const std::wstring& path, const std::function<void()>& onFile) {
    DWORD attributes = GetFileAttributesW(path.c_str());
    if (attributes == INVALID_FILE_ATTRIBUTES) return true;
    if (!(attributes & FILE_ATTRIBUTE_DIRECTORY)) {
        SetFileAttributesW(path.c_str(), FILE_ATTRIBUTE_NORMAL);
        bool ok = DeleteFileW(path.c_str()) != FALSE;
        if (onFile) onFile();
        return ok;
    }
    bool ok = true;
    // Never follow junctions or symlinks out of the folder being removed.
    if (!(attributes & FILE_ATTRIBUTE_REPARSE_POINT)) {
        WIN32_FIND_DATAW data;
        HANDLE find = FindFirstFileExW((path + L"\\*").c_str(), FindExInfoBasic, &data, FindExSearchNameMatch, nullptr,
                                       FIND_FIRST_EX_LARGE_FETCH);
        if (find != INVALID_HANDLE_VALUE) {
            do {
                std::wstring name = data.cFileName;
                if (name == L"." || name == L"..") continue;
                ok = deleteTreeImpl(path + L"\\" + name, onFile) && ok;
            } while (FindNextFileW(find, &data));
            FindClose(find);
        }
    }
    SetFileAttributesW(path.c_str(), FILE_ATTRIBUTE_NORMAL);
    for (int attempt = 0; attempt < 5; ++attempt) {
        if (RemoveDirectoryW(path.c_str())) return ok;
        Sleep(100);
    }
    return false;
}

size_t countFiles(const std::wstring& path) {
    size_t count = 0;
    WIN32_FIND_DATAW data;
    HANDLE find = FindFirstFileExW((path + L"\\*").c_str(), FindExInfoBasic, &data, FindExSearchNameMatch, nullptr, 0);
    if (find == INVALID_HANDLE_VALUE) return 0;
    do {
        std::wstring name = data.cFileName;
        if (name == L"." || name == L"..") continue;
        if ((data.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) && !(data.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT)) {
            count += countFiles(path + L"\\" + name);
        } else {
            ++count;
        }
    } while (FindNextFileW(find, &data));
    FindClose(find);
    return count;
}

}  // namespace

// ---------------------------------------------------------------------------
// Helpers

std::wstring utf16(const std::string& value) {
    if (value.empty()) return {};
    int size = MultiByteToWideChar(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0);
    std::wstring out(static_cast<size_t>(size), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), out.data(), size);
    return out;
}

std::string utf8(const std::wstring& value) {
    if (value.empty()) return {};
    int size = WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0, nullptr, nullptr);
    std::string out(static_cast<size_t>(size), '\0');
    WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), out.data(), size, nullptr, nullptr);
    return out;
}

std::wstring logPath() {
    wchar_t temp[MAX_PATH + 1];
    DWORD length = GetTempPathW(ARRAYSIZE(temp), temp);
    std::wstring dir = length ? std::wstring(temp, length) : L".\\";
    return dir + L"Nuvio Native Setup.log";
}

void log(const wchar_t* format, ...) {
    wchar_t message[2048];
    va_list args;
    va_start(args, format);
    _vsnwprintf_s(message, _TRUNCATE, format, args);
    va_end(args);
    SYSTEMTIME now;
    GetLocalTime(&now);
    wchar_t line[2200];
    swprintf_s(line, L"%04u-%02u-%02u %02u:%02u:%02u.%03u [%lu] %s\r\n", now.wYear, now.wMonth, now.wDay, now.wHour,
               now.wMinute, now.wSecond, now.wMilliseconds, GetCurrentProcessId(), message);
    std::lock_guard<std::mutex> lock(gLogMutex);
    FILE* file = _wfopen(logPath().c_str(), L"ab");
    if (!file) return;
    std::string bytes = utf8(line);
    std::fwrite(bytes.data(), 1, bytes.size(), file);
    std::fclose(file);
}

std::wstring knownFolder(const GUID& id) {
    PWSTR path = nullptr;
    std::wstring result;
    if (SUCCEEDED(SHGetKnownFolderPath(id, KF_FLAG_DEFAULT, nullptr, &path))) result = path;
    CoTaskMemFree(path);
    return result;
}

std::wstring normalizeDir(const std::wstring& dir) {
    std::wstring value = dir;
    while (!value.empty() && (value.front() == L' ' || value.front() == L'"')) value.erase(value.begin());
    while (!value.empty() && (value.back() == L' ' || value.back() == L'"')) value.pop_back();
    if (value.empty()) return {};
    wchar_t full[MAX_PATH * 2];
    DWORD length = GetFullPathNameW(value.c_str(), ARRAYSIZE(full), full, nullptr);
    if (length == 0 || length >= ARRAYSIZE(full)) return value;
    value.assign(full, length);
    while (value.size() > 3 && (value.back() == L'\\' || value.back() == L'/')) value.pop_back();
    return value;
}

std::wstring parentDir(const std::wstring& path) {
    size_t slash = path.find_last_of(L"\\/");
    return slash == std::wstring::npos ? std::wstring() : path.substr(0, slash);
}

std::wstring fileName(const std::wstring& path) {
    size_t slash = path.find_last_of(L"\\/");
    return slash == std::wstring::npos ? path : path.substr(slash + 1);
}

bool fileExists(const std::wstring& path) {
    DWORD attributes = GetFileAttributesW(path.c_str());
    return attributes != INVALID_FILE_ATTRIBUTES && !(attributes & FILE_ATTRIBUTE_DIRECTORY);
}

bool dirExists(const std::wstring& path) {
    DWORD attributes = GetFileAttributesW(path.c_str());
    return attributes != INVALID_FILE_ATTRIBUTES && (attributes & FILE_ATTRIBUTE_DIRECTORY);
}

bool deleteTree(const std::wstring& path) {
    return deleteTreeImpl(path, nullptr);
}

uint64_t freeBytesFor(const std::wstring& dir) {
    std::wstring probe = dir;
    while (!probe.empty() && !dirExists(probe)) probe = parentDir(probe);
    ULARGE_INTEGER available{};
    if (probe.empty() || !GetDiskFreeSpaceExW((probe + L"\\").c_str(), &available, nullptr, nullptr)) return 0;
    return available.QuadPart;
}

std::wstring systemMessage(DWORD code) {
    wchar_t* buffer = nullptr;
    DWORD length = FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
                                  nullptr, code, 0, reinterpret_cast<LPWSTR>(&buffer), 0, nullptr);
    std::wstring message = length ? std::wstring(buffer, length) : L"Error " + std::to_wstring(code);
    LocalFree(buffer);
    while (!message.empty() && (message.back() == L'\n' || message.back() == L'\r' || message.back() == L' ')) message.pop_back();
    return message;
}

// ---------------------------------------------------------------------------
// Identity

std::wstring Identity::displayName() const {
    return sandbox ? product.name + kSandboxSuffix : product.name;
}

std::wstring Identity::arpKeyPath() const {
    return std::wstring(kArpRoot) + kArpKeyName + (sandbox ? L".SetupTest" : L"");
}

std::wstring Identity::shortcutFileName() const {
    return displayName() + L".lnk";
}

std::wstring Identity::uninstallerFileName() const {
    return L"Uninstall " + product.name + L".exe";
}

std::wstring Identity::defaultInstallDir() const {
    return knownFolder(FOLDERID_LocalAppData) + L"\\" + displayName();
}

std::wstring Identity::startMenuShortcut() const {
    return knownFolder(FOLDERID_Programs) + L"\\" + shortcutFileName();
}

std::wstring Identity::desktopShortcut() const {
    return knownFolder(FOLDERID_Desktop) + L"\\" + shortcutFileName();
}

// ---------------------------------------------------------------------------
// Payload and existing installs

bool loadPayload(PayloadSource& source) {
    wchar_t path[MAX_PATH * 2];
    DWORD length = GetModuleFileNameW(nullptr, path, ARRAYSIZE(path));
    source.exePath.assign(path, length);
    const wchar_t* damaged = L"This installer file is incomplete or damaged. Download it again.";

    ScopedHandle file(CreateFileW(source.exePath.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_DELETE, nullptr,
                                  OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr));
    LARGE_INTEGER size{};
    if (!file.valid() || !GetFileSizeEx(file.handle, &size)) {
        source.problem = L"Setup couldn't read its own file: " + systemMessage(GetLastError());
        return false;
    }
    const uint64_t fileSize = static_cast<uint64_t>(size.QuadPart);
    source.stubSize = fileSize;
    payload::Trailer trailer{};
    if (fileSize < sizeof trailer || !readExact(file.handle, fileSize - sizeof trailer, &trailer, sizeof trailer) ||
        std::memcmp(trailer.magic, payload::kMagic, sizeof trailer.magic) != 0) {
        return true;  // A bare stub: the copy in the install folder that acts as the uninstaller.
    }
    if (trailer.formatVersion != payload::kFormatVersion || trailer.payloadSize > fileSize - sizeof trailer ||
        trailer.indexOffset > trailer.payloadSize || trailer.indexSize > trailer.payloadSize - trailer.indexOffset ||
        trailer.indexSize > 64u * 1024u * 1024u) {
        source.problem = damaged;
        return false;
    }
    source.payloadStart = fileSize - sizeof trailer - trailer.payloadSize;
    source.stubSize = source.payloadStart;

    std::vector<uint8_t> indexBytes(static_cast<size_t>(trailer.indexSize));
    if (!readExact(file.handle, source.payloadStart + trailer.indexOffset, indexBytes.data(), indexBytes.size()) ||
        payload::crc32(indexBytes.data(), indexBytes.size()) != trailer.indexCrc ||
        !payload::deserialize(indexBytes.data(), indexBytes.size(), source.index)) {
        source.problem = damaged;
        return false;
    }
    uint64_t fileTotal = 0;
    for (const auto& entry : source.index.files) {
        if (!payload::isSafeRelativePath(entry.path)) { source.problem = damaged; return false; }
        fileTotal += entry.size;
    }
    for (const auto& dir : source.index.directories) {
        if (!payload::isSafeRelativePath(dir)) { source.problem = damaged; return false; }
    }
    uint64_t blockTotal = 0;
    for (const auto& block : source.index.blocks) {
        if (block.offset > trailer.indexOffset || block.compressedSize > trailer.indexOffset - block.offset ||
            block.rawSize > 64u * 1024u * 1024u) {
            source.problem = damaged;
            return false;
        }
        blockTotal += block.rawSize;
    }
    if (fileTotal != source.index.totalRaw || blockTotal != source.index.totalRaw || source.index.files.empty()) {
        source.problem = damaged;
        return false;
    }
    source.present = true;
    return true;
}

Existing detectExisting(const Identity& identity) {
    Existing existing;
    const std::wstring key = identity.arpKeyPath();
    existing.location = normalizeDir(regString(HKEY_CURRENT_USER, key, L"InstallLocation"));
    if (!existing.location.empty()) {
        existing.ours = true;
        existing.version = regString(HKEY_CURRENT_USER, key, L"DisplayVersion");
        DWORD value = 0;
        if (regDword(HKEY_CURRENT_USER, key, L"NuvioStartMenuShortcut", value)) existing.startMenu = value != 0;
        if (regDword(HKEY_CURRENT_USER, key, L"NuvioDesktopShortcut", value)) existing.desktop = value != 0;
        log(L"Found %s %s in %s", identity.displayName().c_str(), existing.version.c_str(), existing.location.c_str());
    }

    if (!identity.product.legacyUpgradeCode.empty()) {
        const std::wstring upgradeCode = bracedGuid(identity.product.legacyUpgradeCode);
        wchar_t productCode[39];
        if (MsiEnumRelatedProductsW(upgradeCode.c_str(), 0, 0, productCode) == ERROR_SUCCESS) {
            existing.legacy = true;
            existing.legacyProductCode = productCode;
            existing.legacyVersion = msiProperty(productCode, INSTALLPROPERTY_VERSIONSTRING);
            existing.legacyLocation = normalizeDir(msiProperty(productCode, INSTALLPROPERTY_INSTALLLOCATION));
            log(L"Found MSI install %s (%s) in %s", existing.legacyProductCode.c_str(), existing.legacyVersion.c_str(),
                existing.legacyLocation.c_str());
            if (!existing.ours && !identity.sandbox) {
                // jpackage named its desktop shortcut after the product; keep the user's choice.
                existing.desktop = fileExists(knownFolder(FOLDERID_Desktop) + L"\\" + identity.product.name + L".lnk");
            }
        }
    }
    return existing;
}

void readInstalledProduct(Identity& identity) {
    const std::wstring key = identity.arpKeyPath();
    auto read = [&](const wchar_t* name, std::wstring& target) {
        std::wstring value = regString(HKEY_CURRENT_USER, key, name);
        if (!value.empty()) target = value;
    };
    read(L"NuvioProductName", identity.product.name);
    read(L"DisplayVersion", identity.product.version);
    read(L"Publisher", identity.product.publisher);
    read(L"NuvioLauncher", identity.product.exe);
}

bool launchApp(const Identity& identity, const std::wstring& dir) {
    const std::wstring exe = dir + L"\\" + identity.product.exe;
    SHELLEXECUTEINFOW info{};
    info.cbSize = sizeof info;
    info.fMask = SEE_MASK_NOASYNC;
    info.lpVerb = L"open";
    info.lpFile = exe.c_str();
    info.lpDirectory = dir.c_str();
    info.nShow = SW_SHOWNORMAL;
    bool ok = ShellExecuteExW(&info) != FALSE;
    log(L"Launch %s: %s", exe.c_str(), ok ? L"ok" : systemMessage(GetLastError()).c_str());
    return ok;
}

// ---------------------------------------------------------------------------
// Engine

Engine::Engine(Identity identity, PayloadSource source, Existing existing)
    : identity_(std::move(identity)), source_(std::move(source)), existing_(std::move(existing)) {}

std::wstring Engine::error() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return error_;
}

std::wstring Engine::currentFile() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return currentFile_;
}

void Engine::fail(const std::wstring& message) {
    log(L"Failed: %s", message.c_str());
    std::lock_guard<std::mutex> lock(mutex_);
    error_ = message;
}

void Engine::setFile(const std::wstring& file) {
    std::lock_guard<std::mutex> lock(mutex_);
    currentFile_ = file;
}

std::wstring Engine::defaultDir() const {
    if (existing_.ours) return existing_.location;
    // A sandbox run must never pick the real install's folder.
    if (existing_.legacy && !identity_.sandbox && !existing_.legacyLocation.empty()) return existing_.legacyLocation;
    return identity_.defaultInstallDir();
}

std::wstring Engine::validateDir(const std::wstring& raw) const {
    const std::wstring dir = normalizeDir(raw);
    if (dir.size() < 3 || PathIsRelativeW(dir.c_str())) {
        return L"Enter a full folder path, such as " + identity_.defaultInstallDir() + L".";
    }
    if (PathIsRootW(dir.c_str())) return L"Choose a folder inside the drive, not the drive itself.";
    if (fileExists(dir)) return L"A file already has this name. Choose a different folder.";
    if (dirExists(dir) && !isDirEmpty(dir) && !fileExists(dir + L"\\" + identity_.product.exe)) {
        return L"This folder already has other files in it. Choose an empty or new folder.";
    }
    if (identity_.sandbox && existing_.legacy && samePath(dir, existing_.legacyLocation)) {
        return L"Sandbox runs can't use the folder of the real install.";
    }
    const uint64_t needed = source_.index.totalRaw + 64ull * 1024 * 1024;
    if (source_.present && freeBytesFor(dir) < needed) {
        return L"There isn't enough space on this drive. Nuvio needs " + megabytes(needed) + L".";
    }
    return {};
}

bool Engine::isAppRunning(const std::wstring& dir) const {
    std::vector<std::wstring> dirs{normalizeDir(dir)};
    if (existing_.ours) dirs.push_back(existing_.location);
    if (existing_.legacy && !identity_.sandbox && !existing_.legacyLocation.empty()) dirs.push_back(existing_.legacyLocation);
    return !processesUnder(dirs, identity_.uninstallerFileName()).empty();
}

bool Engine::closeRunning(const std::vector<std::wstring>& dirs) {
    auto pids = processesUnder(dirs, identity_.uninstallerFileName());
    if (pids.empty()) return true;
    log(L"Closing %zu running process(es)", pids.size());
    EnumWindows(closeWindowsOf, reinterpret_cast<LPARAM>(&pids));
    for (int attempt = 0; attempt < 40; ++attempt) {
        Sleep(200);
        if (processesUnder(dirs, identity_.uninstallerFileName()).empty()) return true;
    }
    for (DWORD pid : processesUnder(dirs, identity_.uninstallerFileName())) {
        HANDLE process = OpenProcess(PROCESS_TERMINATE | SYNCHRONIZE, FALSE, pid);
        if (!process) continue;
        log(L"Terminating process %lu", pid);
        TerminateProcess(process, 1);
        WaitForSingleObject(process, 3000);
        CloseHandle(process);
    }
    return processesUnder(dirs, identity_.uninstallerFileName()).empty();
}

bool Engine::install(const InstallRequest& request) {
    const std::wstring dir = normalizeDir(request.dir);
    log(L"Install %s %s to %s (start menu %d, desktop %d, sandbox %d)", identity_.product.name.c_str(),
        identity_.product.version.c_str(), dir.c_str(), request.startMenu, request.desktop, identity_.sandbox);
    if (!source_.present) {
        fail(source_.problem.empty() ? L"This installer doesn't contain the app. Download it again." : source_.problem);
        return false;
    }
    if (auto problem = validateDir(dir); !problem.empty()) {
        fail(problem);
        return false;
    }

    phase = Phase::Closing;
    progress = 0.0;
    std::vector<std::wstring> runningDirs{dir};
    if (existing_.ours && !samePath(existing_.location, dir)) runningDirs.push_back(existing_.location);
    if (existing_.legacy && !identity_.sandbox && !existing_.legacyLocation.empty()) runningDirs.push_back(existing_.legacyLocation);
    if (!closeRunning(runningDirs)) {
        fail(L"Nuvio is still running and couldn't be closed. Close it, then select Try again.");
        return false;
    }

    // Unpack next to the target first so a failed or cancelled run never
    // leaves a half-written app behind; the old version is only touched once
    // every new file is on disk.
    const std::wstring staging = dir + L".setup-new";
    if (dirExists(staging) && !deleteTree(staging)) {
        fail(L"Setup couldn't clear " + staging + L". Delete that folder, then select Try again.");
        return false;
    }
    phase = Phase::Extracting;
    progress = kExtractStart;
    if (!extract(staging) || !writeUninstaller(staging)) {
        deleteTree(staging);
        return false;
    }

    progress = kExtractEnd;
    if (existing_.legacy && !identity_.sandbox) {
        phase = Phase::Legacy;
        removeLegacyMsi();
    } else if (existing_.legacy) {
        log(L"Sandbox: leaving MSI install %s in place", existing_.legacyProductCode.c_str());
    }
    progress = kLegacyEnd;

    phase = Phase::Finishing;
    if (!swapInto(staging, dir)) {
        deleteTree(staging);
        return false;
    }
    if (existing_.ours && !samePath(existing_.location, dir) &&
        fileExists(existing_.location + L"\\" + identity_.product.exe)) {
        log(L"Removing previous location %s", existing_.location.c_str());
        deleteTree(existing_.location);
    }
    InstallRequest applied = request;
    applied.dir = dir;
    writeShortcuts(applied);
    progress = 0.98;
    registerUninstall(applied);
    SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, nullptr, nullptr);
    progress = 1.0;
    phase = Phase::Done;
    log(L"Install finished");
    return true;
}

bool Engine::extract(const std::wstring& staging) {
    const auto& index = source_.index;
    if (!ensureDir(staging)) {
        DWORD code = GetLastError();
        fail(code == ERROR_ACCESS_DENIED
                 ? L"Setup can't write to " + parentDir(staging) + L". Choose a folder in your user profile."
                 : L"Setup couldn't create " + staging + L": " + systemMessage(code));
        return false;
    }
    for (const auto& dir : index.directories) ensureDir(staging + L"\\" + utf16(dir));

    ScopedHandle in(CreateFileW(source_.exePath.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_DELETE, nullptr,
                                OPEN_EXISTING, FILE_FLAG_SEQUENTIAL_SCAN, nullptr));
    DECOMPRESSOR_HANDLE decompressor = nullptr;
    if (!in.valid() || !CreateDecompressor(COMPRESS_ALGORITHM_LZMS, nullptr, &decompressor)) {
        fail(L"Setup couldn't open its app files: " + systemMessage(GetLastError()));
        return false;
    }
    struct DecompressorCloser {
        DECOMPRESSOR_HANDLE handle;
        ~DecompressorCloser() { CloseDecompressor(handle); }
    } closer{decompressor};

    const wchar_t* damaged = L"This installer file is damaged. Download it again.";
    std::vector<uint8_t> compressed;
    std::vector<uint8_t> raw;
    size_t nextFile = 0;
    size_t openFile = 0;
    uint64_t remaining = 0;
    uint64_t done = 0;
    HANDLE out = INVALID_HANDLE_VALUE;

    auto closeOut = [&]() {
        if (out == INVALID_HANDLE_VALUE) return;
        const uint64_t stamp = index.files[openFile].lastWrite;
        FILETIME time{static_cast<DWORD>(stamp & 0xFFFFFFFFu), static_cast<DWORD>(stamp >> 32)};
        if (stamp) SetFileTime(out, nullptr, nullptr, &time);
        CloseHandle(out);
        out = INVALID_HANDLE_VALUE;
    };
    // Leaves `out` on a file that still needs bytes, creating empty files on the
    // way; `out` stays closed once every file has been written.
    auto advance = [&]() -> bool {
        while (remaining == 0) {
            closeOut();
            if (nextFile >= index.files.size()) return true;
            openFile = nextFile++;
            const std::wstring relative = utf16(index.files[openFile].path);
            const std::wstring path = staging + L"\\" + relative;
            ensureDir(parentDir(path));
            out = CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                              FILE_ATTRIBUTE_NORMAL | FILE_FLAG_SEQUENTIAL_SCAN, nullptr);
            if (out == INVALID_HANDLE_VALUE) {
                fail(L"Setup couldn't create " + path + L": " + systemMessage(GetLastError()));
                return false;
            }
            setFile(relative);
            remaining = index.files[openFile].size;
        }
        return true;
    };

    bool ok = true;
    for (const auto& block : index.blocks) {
        compressed.resize(block.compressedSize);
        if (raw.size() < block.rawSize) raw.resize(block.rawSize);
        SIZE_T produced = 0;
        if (!readExact(in.handle, source_.payloadStart + block.offset, compressed.data(), compressed.size()) ||
            !Decompress(decompressor, compressed.data(), compressed.size(), raw.data(), block.rawSize, &produced) ||
            produced != block.rawSize || payload::crc32(raw.data(), produced) != block.crc) {
            fail(damaged);
            ok = false;
            break;
        }
        size_t position = 0;
        while (ok && position < produced) {
            if (!advance()) { ok = false; break; }
            if (out == INVALID_HANDLE_VALUE) { fail(damaged); ok = false; break; }
            const DWORD chunk = static_cast<DWORD>(std::min<uint64_t>(remaining, produced - position));
            DWORD written = 0;
            if (!WriteFile(out, raw.data() + position, chunk, &written, nullptr) || written != chunk) {
                fail(L"Setup couldn't write " + utf16(index.files[openFile].path) + L": " + systemMessage(GetLastError()));
                ok = false;
                break;
            }
            position += chunk;
            remaining -= chunk;
            done += chunk;
            doneBytes = done;
            progress = kExtractStart + (kExtractEnd - kExtractStart) * static_cast<double>(done) /
                static_cast<double>(std::max<uint64_t>(1, index.totalRaw));
        }
        if (!ok) break;
    }
    if (ok && !advance()) ok = false;
    if (ok && (remaining != 0 || nextFile < index.files.size())) {
        fail(damaged);
        ok = false;
    }
    if (out != INVALID_HANDLE_VALUE) CloseHandle(out);
    if (ok) log(L"Extracted %zu files (%llu bytes) to %s", index.files.size(), done, staging.c_str());
    return ok;
}

bool Engine::writeUninstaller(const std::wstring& dir) {
    const std::wstring target = dir + L"\\" + identity_.uninstallerFileName();
    ScopedHandle in(CreateFileW(source_.exePath.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_DELETE, nullptr,
                                OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr));
    ScopedHandle out(CreateFileW(target.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr));
    std::vector<uint8_t> stub(static_cast<size_t>(source_.stubSize));
    DWORD written = 0;
    if (!in.valid() || !out.valid() || !readExact(in.handle, 0, stub.data(), stub.size()) ||
        !WriteFile(out.handle, stub.data(), static_cast<DWORD>(stub.size()), &written, nullptr) || written != stub.size()) {
        fail(L"Setup couldn't write the uninstaller: " + systemMessage(GetLastError()));
        return false;
    }
    return true;
}

void Engine::removeLegacyMsi() {
    log(L"Removing MSI install %s", existing_.legacyProductCode.c_str());
    MsiSetInternalUI(INSTALLUILEVEL_NONE, nullptr);
    UINT result = MsiConfigureProductExW(existing_.legacyProductCode.c_str(), INSTALLLEVEL_DEFAULT, INSTALLSTATE_ABSENT,
                                         L"REBOOT=ReallySuppress");
    // A failed removal leaves the old Apps & Features entry, but the new files
    // still go in, so the update itself carries on.
    log(L"MSI removal finished with %u (%s)", result, systemMessage(result).c_str());
}

bool Engine::swapInto(const std::wstring& staging, const std::wstring& dir) {
    const std::wstring old = dir + L".setup-old";
    if (dirExists(old)) deleteTree(old);
    bool movedOld = false;
    if (dirExists(dir)) {
        if (!moveWithRetry(dir, old)) {
            fail(L"Some files in " + dir + L" are in use, so setup couldn't replace them. Close Nuvio, then select Try again.");
            return false;
        }
        movedOld = true;
    }
    ensureDir(parentDir(dir));
    if (!moveWithRetry(staging, dir)) {
        DWORD code = GetLastError();
        if (movedOld) MoveFileExW(old.c_str(), dir.c_str(), 0);
        fail(L"Setup couldn't move the new files into " + dir + L": " + systemMessage(code));
        return false;
    }
    if (movedOld && !deleteTree(old)) log(L"Couldn't fully remove %s", old.c_str());
    return true;
}

void Engine::writeShortcuts(const InstallRequest& request) {
    const std::wstring target = request.dir + L"\\" + identity_.product.exe;
    auto apply = [&](bool wanted, const std::wstring& link) {
        if (link.empty() || parentDir(link).empty()) return;
        if (wanted) {
            bool ok = createShortcut(link, target, request.dir);
            log(L"Shortcut %s: %s", link.c_str(), ok ? L"created" : L"failed");
        } else if (fileExists(link)) {
            DeleteFileW(link.c_str());
            log(L"Shortcut %s: removed", link.c_str());
        }
    };
    apply(request.startMenu, identity_.startMenuShortcut());
    apply(request.desktop, identity_.desktopShortcut());
}

void Engine::registerUninstall(const InstallRequest& request) {
    HKEY key = nullptr;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, identity_.arpKeyPath().c_str(), 0, nullptr, 0, KEY_SET_VALUE, nullptr, &key,
                        nullptr) != ERROR_SUCCESS) {
        log(L"Couldn't create the Apps & Features entry");
        return;
    }
    const std::wstring uninstaller = L"\"" + request.dir + L"\\" + identity_.uninstallerFileName() + L"\" --uninstall" +
        (identity_.sandbox ? L" --sandbox" : L"");
    SYSTEMTIME now;
    GetLocalTime(&now);
    wchar_t date[16];
    swprintf_s(date, L"%04u%02u%02u", now.wYear, now.wMonth, now.wDay);

    setString(key, L"DisplayName", identity_.displayName());
    setString(key, L"DisplayVersion", identity_.product.version);
    setString(key, L"Publisher", identity_.product.publisher);
    setString(key, L"DisplayIcon", request.dir + L"\\" + identity_.product.exe + L",0");
    setString(key, L"InstallLocation", request.dir);
    setString(key, L"UninstallString", uninstaller);
    setString(key, L"QuietUninstallString", uninstaller + L" --silent");
    setString(key, L"InstallDate", date);
    setDword(key, L"EstimatedSize", static_cast<DWORD>(source_.index.totalRaw / 1024));
    setDword(key, L"NoModify", 1);
    setDword(key, L"NoRepair", 1);
    setString(key, L"NuvioProductName", identity_.product.name);
    setString(key, L"NuvioLauncher", identity_.product.exe);
    setDword(key, L"NuvioStartMenuShortcut", request.startMenu ? 1 : 0);
    setDword(key, L"NuvioDesktopShortcut", request.desktop ? 1 : 0);
    RegCloseKey(key);
}

bool Engine::uninstall(const std::wstring& location, bool removeData) {
    const std::wstring dir = normalizeDir(location);
    log(L"Uninstall %s from %s (remove data %d, sandbox %d)", identity_.displayName().c_str(), dir.c_str(), removeData,
        identity_.sandbox);
    phase = Phase::Closing;
    progress = 0.0;
    const bool owned = !dir.empty() && (fileExists(dir + L"\\" + identity_.product.exe) ||
                                        fileExists(dir + L"\\" + identity_.uninstallerFileName()));
    if (!dir.empty() && dirExists(dir) && !owned) {
        fail(dir + L" doesn't look like a Nuvio install folder, so setup left it alone.");
        return false;
    }
    if (owned && !closeRunning({dir})) {
        fail(L"Nuvio is still running and couldn't be closed. Close it, then select Try again.");
        return false;
    }

    phase = Phase::Removing;
    progress = 0.1;
    for (const auto& link : {identity_.startMenuShortcut(), identity_.desktopShortcut()}) {
        if (fileExists(link)) DeleteFileW(link.c_str());
    }
    if (owned) {
        const size_t total = std::max<size_t>(1, countFiles(dir));
        size_t removed = 0;
        bool complete = deleteTreeImpl(dir, [&]() {
            ++removed;
            progress = 0.1 + 0.8 * static_cast<double>(std::min(removed, total)) / static_cast<double>(total);
        });
        // The uninstaller that started this run may still be locked; setup.cpp
        // schedules a cleanup of whatever is left once it exits.
        if (!complete) log(L"Some files in %s are still in use", dir.c_str());
    }
    progress = 0.92;
    RegDeleteTreeW(HKEY_CURRENT_USER, identity_.arpKeyPath().c_str());
    if (removeData) {
        if (identity_.sandbox) {
            log(L"Sandbox: keeping app data");
        } else {
            // Matches DesktopStorage: settings in Roaming, cache in Local.
            for (const auto& base : {knownFolder(FOLDERID_RoamingAppData), knownFolder(FOLDERID_LocalAppData)}) {
                if (base.empty()) continue;
                const std::wstring data = base + L"\\NuvioNative";
                log(L"Removing app data %s: %s", data.c_str(), deleteTree(data) ? L"ok" : L"incomplete");
            }
        }
    }
    SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, nullptr, nullptr);
    progress = 1.0;
    phase = Phase::Done;
    log(L"Uninstall finished");
    return true;
}

}  // namespace setup
