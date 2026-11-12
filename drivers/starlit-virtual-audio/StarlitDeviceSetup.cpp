// Windows setup helper for the optional render-only Starlit audio endpoint.
// No driver is installed implicitly, and this executable never alters the
// default playback/communications device or Windows driver-signing policy.
#define UNICODE
#define _UNICODE
#include <windows.h>
#include <setupapi.h>
#include <devguid.h>
#include <cfgmgr32.h>
#include <cwchar>
#include <iostream>
#include <string>
#include <vector>

#pragma comment(lib, "setupapi.lib")

namespace {

constexpr wchar_t kHardwareId[] = L"ROOT\\StarlitVirtualAudio";
constexpr wchar_t kInstancePrefix[] = L"ROOT\\STARLITVIRTUALAUDIO\\";

class DeviceSet {
public:
    explicit DeviceSet(HDEVINFO handle) : handle_(handle) {}
    ~DeviceSet() {
        if (handle_ != INVALID_HANDLE_VALUE) SetupDiDestroyDeviceInfoList(handle_);
    }
    DeviceSet(const DeviceSet&) = delete;
    DeviceSet& operator=(const DeviceSet&) = delete;
    HDEVINFO get() const { return handle_; }
    explicit operator bool() const { return handle_ != INVALID_HANDLE_VALUE; }
private:
    HDEVINFO handle_;
};

bool isStarlitInstance(const std::wstring& instance) {
    return instance.size() > std::wcslen(kInstancePrefix) &&
        _wcsnicmp(instance.c_str(), kInstancePrefix,
                  std::wcslen(kInstancePrefix)) == 0;
}

std::vector<std::wstring> findInstances(bool& valid) {
    valid = false;
    DeviceSet devices(SetupDiGetClassDevsW(&GUID_DEVCLASS_MEDIA, nullptr, nullptr, 0));
    if (!devices) return {};
    std::vector<std::wstring> result;
    for (DWORD index = 0;; ++index) {
        SP_DEVINFO_DATA info = {sizeof(info)};
        if (!SetupDiEnumDeviceInfo(devices.get(), index, &info)) {
            if (GetLastError() != ERROR_NO_MORE_ITEMS) return {};
            break;
        }
        wchar_t instanceId[MAX_DEVICE_ID_LEN] = {};
        if (!SetupDiGetDeviceInstanceIdW(devices.get(), &info,
                                         instanceId, MAX_DEVICE_ID_LEN, nullptr)) {
            return {};
        }
        if (isStarlitInstance(instanceId)) result.emplace_back(instanceId);
    }
    valid = true;
    return result;
}

bool createRootDevice(const std::wstring& inf, std::wstring& created) {
    wchar_t className[MAX_CLASS_NAME_LEN] = {};
    GUID classGuid = {};
    if (!SetupDiGetINFClassW(inf.c_str(), &classGuid, className,
                            MAX_CLASS_NAME_LEN, nullptr) ||
        !IsEqualGUID(classGuid, GUID_DEVCLASS_MEDIA)) {
        std::wcerr << L"Invalid media-class INF: " << GetLastError() << L"\n";
        return false;
    }
    DeviceSet devices(SetupDiCreateDeviceInfoList(&classGuid, nullptr));
    if (!devices) return false;

    SP_DEVINFO_DATA info = {sizeof(info)};
    if (!SetupDiCreateDeviceInfoW(devices.get(), className, &classGuid,
                                  nullptr, nullptr, DICD_GENERATE_ID, &info)) {
        std::wcerr << L"Could not create Starlit device node: " << GetLastError() << L"\n";
        return false;
    }
    // REG_MULTI_SZ: hardware ID, NUL, terminating NUL. Do not use wildcard
    // IDs or generic ROOT\\MEDIA; ownership of removal is exact-instance only.
    constexpr wchar_t ids[] = L"ROOT\\StarlitVirtualAudio\0";
    if (!SetupDiSetDeviceRegistryPropertyW(devices.get(), &info, SPDRP_HARDWAREID,
          reinterpret_cast<const BYTE*>(ids), sizeof(ids)) ||
        !SetupDiCallClassInstaller(DIF_REGISTERDEVICE, devices.get(), &info)) {
        std::wcerr << L"Could not register Starlit device node: " << GetLastError() << L"\n";
        return false;
    }
    wchar_t instanceId[MAX_DEVICE_ID_LEN] = {};
    if (!SetupDiGetDeviceInstanceIdW(devices.get(), &info, instanceId,
                                     MAX_DEVICE_ID_LEN, nullptr) ||
        !isStarlitInstance(instanceId)) {
        std::wcerr << L"Registered node does not have the expected Starlit identity.\n";
        return false;
    }
    created = instanceId;
    return true;
}

} // namespace

int wmain(int argc, wchar_t** argv) {
    if (argc != 2 && argc != 3) {
        std::wcerr << L"Usage: StarlitDeviceSetup.exe status | create <absolute signed INF>\n";
        return 2;
    }
    const std::wstring operation = argv[1];
    bool valid = false;
    const auto existing = findInstances(valid);
    if (!valid) {
        std::wcerr << L"Cannot enumerate the system media devices.\n";
        return 3;
    }
    if (existing.size() > 1) {
        std::wcerr << L"Multiple Starlit root devices found; no changes made.\n";
        return 4;
    }
    if (operation == L"status" && argc == 2) {
        if (existing.empty()) {
            std::wcout << L"NOT_INSTALLED\n";
        } else {
            std::wcout << L"INSTANCE_ID=" << existing.front() << L"\n";
        }
        return 0;
    }
    if (operation != L"create" || argc != 3 || argv[2][0] == L'\0') return 2;
    if (!existing.empty()) {
        std::wcerr << L"Starlit device already exists; refusing duplicate registration.\n";
        return 5;
    }
    if (GetFileAttributesW(argv[2]) == INVALID_FILE_ATTRIBUTES) {
        std::wcerr << L"Driver INF does not exist.\n";
        return 6;
    }
    std::wstring created;
    if (!createRootDevice(argv[2], created)) return 7;
    std::wcout << L"CREATED_INSTANCE_ID=" << created << L"\n";
    // The separately validated signed package must then be installed through
    // the Windows-provided PnPUtil. This helper never loads unsigned code.
    return 0;
}
