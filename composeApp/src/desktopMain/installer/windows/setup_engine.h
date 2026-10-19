// Install and uninstall logic for Nuvio Native setup, kept apart from the
// window and WebView2 host in setup.cpp so silent runs share the same code.
#pragma once

#include "payload.h"

#include <atomic>
#include <mutex>
#include <string>
#include <vector>

namespace setup {

// Who we are. Name, version, publisher and launcher come from the payload index
// (written by the Gradle task from compose.desktop settings); the uninstaller,
// which carries no payload, reads them back from its Apps & Features entry.
struct Product {
    std::wstring name = L"Starlit";
    std::wstring version;
    std::wstring publisher = L"Beelzebub2";
    std::wstring exe = L"Starlit.exe";
    std::wstring legacyUpgradeCode;  // the jpackage MSI's UpgradeCode, removed on upgrade
};

// --sandbox gives every user-visible artifact a separate identity (Apps &
// Features entry, shortcuts, default folder) and never removes the legacy MSI
// or app data, so the full flow can be exercised next to a real install.
// --legacy-upgrade-code points a sandbox run at a throwaway test MSI instead,
// which it may then upgrade like a real one.
struct Identity {
    Product product;
    bool sandbox = false;
    bool testLegacy = false;

    // Whether the MSI found by legacyUpgradeCode is ours to replace.
    bool ownsLegacy() const { return !sandbox || testLegacy; }
    std::wstring displayName() const;
    std::wstring displayNameFor(const std::wstring& productName) const;
    std::wstring arpKeyPath() const;
    std::wstring shortcutFileName() const;
    std::wstring uninstallerFileName() const;
    std::wstring defaultInstallDir() const;
    std::wstring startMenuShortcut() const;
    std::wstring desktopShortcut() const;
    std::wstring startMenuShortcutFor(const std::wstring& productName) const;
    std::wstring desktopShortcutFor(const std::wstring& productName) const;
};

struct Existing {
    bool ours = false;
    std::wstring version;
    std::wstring location;
    // What the earlier install was called; differs from Product after a rename
    // (Nuvio Native -> Starlit), so its folder and shortcuts can be retired.
    std::wstring name;
    std::wstring launcher;
    bool startMenu = true;
    bool desktop = true;

    bool legacy = false;  // installed by the jpackage MSI/EXE
    std::wstring legacyProductCode;
    std::wstring legacyName;
    std::wstring legacyVersion;
    std::wstring legacyLocation;

    // True when the earlier copy carries another product name than this setup.
    bool renamed(const Product& product) const;
};

struct PayloadSource {
    std::wstring exePath;
    uint64_t stubSize = 0;
    uint64_t payloadStart = 0;
    payload::Index index;
    bool present = false;
    std::wstring problem;
};

enum class Phase { Idle, Closing, Extracting, Legacy, Finishing, Removing, Done };

struct InstallRequest {
    std::wstring dir;
    bool startMenu = true;
    bool desktop = true;
};

class Engine {
public:
    Engine(Identity identity, PayloadSource source, Existing existing);

    const Identity& identity() const { return identity_; }
    const PayloadSource& source() const { return source_; }
    const Existing& existing() const { return existing_; }

    std::wstring defaultDir() const;
    // Empty when the folder can take the install, otherwise a sentence for the UI.
    std::wstring validateDir(const std::wstring& dir) const;
    bool isAppRunning(const std::wstring& dir) const;

    bool install(const InstallRequest& request);
    bool uninstall(const std::wstring& location, bool removeData);

    std::atomic<Phase> phase{Phase::Idle};
    std::atomic<double> progress{0.0};
    std::atomic<uint64_t> doneBytes{0};
    std::wstring error() const;
    std::wstring currentFile() const;

private:
    bool extract(const std::wstring& staging);
    bool writeUninstaller(const std::wstring& dir);
    void removeLegacyMsi();
    bool swapInto(const std::wstring& staging, const std::wstring& dir);
    bool mergeInto(const std::wstring& staging, const std::wstring& dir);
    void writeShortcuts(const InstallRequest& request);
    void registerUninstall(const InstallRequest& request);
    bool closeRunning(const std::vector<std::wstring>& dirs);
    void fail(const std::wstring& message);
    void setFile(const std::wstring& file);

    Identity identity_;
    PayloadSource source_;
    Existing existing_;
    mutable std::mutex mutex_;
    std::wstring error_;
    std::wstring currentFile_;
};

// Shared helpers.
void log(const wchar_t* format, ...);
std::wstring logPath();
std::wstring knownFolder(const GUID& id);
std::wstring utf16(const std::string& value);
std::string utf8(const std::wstring& value);
std::wstring normalizeDir(const std::wstring& dir);
std::wstring parentDir(const std::wstring& path);
std::wstring fileName(const std::wstring& path);
bool fileExists(const std::wstring& path);
bool dirExists(const std::wstring& path);
bool deleteTree(const std::wstring& path);
uint64_t freeBytesFor(const std::wstring& dir);
std::wstring systemMessage(DWORD code);

bool loadPayload(PayloadSource& source);
Existing detectExisting(const Identity& identity);
// Fills product fields the uninstaller cannot get from a payload.
void readInstalledProduct(Identity& identity);
bool launchApp(const Identity& identity, const std::wstring& dir);

}  // namespace setup
