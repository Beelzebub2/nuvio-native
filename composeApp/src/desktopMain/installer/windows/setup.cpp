// Nuvio Native setup: hosts the installer UI (ui/) in a borderless WebView2
// window and drives setup_engine for installs, updates and uninstalls.
//
//   setup.exe                         interactive install or update
//   setup.exe --silent [--dir <path>] [--no-desktop-shortcut] [--no-start-menu-shortcut] [--launch]
//   setup.exe --uninstall [--silent] [--remove-data]
//   --sandbox [--legacy-upgrade-code <guid>]   separate test identity, see setup_engine.h
//   --devtools                        enable WebView2 DevTools and reload keys
#include "resource.h"
#include "setup_engine.h"

#include <dwmapi.h>
#include <knownfolders.h>
#include <shellapi.h>
#include <shellscalingapi.h>
#include <shlobj.h>
#include <shlwapi.h>
#include <shobjidl.h>
#include <wrl.h>
#include <WebView2.h>

#include <cmath>
#include <memory>
#include <string>
#include <thread>
#include <vector>

using Microsoft::WRL::Callback;
using Microsoft::WRL::ComPtr;
using namespace setup;

namespace {

constexpr wchar_t kWindowClass[] = L"NuvioNativeSetupWindow";
constexpr wchar_t kUiOrigin[] = L"https://setup.nuvio/";
constexpr wchar_t kWebView2Download[] = L"https://go.microsoft.com/fwlink/p/?LinkId=2124703";
constexpr int kWidth = 760;
constexpr int kHeight = 480;
constexpr UINT kFinishedMessage = WM_APP + 1;
constexpr UINT kDragMessage = WM_APP + 2;
constexpr UINT_PTR kProgressTimer = 1;
constexpr UINT_PTR kShowTimer = 2;
constexpr COLORREF kBackground = RGB(13, 13, 13);

struct Args {
    bool silent = false;
    bool uninstall = false;
    bool sandbox = false;
    bool fromTemp = false;
    bool launch = false;
    bool devtools = false;
    bool removeData = false;
    bool noDesktop = false;
    bool noStartMenu = false;
    std::wstring dir;
    std::wstring legacyUpgradeCode;
};

struct UiResource {
    const wchar_t* path;
    int id;
    const wchar_t* mime;
};

constexpr UiResource kUiResources[] = {
    {L"index.html", IDR_UI_HTML, L"text/html; charset=utf-8"},
    {L"setup.css", IDR_UI_CSS, L"text/css; charset=utf-8"},
    {L"setup.js", IDR_UI_JS, L"text/javascript; charset=utf-8"},
    {L"logo.png", IDR_UI_LOGO, L"image/png"},
    {L"fonts/regular.ttf", IDR_FONT_REGULAR, L"font/ttf"},
    {L"fonts/semibold.ttf", IDR_FONT_SEMIBOLD, L"font/ttf"},
    {L"fonts/bold.ttf", IDR_FONT_BOLD, L"font/ttf"},
};

Args gArgs;
std::unique_ptr<Engine> gEngine;
HWND gWindow = nullptr;
ComPtr<ICoreWebView2Environment> gEnvironment;
ComPtr<ICoreWebView2Controller> gController;
ComPtr<ICoreWebView2> gWebView;
ComPtr<ITaskbarList3> gTaskbar;
std::thread gWorker;
std::wstring gWebViewData;
std::wstring gMode;
std::wstring gDir;
std::wstring gLastProgress;
bool gBusy = false;
bool gShown = false;
bool gUninstalled = false;

// ---------------------------------------------------------------------------
// Messages to the page

std::wstring jsonString(const std::wstring& value) {
    std::wstring out = L"\"";
    for (wchar_t c : value) {
        switch (c) {
            case L'"': out += L"\\\""; break;
            case L'\\': out += L"\\\\"; break;
            case L'\n': out += L"\\n"; break;
            case L'\r': out += L"\\r"; break;
            case L'\t': out += L"\\t"; break;
            default:
                if (c < 0x20) {
                    wchar_t escaped[8];
                    swprintf_s(escaped, L"\\u%04x", static_cast<unsigned>(c));
                    out += escaped;
                } else {
                    out += c;
                }
        }
    }
    return out + L"\"";
}

class Json {
public:
    explicit Json(const wchar_t* type) { str(L"type", type); }
    Json& str(const wchar_t* key, const std::wstring& value) { return add(key, jsonString(value)); }
    Json& flag(const wchar_t* key, bool value) { return add(key, value ? L"true" : L"false"); }
    Json& number(const wchar_t* key, double value) {
        wchar_t buffer[64];
        swprintf_s(buffer, std::floor(value) == value ? L"%.0f" : L"%.4f", value);
        return add(key, buffer);
    }
    std::wstring text() const { return L"{" + body_ + L"}"; }

private:
    Json& add(const wchar_t* key, const std::wstring& value) {
        if (!body_.empty()) body_ += L",";
        body_ += jsonString(key) + L":" + value;
        return *this;
    }
    std::wstring body_;
};

void post(const Json& message) {
    if (gWebView) gWebView->PostWebMessageAsJson(message.text().c_str());
}

const wchar_t* phaseName(Phase phase) {
    switch (phase) {
        case Phase::Closing: return L"closing";
        case Phase::Extracting: return L"extracting";
        case Phase::Legacy: return L"legacy";
        case Phase::Finishing: return L"finishing";
        case Phase::Removing: return L"removing";
        case Phase::Done: return L"done";
        default: return L"idle";
    }
}

void sendInit() {
    const auto& identity = gEngine->identity();
    const auto& existing = gEngine->existing();
    std::wstring previous;
    if (existing.ours) previous = existing.version;
    else if (existing.legacy && identity.ownsLegacy()) previous = existing.legacyVersion;
    std::wstring problem = gEngine->source().problem;
    if (problem.empty() && gMode != L"uninstall") problem = gEngine->validateDir(gDir);
    post(Json(L"init")
             .str(L"mode", gMode)
             .str(L"name", identity.displayName())
             .str(L"version", identity.product.version)
             .str(L"previousVersion", previous)
             .str(L"dir", gDir)
             .flag(L"startMenu", existing.startMenu)
             .flag(L"desktop", existing.desktop)
             .number(L"required", static_cast<double>(gEngine->source().index.totalRaw))
             .number(L"free", static_cast<double>(freeBytesFor(gDir)))
             .flag(L"running", gEngine->isAppRunning(gDir))
             .flag(L"sandbox", identity.sandbox)
             .flag(L"damaged", !gEngine->source().problem.empty())
             .str(L"problem", problem));
}

void sendDir(const std::wstring& dir) {
    gDir = normalizeDir(dir);
    post(Json(L"dir")
             .str(L"dir", gDir)
             .number(L"free", static_cast<double>(freeBytesFor(gDir)))
             .str(L"problem", gEngine->validateDir(gDir)));
}

void sendProgress(bool force) {
    const Phase phase = gEngine->phase.load();
    const double progress = gEngine->progress.load();
    Json message(L"progress");
    message.str(L"phase", phaseName(phase))
        .number(L"value", std::round(progress * 10000.0) / 10000.0)
        .number(L"done", static_cast<double>(gEngine->doneBytes.load()))
        .number(L"total", static_cast<double>(gEngine->source().index.totalRaw))
        .str(L"file", gEngine->currentFile());
    std::wstring text = message.text();
    if (!force && text == gLastProgress) return;
    gLastProgress = text;
    if (gWebView) gWebView->PostWebMessageAsJson(text.c_str());
    if (gTaskbar) gTaskbar->SetProgressValue(gWindow, static_cast<ULONGLONG>(progress * 1000.0), 1000);
}

// ---------------------------------------------------------------------------
// Actions from the page

void startWork(bool uninstall, bool startMenu, bool desktop, bool removeData) {
    if (gBusy) return;
    if (gWorker.joinable()) gWorker.join();
    gBusy = true;
    gLastProgress.clear();
    if (gTaskbar) gTaskbar->SetProgressState(gWindow, TBPF_NORMAL);
    const std::wstring dir = gDir;
    gWorker = std::thread([=]() {
        CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
        bool ok = uninstall ? gEngine->uninstall(dir, removeData) : gEngine->install({dir, startMenu, desktop});
        CoUninitialize();
        PostMessageW(gWindow, kFinishedMessage, ok ? 1 : 0, 0);
    });
    SetTimer(gWindow, kProgressTimer, 33, nullptr);
}

void browseForFolder() {
    ComPtr<IFileOpenDialog> dialog;
    if (FAILED(CoCreateInstance(CLSID_FileOpenDialog, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&dialog)))) return;
    DWORD options = 0;
    dialog->GetOptions(&options);
    dialog->SetOptions(options | FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST);
    const std::wstring title = L"Choose where to install " + gEngine->identity().product.name;
    dialog->SetTitle(title.c_str());
    std::wstring start = gDir;
    while (!start.empty() && !dirExists(start)) start = parentDir(start);
    ComPtr<IShellItem> folder;
    if (!start.empty() && SUCCEEDED(SHCreateItemFromParsingName(start.c_str(), nullptr, IID_PPV_ARGS(&folder)))) {
        dialog->SetFolder(folder.Get());
    }
    if (dialog->Show(gWindow) != S_OK) return;
    ComPtr<IShellItem> result;
    PWSTR path = nullptr;
    if (FAILED(dialog->GetResult(&result)) || FAILED(result->GetDisplayName(SIGDN_FILESYSPATH, &path))) return;
    std::wstring picked = normalizeDir(path);
    CoTaskMemFree(path);
    // Picking "D:\Apps" means "install under D:\Apps", as with most installers.
    const std::wstring name = gEngine->identity().displayName();
    if (lstrcmpiW(fileName(picked).c_str(), name.c_str()) != 0) {
        picked += (picked.back() == L'\\' ? L"" : L"\\") + name;
    }
    sendDir(picked);
}

void showWindow() {
    if (gShown) return;
    gShown = true;
    KillTimer(gWindow, kShowTimer);
    ShowWindow(gWindow, SW_SHOW);
    SetForegroundWindow(gWindow);
    if (gController) {
        // WebView2 doesn't follow its parent becoming visible on its own.
        gController->put_IsVisible(TRUE);
        gController->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC);
    }
    post(Json(L"shown"));
}

void handleMessage(const std::wstring& message) {
    std::vector<std::wstring> parts;
    size_t start = 0;
    while (true) {
        size_t bar = message.find(L'|', start);
        parts.push_back(message.substr(start, bar == std::wstring::npos ? std::wstring::npos : bar - start));
        if (bar == std::wstring::npos) break;
        start = bar + 1;
    }
    const std::wstring& command = parts[0];
    auto arg = [&](size_t i) { return i < parts.size() ? parts[i] : std::wstring(); };

    if (command == L"hello") {
        sendInit();
    } else if (command == L"ready") {
        showWindow();
    } else if (command == L"drag") {
        PostMessageW(gWindow, kDragMessage, 0, 0);
    } else if (command == L"minimize") {
        ShowWindow(gWindow, SW_MINIMIZE);
    } else if (command == L"close") {
        PostMessageW(gWindow, WM_CLOSE, 0, 0);
    } else if (command == L"browse") {
        if (!gBusy) browseForFolder();
    } else if (command == L"install") {
        gDir = normalizeDir(arg(1));
        startWork(false, arg(2) == L"1", arg(3) == L"1", false);
    } else if (command == L"uninstall") {
        startWork(true, false, false, arg(1) == L"1");
    } else if (command == L"launch") {
        launchApp(gEngine->identity(), gDir);
        PostMessageW(gWindow, WM_CLOSE, 0, 0);
    } else if (command == L"log") {
        ShellExecuteW(gWindow, L"open", logPath().c_str(), nullptr, nullptr, SW_SHOWNORMAL);
    }
}

// ---------------------------------------------------------------------------
// WebView2

void webViewUnavailable(HRESULT result) {
    log(L"WebView2 unavailable: 0x%08lx", static_cast<unsigned long>(result));
    KillTimer(gWindow, kShowTimer);
    ShowWindow(gWindow, SW_HIDE);
    const std::wstring text = gEngine->identity().product.name +
        L" Setup needs the Microsoft Edge WebView2 Runtime, which Nuvio also uses for its player.\n\n"
        L"Select OK to open the download page, then run setup again.";
    if (MessageBoxW(nullptr, text.c_str(), L"WebView2 Runtime required", MB_OKCANCEL | MB_ICONINFORMATION) == IDOK) {
        ShellExecuteW(nullptr, L"open", kWebView2Download, nullptr, nullptr, SW_SHOWNORMAL);
    }
    DestroyWindow(gWindow);
}

void serveResource(ICoreWebView2WebResourceRequestedEventArgs* args) {
    ComPtr<ICoreWebView2WebResourceRequest> request;
    LPWSTR rawUri = nullptr;
    if (FAILED(args->get_Request(&request)) || FAILED(request->get_Uri(&rawUri))) return;
    std::wstring uri = rawUri;
    CoTaskMemFree(rawUri);
    std::wstring path = uri.compare(0, wcslen(kUiOrigin), kUiOrigin) == 0 ? uri.substr(wcslen(kUiOrigin)) : L"";
    path = path.substr(0, path.find_first_of(L"?#"));

    ComPtr<ICoreWebView2WebResourceResponse> response;
    for (const auto& resource : kUiResources) {
        if (path != resource.path) continue;
        HRSRC info = FindResourceW(nullptr, MAKEINTRESOURCEW(resource.id), RT_RCDATA);
        HGLOBAL loaded = info ? LoadResource(nullptr, info) : nullptr;
        const void* data = loaded ? LockResource(loaded) : nullptr;
        if (!data) break;
        IStream* stream = SHCreateMemStream(static_cast<const BYTE*>(data), SizeofResource(nullptr, info));
        const std::wstring headers = std::wstring(L"Content-Type: ") + resource.mime + L"\r\nCache-Control: no-store";
        gEnvironment->CreateWebResourceResponse(stream, 200, L"OK", headers.c_str(), &response);
        if (stream) stream->Release();
        break;
    }
    if (!response) gEnvironment->CreateWebResourceResponse(nullptr, 404, L"Not Found", L"", &response);
    args->put_Response(response.Get());
}

void configureWebView(ICoreWebView2Controller* controller) {
    gController = controller;
    gController->put_IsVisible(gShown ? TRUE : FALSE);
    gController->get_CoreWebView2(&gWebView);
    ComPtr<ICoreWebView2Controller2> controller2;
    if (SUCCEEDED(gController.As(&controller2))) {
        controller2->put_DefaultBackgroundColor({255, GetRValue(kBackground), GetGValue(kBackground), GetBValue(kBackground)});
    }

    ComPtr<ICoreWebView2Settings> settings;
    gWebView->get_Settings(&settings);
    settings->put_AreDefaultContextMenusEnabled(gArgs.devtools);
    settings->put_AreDevToolsEnabled(gArgs.devtools);
    settings->put_IsStatusBarEnabled(FALSE);
    settings->put_IsZoomControlEnabled(FALSE);
    settings->put_AreDefaultScriptDialogsEnabled(FALSE);
    if (ComPtr<ICoreWebView2Settings3> settings3; SUCCEEDED(settings.As(&settings3))) {
        settings3->put_AreBrowserAcceleratorKeysEnabled(gArgs.devtools);
    }
    if (ComPtr<ICoreWebView2Settings4> settings4; SUCCEEDED(settings.As(&settings4))) {
        settings4->put_IsGeneralAutofillEnabled(FALSE);
        settings4->put_IsPasswordAutosaveEnabled(FALSE);
    }
    if (ComPtr<ICoreWebView2Settings5> settings5; SUCCEEDED(settings.As(&settings5))) {
        settings5->put_IsPinchZoomEnabled(FALSE);
    }
    if (ComPtr<ICoreWebView2Settings6> settings6; SUCCEEDED(settings.As(&settings6))) {
        settings6->put_IsSwipeNavigationEnabled(FALSE);
    }

    const std::wstring filter = std::wstring(kUiOrigin) + L"*";
    gWebView->AddWebResourceRequestedFilter(filter.c_str(), COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL);
    EventRegistrationToken token;
    gWebView->add_WebResourceRequested(
        Callback<ICoreWebView2WebResourceRequestedEventHandler>(
            [](ICoreWebView2*, ICoreWebView2WebResourceRequestedEventArgs* args) -> HRESULT {
                serveResource(args);
                return S_OK;
            }).Get(),
        &token);
    gWebView->add_WebMessageReceived(
        Callback<ICoreWebView2WebMessageReceivedEventHandler>(
            [](ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs* args) -> HRESULT {
                LPWSTR text = nullptr;
                if (SUCCEEDED(args->TryGetWebMessageAsString(&text)) && text) {
                    std::wstring message = text;
                    CoTaskMemFree(text);
                    handleMessage(message);
                }
                return S_OK;
            }).Get(),
        &token);
    // The page is the whole app: no navigating away, no popups, no dropped files.
    gWebView->add_NavigationStarting(
        Callback<ICoreWebView2NavigationStartingEventHandler>(
            [](ICoreWebView2*, ICoreWebView2NavigationStartingEventArgs* args) -> HRESULT {
                LPWSTR uri = nullptr;
                if (SUCCEEDED(args->get_Uri(&uri)) && uri) {
                    if (wcsncmp(uri, kUiOrigin, wcslen(kUiOrigin)) != 0) args->put_Cancel(TRUE);
                    CoTaskMemFree(uri);
                }
                return S_OK;
            }).Get(),
        &token);
    gWebView->add_NewWindowRequested(
        Callback<ICoreWebView2NewWindowRequestedEventHandler>(
            [](ICoreWebView2*, ICoreWebView2NewWindowRequestedEventArgs* args) -> HRESULT {
                args->put_Handled(TRUE);
                return S_OK;
            }).Get(),
        &token);

    RECT bounds;
    GetClientRect(gWindow, &bounds);
    gController->put_Bounds(bounds);
    const std::wstring start = std::wstring(kUiOrigin) + L"index.html";
    gWebView->Navigate(start.c_str());
}

void createWebView() {
    HRESULT result = CreateCoreWebView2EnvironmentWithOptions(
        nullptr, gWebViewData.c_str(), nullptr,
        Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
            [](HRESULT created, ICoreWebView2Environment* environment) -> HRESULT {
                if (FAILED(created) || !environment) {
                    webViewUnavailable(created);
                    return S_OK;
                }
                gEnvironment = environment;
                return environment->CreateCoreWebView2Controller(
                    gWindow, Callback<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(
                                 [](HRESULT made, ICoreWebView2Controller* controller) -> HRESULT {
                                     if (FAILED(made) || !controller) {
                                         webViewUnavailable(made);
                                         return S_OK;
                                     }
                                     configureWebView(controller);
                                     return S_OK;
                                 }).Get());
            }).Get());
    if (FAILED(result)) webViewUnavailable(result);
}

// ---------------------------------------------------------------------------
// Window

LRESULT CALLBACK windowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
    switch (message) {
        case WM_NCCALCSIZE:
            if (wParam) return 0;  // the page draws everything, including the title bar
            break;
        case WM_NCHITTEST:
            return HTCLIENT;
        case WM_NCACTIVATE:
            return DefWindowProcW(hwnd, message, wParam, -1);
        case WM_SIZE:
            if (gController) {
                RECT bounds;
                GetClientRect(hwnd, &bounds);
                gController->put_Bounds(bounds);
            }
            return 0;
        case WM_MOVE:
            if (gController) gController->NotifyParentWindowPositionChanged();
            return 0;
        case WM_DPICHANGED: {
            const RECT* suggested = reinterpret_cast<const RECT*>(lParam);
            SetWindowPos(hwnd, nullptr, suggested->left, suggested->top, suggested->right - suggested->left,
                         suggested->bottom - suggested->top, SWP_NOZORDER | SWP_NOACTIVATE);
            return 0;
        }
        case WM_SETFOCUS:
            if (gController) gController->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC);
            return 0;
        case WM_TIMER:
            if (wParam == kProgressTimer) sendProgress(false);
            if (wParam == kShowTimer) showWindow();
            return 0;
        case kDragMessage: {
            POINT cursor;
            GetCursorPos(&cursor);
            ReleaseCapture();
            SendMessageW(hwnd, WM_NCLBUTTONDOWN, HTCAPTION, MAKELPARAM(cursor.x, cursor.y));
            return 0;
        }
        case kFinishedMessage: {
            KillTimer(hwnd, kProgressTimer);
            sendProgress(true);
            gBusy = false;
            const bool ok = wParam != 0;
            if (ok && gMode == L"uninstall") gUninstalled = true;
            if (gTaskbar) gTaskbar->SetProgressState(hwnd, ok ? TBPF_NOPROGRESS : TBPF_ERROR);
            if (ok) post(Json(L"done"));
            else post(Json(L"error").str(L"message", gEngine->error()));
            if (!ok || GetForegroundWindow() != hwnd) FlashWindow(hwnd, TRUE);
            return 0;
        }
        case WM_CLOSE:
            if (gBusy) return 0;  // the page disables close while files are moving
            DestroyWindow(hwnd);
            return 0;
        case WM_DESTROY:
            if (gController) gController->Close();
            PostQuitMessage(0);
            return 0;
    }
    static const UINT taskbarButtonCreated = RegisterWindowMessageW(L"TaskbarButtonCreated");
    if (message == taskbarButtonCreated) {
        if (SUCCEEDED(CoCreateInstance(CLSID_TaskbarList, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&gTaskbar)))) {
            gTaskbar->HrInit();
        }
    }
    return DefWindowProcW(hwnd, message, wParam, lParam);
}

bool createWindow(HINSTANCE instance) {
    WNDCLASSEXW windowClass{sizeof windowClass};
    windowClass.style = CS_HREDRAW | CS_VREDRAW;
    windowClass.lpfnWndProc = windowProc;
    windowClass.hInstance = instance;
    windowClass.hIcon = static_cast<HICON>(LoadImageW(instance, MAKEINTRESOURCEW(IDI_APP), IMAGE_ICON,
                                                      GetSystemMetrics(SM_CXICON), GetSystemMetrics(SM_CYICON), 0));
    windowClass.hIconSm = static_cast<HICON>(LoadImageW(instance, MAKEINTRESOURCEW(IDI_APP), IMAGE_ICON,
                                                        GetSystemMetrics(SM_CXSMICON), GetSystemMetrics(SM_CYSMICON), 0));
    windowClass.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    windowClass.hbrBackground = CreateSolidBrush(kBackground);
    windowClass.lpszClassName = kWindowClass;
    if (!RegisterClassExW(&windowClass)) return false;

    POINT cursor;
    GetCursorPos(&cursor);
    HMONITOR monitor = MonitorFromPoint(cursor, MONITOR_DEFAULTTOPRIMARY);
    MONITORINFO info{sizeof info};
    GetMonitorInfoW(monitor, &info);
    UINT dpiX = 96, dpiY = 96;
    GetDpiForMonitor(monitor, MDT_EFFECTIVE_DPI, &dpiX, &dpiY);
    const int width = MulDiv(kWidth, static_cast<int>(dpiX), 96);
    const int height = MulDiv(kHeight, static_cast<int>(dpiX), 96);
    const RECT& work = info.rcWork;
    const int x = work.left + (work.right - work.left - width) / 2;
    const int y = work.top + (work.bottom - work.top - height) / 2;

    const std::wstring title = gEngine->identity().displayName() + (gMode == L"uninstall" ? L" Uninstall" : L" Setup");
    gWindow = CreateWindowExW(WS_EX_APPWINDOW, kWindowClass, title.c_str(),
                              WS_POPUP | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX | WS_CLIPCHILDREN, x, y, width, height,
                              nullptr, nullptr, instance, nullptr);
    if (!gWindow) return false;

    // Native shadow and, on Windows 11, rounded corners for the borderless frame.
    const MARGINS margins{1, 1, 1, 1};
    DwmExtendFrameIntoClientArea(gWindow, &margins);
    const BOOL dark = TRUE;
    DwmSetWindowAttribute(gWindow, 20 /* DWMWA_USE_IMMERSIVE_DARK_MODE */, &dark, sizeof dark);
    const DWORD corners = 2 /* DWMWCP_ROUND */;
    DwmSetWindowAttribute(gWindow, 33 /* DWMWA_WINDOW_CORNER_PREFERENCE */, &corners, sizeof corners);
    const COLORREF border = RGB(42, 42, 42);
    DwmSetWindowAttribute(gWindow, 34 /* DWMWA_BORDER_COLOR */, &border, sizeof border);
    SetWindowPos(gWindow, nullptr, 0, 0, 0, 0, SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER);
    // Shown once the page has painted, so it never flashes empty; this is the fallback.
    SetTimer(gWindow, kShowTimer, 4000, nullptr);
    return true;
}

// ---------------------------------------------------------------------------
// Process plumbing

void parseArgs() {
    int count = 0;
    LPWSTR* argv = CommandLineToArgvW(GetCommandLineW(), &count);
    for (int i = 1; i < count; ++i) {
        std::wstring arg = argv[i];
        if (arg == L"--silent" || arg == L"/S" || arg == L"/silent") gArgs.silent = true;
        else if (arg == L"--uninstall") gArgs.uninstall = true;
        else if (arg == L"--sandbox") gArgs.sandbox = true;
        else if (arg == L"--from-temp") gArgs.fromTemp = true;
        else if (arg == L"--launch") gArgs.launch = true;
        else if (arg == L"--devtools") gArgs.devtools = true;
        else if (arg == L"--remove-data") gArgs.removeData = true;
        else if (arg == L"--no-desktop-shortcut") gArgs.noDesktop = true;
        else if (arg == L"--no-start-menu-shortcut") gArgs.noStartMenu = true;
        else if (arg == L"--legacy-upgrade-code" && i + 1 < count) gArgs.legacyUpgradeCode = argv[++i];
        else if (arg == L"--dir" && i + 1 < count) gArgs.dir = argv[++i];
        else if (arg.rfind(L"--dir=", 0) == 0) gArgs.dir = arg.substr(6);
        else if (arg.rfind(L"/D=", 0) == 0) gArgs.dir = arg.substr(3);
    }
    LocalFree(argv);
}

std::wstring tempDir() {
    wchar_t temp[MAX_PATH + 1];
    DWORD length = GetTempPathW(ARRAYSIZE(temp), temp);
    return std::wstring(temp, length);
}

// Files that are locked until this process exits are removed by a short-lived,
// hidden cmd.exe that waits a few seconds first.
void scheduleCleanup(const std::vector<std::wstring>& files, const std::vector<std::wstring>& dirs) {
    std::wstring command = L"cmd.exe /d /c ping -n 4 127.0.0.1 >nul";
    for (const auto& file : files) command += L" & del /f /q \"" + file + L"\"";
    for (const auto& dir : dirs) command += L" & rmdir /s /q \"" + dir + L"\"";
    if (files.empty() && dirs.empty()) return;
    STARTUPINFOW startup{sizeof startup};
    PROCESS_INFORMATION process{};
    if (CreateProcessW(nullptr, command.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW, nullptr, nullptr, &startup,
                       &process)) {
        CloseHandle(process.hThread);
        CloseHandle(process.hProcess);
    }
}

std::vector<std::wstring> leftovers() {
    std::vector<std::wstring> dirs;
    if (gUninstalled && !gDir.empty() && dirExists(gDir)) dirs.push_back(gDir);
    if (!gWebViewData.empty() && dirExists(gWebViewData)) dirs.push_back(gWebViewData);
    return dirs;
}

// The uninstaller lives in the folder it deletes, so it hands over to a copy in %TEMP%.
int relaunchFromTemp(const Identity& identity, const PayloadSource& source) {
    std::wstring location = gArgs.dir;
    const std::wstring ownDir = parentDir(source.exePath);
    if (location.empty() && fileExists(ownDir + L"\\" + identity.product.exe)) location = ownDir;
    if (location.empty()) location = detectExisting(identity).location;
    location = normalizeDir(location);

    const std::wstring copy = tempDir() + L"Nuvio Native Uninstall " + std::to_wstring(GetTickCount64()) + L".exe";
    if (!CopyFileW(source.exePath.c_str(), copy.c_str(), FALSE)) {
        log(L"Couldn't copy the uninstaller to %s: %s", copy.c_str(), systemMessage(GetLastError()).c_str());
        return 1;
    }
    std::wstring command = L"\"" + copy + L"\" --uninstall --from-temp";
    if (!location.empty()) command += L" --dir \"" + location + L"\"";
    if (gArgs.silent) command += L" --silent";
    if (gArgs.sandbox) command += L" --sandbox";
    if (gArgs.removeData) command += L" --remove-data";
    STARTUPINFOW startup{sizeof startup};
    PROCESS_INFORMATION process{};
    if (!CreateProcessW(nullptr, command.data(), nullptr, nullptr, FALSE, 0, nullptr, nullptr, &startup, &process)) {
        log(L"Couldn't start the uninstaller copy: %s", systemMessage(GetLastError()).c_str());
        return 1;
    }
    DWORD exitCode = 0;
    if (gArgs.silent) {
        WaitForSingleObject(process.hProcess, INFINITE);
        GetExitCodeProcess(process.hProcess, &exitCode);
    }
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    return static_cast<int>(exitCode);
}

int runSilent(bool uninstall) {
    const auto& existing = gEngine->existing();
    bool ok;
    if (uninstall) {
        ok = gEngine->uninstall(gDir, gArgs.removeData);
        gUninstalled = ok;
    } else {
        InstallRequest request{gDir, existing.startMenu && !gArgs.noStartMenu, existing.desktop && !gArgs.noDesktop};
        ok = gEngine->install(request);
        if (ok && gArgs.launch) launchApp(gEngine->identity(), gDir);
    }
    return ok ? 0 : 1;
}

}  // namespace

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int) {
    // Before anything can load a DLL: never from the folder setup was started in
    // (see the delay-load flags in build-installer.ps1).
    SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_SYSTEM32);
    parseArgs();
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    log(L"Started: %s", GetCommandLineW());

    PayloadSource source;
    loadPayload(source);
    Identity identity;
    identity.sandbox = gArgs.sandbox;
    if (source.present) {
        auto meta = [&](const char* key, std::wstring& target) {
            std::wstring value = utf16(source.index.get(key));
            if (!value.empty()) target = value;
        };
        meta("name", identity.product.name);
        meta("version", identity.product.version);
        meta("publisher", identity.product.publisher);
        meta("exe", identity.product.exe);
        meta("legacyUpgradeCode", identity.product.legacyUpgradeCode);
    }
    if (gArgs.sandbox && !gArgs.legacyUpgradeCode.empty()) {
        identity.product.legacyUpgradeCode = gArgs.legacyUpgradeCode;
        identity.testLegacy = true;
    }
    // Without a payload this copy is the uninstaller kept in the install folder.
    const bool uninstall = gArgs.uninstall || (!source.present && source.problem.empty());
    if (uninstall) {
        readInstalledProduct(identity);
        if (!gArgs.fromTemp) {
            int code = relaunchFromTemp(identity, source);
            CoUninitialize();
            return code;
        }
    }

    Existing existing = detectExisting(identity);
    gEngine = std::make_unique<Engine>(identity, source, existing);
    if (uninstall) {
        gMode = L"uninstall";
        gDir = normalizeDir(gArgs.dir.empty() ? existing.location : gArgs.dir);
    } else {
        gMode = existing.ours || (existing.legacy && identity.ownsLegacy()) ? L"update" : L"install";
        gDir = normalizeDir(gArgs.dir.empty() ? gEngine->defaultDir() : gArgs.dir);
    }

    int exitCode = 0;
    if (gArgs.silent) {
        exitCode = runSilent(uninstall);
    } else {
        HANDLE singleInstance = CreateMutexW(nullptr, TRUE, L"Local\\NuvioNativeSetup");
        if (GetLastError() == ERROR_ALREADY_EXISTS) {
            if (HWND other = FindWindowW(kWindowClass, nullptr)) SetForegroundWindow(other);
            CoUninitialize();
            return 0;
        }
        gWebViewData = tempDir() + L"NuvioNativeSetup.WebView2";
        if (!createWindow(instance)) {
            log(L"Couldn't create the window: %s", systemMessage(GetLastError()).c_str());
            CoUninitialize();
            return 1;
        }
        createWebView();
        MSG message;
        while (GetMessageW(&message, nullptr, 0, 0) > 0) {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
        if (gWorker.joinable()) gWorker.join();
        gWebView.Reset();
        gController.Reset();
        gEnvironment.Reset();
        gTaskbar.Reset();
        if (singleInstance) CloseHandle(singleInstance);
    }

    std::vector<std::wstring> files;
    if (gArgs.fromTemp) files.push_back(source.exePath);
    scheduleCleanup(files, leftovers());
    log(L"Exit %d", exitCode);
    CoUninitialize();
    return exitCode;
}
