// CEF subprocess entry (GPU / renderer / etc.). macOS runs these as separate
// executables inside helper .app bundles; each just hands control to CEF.
#include "include/cef_app.h"
#include "include/cef_command_line.h"
#include "include/wrapper/cef_library_loader.h"

namespace {

class OpusChildApp : public CefApp {
 public:
  void OnBeforeCommandLineProcessing(
      const CefString&, CefRefPtr<CefCommandLine> command_line) override {
    // Keep every Chromium subprocess on the same non-Keychain credential
    // policy as the browser process, even if CEF changes switch inheritance.
    if (command_line) {
      command_line->AppendSwitch("use-mock-keychain");
      command_line->AppendSwitchWithValue("password-store", "basic");
    }
  }

 private:
  IMPLEMENT_REFCOUNTING(OpusChildApp);
};

}  // namespace

int main(int argc, char* argv[]) {
  CefScopedLibraryLoader library_loader;
  if (!library_loader.LoadInHelper()) {
    return 1;
  }
  CefMainArgs main_args(argc, argv);
  CefRefPtr<OpusChildApp> app(new OpusChildApp());
  return CefExecuteProcess(main_args, app, nullptr);
}
