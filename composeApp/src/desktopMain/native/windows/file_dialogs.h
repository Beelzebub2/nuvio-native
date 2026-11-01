// Windows Common Item Dialogs, hosted by the existing desktop JNI library.
// Called on a dedicated Java thread; all COM objects stay on that STA.
#include <shobjidl.h>

namespace {
struct FileDialogCancellation {
    JNIEnv *env;
    jobject flag;
    jmethodID get;
    IFileDialog *dialog;
};
thread_local FileDialogCancellation *currentFileDialog = nullptr;

void CALLBACK cancelFileDialog(HWND, UINT, UINT_PTR, DWORD) {
    auto *state = currentFileDialog;
    if (state && state->env->CallBooleanMethod(state->flag, state->get)) {
        state->dialog->Close(HRESULT_FROM_WIN32(ERROR_CANCELLED));
    }
}

std::wstring dialogString(JNIEnv *env, jstring value) {
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (!chars) throw std::runtime_error("Cannot read file dialog text");
    std::wstring result(reinterpret_cast<const wchar_t *>(chars), env->GetStringLength(value));
    env->ReleaseStringChars(value, chars);
    return result;
}

void checkFileDialog(HRESULT result) {
    if (FAILED(result)) throw std::runtime_error("Windows file dialog failed (HRESULT " + std::to_string(result) + ")");
}

struct FileDialogApartment {
    FileDialogApartment() { checkFileDialog(CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE)); }
    ~FileDialogApartment() { CoUninitialize(); }
};

struct FileDialogTimer {
    UINT_PTR id;
    explicit FileDialogTimer(FileDialogCancellation *state) {
        currentFileDialog = state;
        id = SetTimer(nullptr, 0, 100, cancelFileDialog);
        if (!id) { currentFileDialog = nullptr; throw std::runtime_error("Cannot monitor file dialog cancellation"); }
    }
    ~FileDialogTimer() { KillTimer(nullptr, id); currentFileDialog = nullptr; }
};
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nuvio_app_core_ui_WindowsFileDialog_show(
    JNIEnv *env, jobject, jlong owner, jint mode, jstring title, jstring current, jobject cancelled
) {
    try {
        FileDialogApartment apartment;
        ComPtr<IFileDialog> dialog;
        checkFileDialog(CoCreateInstance(mode == 2 ? CLSID_FileSaveDialog : CLSID_FileOpenDialog,
            nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&dialog)));
        DWORD options = 0;
        checkFileDialog(dialog->GetOptions(&options));
        options |= FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST | FOS_NOCHANGEDIR;
        if (mode == 0) options |= FOS_PICKFOLDERS;
        else if (mode == 1) options |= FOS_FILEMUSTEXIST;
        else options |= FOS_OVERWRITEPROMPT | FOS_STRICTFILETYPES;
        checkFileDialog(dialog->SetOptions(options));
        checkFileDialog(dialog->SetTitle(dialogString(env, title).c_str()));
        if (mode != 0) {
            const COMDLG_FILTERSPEC types[] = {{L"JSON (*.json)", L"*.json"}};
            checkFileDialog(dialog->SetFileTypes(1, types));
            checkFileDialog(dialog->SetDefaultExtension(L"json"));
            if (mode == 2) checkFileDialog(dialog->SetFileName(L"starlit-addons.json"));
        }
        const std::wstring start = dialogString(env, current);
        if (!start.empty()) {
            ComPtr<IShellItem> folder;
            if (SUCCEEDED(SHCreateItemFromParsingName(start.c_str(), nullptr, IID_PPV_ARGS(&folder)))) {
                checkFileDialog(dialog->SetFolder(folder.Get()));
            }
        }
        jclass flagClass = env->GetObjectClass(cancelled);
        const jmethodID get = env->GetMethodID(flagClass, "get", "()Z");
        env->DeleteLocalRef(flagClass);
        if (!get || env->ExceptionCheck()) return nullptr;
        if (env->CallBooleanMethod(cancelled, get)) return nullptr;
        FileDialogCancellation cancellation{env, cancelled, get, dialog.Get()};
        FileDialogTimer timer(&cancellation);
        const HRESULT shown = dialog->Show(reinterpret_cast<HWND>(static_cast<intptr_t>(owner)));
        if (shown == HRESULT_FROM_WIN32(ERROR_CANCELLED)) return nullptr;
        checkFileDialog(shown);
        ComPtr<IShellItem> item;
        checkFileDialog(dialog->GetResult(&item));
        PWSTR path = nullptr;
        checkFileDialog(item->GetDisplayName(SIGDN_FILESYSPATH, &path));
        jstring result = env->NewString(reinterpret_cast<const jchar *>(path), static_cast<jsize>(wcslen(path)));
        CoTaskMemFree(path);
        return result;
    } catch (const std::exception &error) {
        if (!env->ExceptionCheck()) {
            jclass type = env->FindClass("java/io/IOException");
            if (type) env->ThrowNew(type, error.what());
        }
        return nullptr;
    }
}
