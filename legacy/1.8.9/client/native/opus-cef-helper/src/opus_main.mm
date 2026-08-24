// Opus CEF helper — offscreen browser -> shared-memory BGRA -> in-game GL texture.
//
// The game JVM (Java 8) launches this as a separate process, connects to the
// loopback control port, and maps the same shared-memory file. CEF renders the
// Opus web UI windowless (OSR); each OnPaint copies BGRA into the shared file
// and notifies the game with a generation-scoped FRAME record. The game uploads that
// buffer to an OpenGL texture and draws it in its own frame — no window, no
// overlay. Java version is irrelevant because CEF runs only here.
//
// Protocol (line-based, loopback TCP; helper is the server):
//   Java -> helper : HELLO <token>
//                    VIEW <generation> <w> <h> <scale> <url>
//                    RELEASE <seq> <slot> | RELOAD <generation> | PING | BYE
//                    INPUT move <x> <y>
//                    INPUT leave <x> <y>
//                    INPUT down|up <x> <y> <button>
//                    INPUT wheel <x> <y> <dy>
//                    INPUT char <codepoint>
//                    INPUT key <windows_vk> <1|0 down>
//   helper -> Java : READY
//                    FRAME <seq> <generation> <slot> <w> <h> <byte_offset>
//                    PONG
//
// SIZE is in DIP (logical CSS pixels); SCALE is the device scale factor, so the
// painted frame is <w*scale> x <h*scale> real pixels. The game passes its own
// framebuffer-to-logical ratio as the scale, which makes the texture map 1:1 to
// the game's framebuffer — that is what keeps web text crisp instead of an
// upscaled low-resolution blur.
//
// The shared buffer is DOUBLE-BUFFERED: it holds two equally sized slots and
// each paint lands in the slot the game is not reading, so the game never
// uploads a half-written frame. Without this, a torn frame would persist on
// screen indefinitely, because CEF only repaints when something actually
// changes — there is no next frame to correct it.

#import <Cocoa/Cocoa.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <limits.h>
#include <netinet/in.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <limits>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "include/cef_app.h"
#include "include/cef_browser.h"
#include "include/cef_command_line.h"
#include "include/cef_client.h"
#include "include/cef_render_handler.h"
#include "include/cef_task.h"
#include "include/wrapper/cef_helpers.h"
#include "include/wrapper/cef_library_loader.h"

namespace {

void Log(const char* fmt, ...) {
  char buf[512];
  va_list ap; va_start(ap, fmt); vsnprintf(buf, sizeof(buf), fmt, ap); va_end(ap);
  fprintf(stderr, "[opus-cef] %s\n", buf);
}

constexpr size_t kSlots = 2;         // double buffering; see header comment

// State touched only on the CEF UI thread unless noted.
std::string g_token;
int g_width = 1280;
int g_height = 720;
float g_scale = 1.0f;                // device scale factor (framebuffer px / DIP)
unsigned char* g_shm = nullptr;      // mmap'd shared frame buffer (2 slots)
size_t g_shm_cap = 0;                // total mapped bytes
size_t g_slot_bytes = 0;             // bytes per frame slot (g_shm_cap / kSlots)
int g_shm_fd = -1;                   // owned descriptor for the mapping
uint64_t g_seq = 0;
uint64_t g_generation = 1;
CefRefPtr<CefBrowser> g_browser;
std::atomic<int> g_client_fd{-1};    // set by socket thread, read by OnPaint
std::mutex g_client_write_mutex;     // serializes all control-socket writes/closes
std::atomic<uint64_t> g_slot_sequence[kSlots]; // 0 free, UINT64_MAX reserved
std::atomic<bool> g_paint_blocked{false};
std::atomic<bool> g_shutdown_requested{false};
bool g_browser_creation_pending = false; // CEF UI thread only
std::string g_cache_path;
bool g_cache_owned = false;
extern std::string g_url; // declared with the navigation helpers below

bool SendToClient(const std::string& line) {
  std::lock_guard<std::mutex> lock(g_client_write_mutex);
  const int fd = g_client_fd.load();
  if (fd < 0) return false;

  const std::string payload = line + "\n";
  size_t sent = 0;
  while (sent < payload.size()) {
#ifdef MSG_NOSIGNAL
    constexpr int flags = MSG_NOSIGNAL;
#else
    constexpr int flags = 0;
#endif
    const ssize_t count = ::send(fd, payload.data() + sent,
                                 payload.size() - sent, flags);
    if (count > 0) {
      sent += static_cast<size_t>(count);
      continue;
    }
    if (count < 0 && errno == EINTR) continue;

    Log("control socket send failed; closing client connection");
    int expected = fd;
    if (g_client_fd.compare_exchange_strong(expected, -1)) {
      shutdown(fd, SHUT_RDWR);
      close(fd);
    }
    return false;
  }
  return true;
}

// Runs a closure on the CEF UI thread (browser ops must happen there).
class FnTask : public CefTask {
 public:
  explicit FnTask(std::function<void()> fn) : fn_(std::move(fn)) {}
  void Execute() override { fn_(); }
 private:
  std::function<void()> fn_;
  IMPLEMENT_REFCOUNTING(FnTask);
};
void PostUI(std::function<void()> fn) { CefPostTask(TID_UI, new FnTask(std::move(fn))); }

// Closes the control connection exactly once. The socket thread and the CEF UI
// thread can both need to wake the other side during shutdown; exchanging the
// descriptor first prevents a late double-close from accidentally closing a
// newly reused descriptor.
void CloseClientConnection() {
  std::lock_guard<std::mutex> lock(g_client_write_mutex);
  int fd = g_client_fd.exchange(-1);
  if (fd >= 0) {
    shutdown(fd, SHUT_RDWR);
    close(fd);
  }
}

void RequestShutdownOnUI() {
  CEF_REQUIRE_UI_THREAD();
  g_shutdown_requested.store(true);
  if (g_browser) {
    // CEF must deliver OnBeforeClose before CefShutdown. The message loop is
    // therefore quit from that callback, never immediately after BYE.
    g_browser->GetHost()->CloseBrowser(true);
  } else if (!g_browser_creation_pending) {
    // No browser exists and no asynchronous CreateBrowser request can still
    // produce one, so the loop can stop immediately.
    CefQuitMessageLoop();
  }
}

// <!-- APPEND-CLIENT -->

class OpusClient : public CefClient,
                   public CefRenderHandler,
                   public CefLifeSpanHandler,
                   public CefLoadHandler,
                   public CefDisplayHandler {
 public:
  void GetViewRect(CefRefPtr<CefBrowser>, CefRect& rect) override {
    rect = CefRect(0, 0, g_width, g_height);
  }

  // Reporting a device scale factor makes CEF rasterize at that factor, so
  // OnPaint delivers g_width*scale x g_height*scale real pixels. The game sets
  // it to its framebuffer/logical ratio, so the texture is pixel-exact.
  bool GetScreenInfo(CefRefPtr<CefBrowser>, CefScreenInfo& info) override {
    info.device_scale_factor = g_scale;
    info.depth = 24;
    info.depth_per_component = 8;
    info.is_monochrome = false;
    info.rect = CefRect(0, 0, g_width, g_height);
    info.available_rect = info.rect;
    return true;
  }

  void OnPaint(CefRefPtr<CefBrowser>, PaintElementType type,
               const RectList& dirty, const void* buffer,
               int width, int height) override {
    if (type != PET_VIEW || g_shm == nullptr || buffer == nullptr
        || width <= 0 || height <= 0) return;
    const size_t max_size = std::numeric_limits<size_t>::max();
    const size_t w = static_cast<size_t>(width);
    const size_t h = static_cast<size_t>(height);
    if (w > max_size / h || w * h > max_size / 4) {
      Log("frame %dx%d size overflows; dropping", width, height);
      return;
    }
    const size_t bytes = w * h * 4;
    if (g_slot_bytes == 0 || bytes > g_slot_bytes) {
      static bool warned = false;
      if (!warned) {
        warned = true;
        Log("frame %dx%d (%zu bytes) exceeds slot capacity %zu; dropping",
            width, height, bytes, g_slot_bytes);
      }
      return;
    }
    int slot = -1;
    for (size_t index = 0; index < kSlots; ++index) {
      uint64_t expected = 0;
      if (g_slot_sequence[index].compare_exchange_strong(expected, UINT64_MAX)) {
        slot = static_cast<int>(index);
        break;
      }
    }
    if (slot < 0) {
      g_paint_blocked.store(true);
      return;
    }
    size_t offset = static_cast<size_t>(slot) * g_slot_bytes;
    if (offset > g_shm_cap || bytes > g_shm_cap - offset) {
      g_slot_sequence[slot].store(0);
      Log("frame %dx%d offset %zu exceeds mapped buffer %zu; dropping",
          width, height, offset, g_shm_cap);
      return;
    }
    memcpy(g_shm + offset, buffer, bytes);
    if (g_seq >= UINT64_MAX - 1) {
      g_slot_sequence[slot].store(0);
      g_paint_blocked.store(true);
      Log("frame sequence exhausted; refusing further paints");
      return;
    }
    ++g_seq;
    g_slot_sequence[slot].store(g_seq);
    // Diagnostics for the first few paints. A frame that arrives but is fully
    // transparent looks identical in-game to no frame at all (the lifecycle
    // shell shows through), so the alpha sample distinguishes "the browser is
    // not painting" from "the page rendered nothing".
    if (g_seq <= 12) {
      const unsigned char* p = static_cast<const unsigned char*>(buffer);
      size_t stride = 4 * 64;  // sample every 64th pixel; alpha is the 4th byte
      size_t sampled = 0, visible = 0;
      for (size_t i = 3; i < bytes; i += stride) { ++sampled; if (p[i]) ++visible; }
      Log("paint #%llu gen %llu %dx%d -> offset %zu; %zu/%zu sampled pixels visible",
          static_cast<unsigned long long>(g_seq),
          static_cast<unsigned long long>(g_generation), width, height, offset,
          visible, sampled);
    }
    SendToClient("FRAME " + std::to_string(g_seq) + " " +
                 std::to_string(g_generation) + " " + std::to_string(slot) + " " +
                 std::to_string(width) + " " + std::to_string(height) + " " +
                 std::to_string(offset));
  }

  // ---- page diagnostics -------------------------------------------------
  // Without these the helper is silent about what the browser is doing: a 404,
  // a failed asset, a JS exception on boot and a perfectly rendered page all
  // look the same from the game side. Everything lands in the game log via
  // stderr (see drainProcessOutput on the Java side).

  void OnLoadingStateChange(CefRefPtr<CefBrowser>, bool isLoading,
                            bool, bool) override {
    Log("loading state: %s", isLoading ? "started" : "finished");
  }

  void OnLoadEnd(CefRefPtr<CefBrowser>, CefRefPtr<CefFrame> frame,
                 int httpStatusCode) override {
    if (frame->IsMain()) {
      Log("main frame loaded, HTTP %d, url=%s", httpStatusCode,
          frame->GetURL().ToString().c_str());
    }
  }

  void OnLoadError(CefRefPtr<CefBrowser>, CefRefPtr<CefFrame> frame,
                   ErrorCode errorCode, const CefString& errorText,
                   const CefString& failedUrl) override {
    Log("LOAD ERROR %s (%d) on %s%s", errorText.ToString().c_str(), errorCode,
        failedUrl.ToString().c_str(), frame->IsMain() ? " [main frame]" : "");
  }

  bool OnConsoleMessage(CefRefPtr<CefBrowser>, cef_log_severity_t level,
                        const CefString& message, const CefString& source,
                        int line) override {
    Log("console[%d] %s (%s:%d)", level, message.ToString().c_str(),
        source.ToString().c_str(), line);
    return false;
  }

  void OnAfterCreated(CefRefPtr<CefBrowser> browser) override {
    CEF_REQUIRE_UI_THREAD();
    g_browser_creation_pending = false;
    g_browser = browser;
    if (g_shutdown_requested.load()) {
      browser->GetHost()->CloseBrowser(true);
    } else {
      browser->GetHost()->SetFocus(true);
      // VIEW commands can arrive while CEF is still creating the browser.
      // Replay the latest URL here so a fast route transition cannot leave the
      // helper on the startup route with no post-navigation paint.
      const std::string current = browser->GetMainFrame()->GetURL().ToString();
      if (!g_url.empty() && g_url != current) {
        browser->GetMainFrame()->LoadURL(g_url);
      }
      Log("browser ready");
    }
  }
  void OnBeforeClose(CefRefPtr<CefBrowser>) override {
    CEF_REQUIRE_UI_THREAD();
    g_browser = nullptr;
    CloseClientConnection();
    Log("browser closed");
    CefQuitMessageLoop();
  }

  CefRefPtr<CefRenderHandler> GetRenderHandler() override { return this; }
  CefRefPtr<CefLifeSpanHandler> GetLifeSpanHandler() override { return this; }
  CefRefPtr<CefLoadHandler> GetLoadHandler() override { return this; }
  CefRefPtr<CefDisplayHandler> GetDisplayHandler() override { return this; }
  IMPLEMENT_REFCOUNTING(OpusClient);
};

class OpusApp : public CefApp, public CefBrowserProcessHandler {
 public:
  explicit OpusApp(std::string url) : url_(std::move(url)) {}
  CefRefPtr<CefBrowserProcessHandler> GetBrowserProcessHandler() override { return this; }

  void OnBeforeCommandLineProcessing(
      const CefString& process_type,
      CefRefPtr<CefCommandLine> command_line) override {
    // Opus never stores browser passwords, cookies, or refresh credentials in
    // Chromium. Keep the helper entirely out of macOS Keychain so a game test
    // cannot trigger an unlock prompt through Chromium's os_crypt service.
    // Restrict this to the browser process; CEF warns that changing child
    // process command lines can be unsafe for some platform helpers.
    if (process_type.empty() && command_line) {
      command_line->AppendSwitch("use-mock-keychain");
      command_line->AppendSwitchWithValue("password-store", "basic");
    }
  }

  void OnContextInitialized() override {
    CEF_REQUIRE_UI_THREAD();
    if (g_shutdown_requested.load()) {
      CefQuitMessageLoop();
      return;
    }
    CefWindowInfo window_info;
    window_info.SetAsWindowless(0);
    CefBrowserSettings settings;
    settings.windowless_frame_rate = 30;
    settings.background_color = CefColorSetARGB(0, 0, 0, 0);  // transparent
    g_browser_creation_pending = true;
    if (!CefBrowserHost::CreateBrowser(window_info, new OpusClient(), url_, settings,
                                       nullptr, nullptr)) {
      g_browser_creation_pending = false;
      Log("FATAL: CreateBrowser request failed");
      CefQuitMessageLoop();
      return;
    }
    Log("context initialized, url=%s", url_.c_str());
  }
 private:
  std::string url_;
  IMPLEMENT_REFCOUNTING(OpusApp);
};

// ---- input injection (runs on UI thread via PostUI) ----
struct MacKeyData {
  int native_key_code;
  char16_t character;
};

MacKeyData MacKeyDataForWindowsVirtualKey(int windows_key_code) {
  // CEF's macOS event translator builds a synthetic NSEvent from the native
  // key code and characters. windows_key_code alone is ignored on this
  // platform, and zero characters are interpreted as a modifier-only event.
  switch (windows_key_code) {
    case 0x1B: return {0x35, 0x001B};  // Escape / kVK_Escape
    case 0x08: return {0x33, 0x007F};  // Backspace / kVK_Delete
    case 0x09: return {0x30, u'\t'};   // Tab
    case 0x0D: return {0x24, u'\r'};   // Return
    case 0x20: return {0x31, u' '};    // Space / kVK_Space
    case 0x21: return {0x74, 0xF72C};  // Page Up / NSPageUpFunctionKey
    case 0x22: return {0x79, 0xF72D};  // Page Down / NSPageDownFunctionKey
    case 0x23: return {0x77, 0xF72B};  // End / NSEndFunctionKey
    case 0x24: return {0x73, 0xF729};  // Home / NSHomeFunctionKey
    case 0x25: return {0x7B, 0xF702};  // Left / NSLeftArrowFunctionKey
    case 0x26: return {0x7E, 0xF700};  // Up / NSUpArrowFunctionKey
    case 0x27: return {0x7C, 0xF703};  // Right / NSRightArrowFunctionKey
    case 0x28: return {0x7D, 0xF701};  // Down / NSDownArrowFunctionKey
    case 0x2E: return {0x75, 0xF728};  // Forward Delete
    default: return {0, 0};
  }
}

void InjectInput(const std::string& rest) {
  if (!g_browser) return;
  CefRefPtr<CefBrowserHost> host = g_browser->GetHost();
  char kind[16] = {0};
  int x = 0, y = 0, a = 0;
  double d = 0;
  if (sscanf(rest.c_str(), "%15s", kind) != 1) return;
  CefMouseEvent m; m.modifiers = 0;
  if (strcmp(kind, "move") == 0 && sscanf(rest.c_str(), "%*s %d %d", &x, &y) == 2) {
    m.x = x; m.y = y; host->SendMouseMoveEvent(m, false);
  } else if (strcmp(kind, "leave") == 0 && sscanf(rest.c_str(), "%*s %d %d", &x, &y) == 2) {
    m.x = x; m.y = y; host->SendMouseMoveEvent(m, true);
  } else if (strcmp(kind, "down") == 0 && sscanf(rest.c_str(), "%*s %d %d %d", &x, &y, &a) == 3) {
    m.x = x; m.y = y;
    cef_mouse_button_type_t b = a == 1 ? MBT_RIGHT : a == 2 ? MBT_MIDDLE : MBT_LEFT;
    host->SendMouseClickEvent(m, b, false, 1);
  } else if (strcmp(kind, "up") == 0 && sscanf(rest.c_str(), "%*s %d %d %d", &x, &y, &a) == 3) {
    m.x = x; m.y = y;
    cef_mouse_button_type_t b = a == 1 ? MBT_RIGHT : a == 2 ? MBT_MIDDLE : MBT_LEFT;
    host->SendMouseClickEvent(m, b, true, 1);
  } else if (strcmp(kind, "wheel") == 0 && sscanf(rest.c_str(), "%*s %d %d %lf", &x, &y, &d) == 3) {
    m.x = x; m.y = y; host->SendMouseWheelEvent(m, 0, static_cast<int>(d));
  } else if (strcmp(kind, "char") == 0 && sscanf(rest.c_str(), "%*s %d", &a) == 1) {
    host->SetFocus(true);
    CefKeyEvent k; k.type = KEYEVENT_CHAR; k.character = a;
    k.unmodified_character = a; k.windows_key_code = a; k.modifiers = 0;
    host->SendKeyEvent(k);
  } else if (strcmp(kind, "key") == 0 && sscanf(rest.c_str(), "%*s %d %d", &a, &x) == 2) {
    const MacKeyData mac_key = MacKeyDataForWindowsVirtualKey(a);
    if (mac_key.native_key_code == 0 || mac_key.character == 0) return;
    host->SetFocus(true);
    CefKeyEvent k; k.windows_key_code = a; k.modifiers = 0;
    k.native_key_code = mac_key.native_key_code;
    k.character = mac_key.character;
    k.unmodified_character = mac_key.character;
    if (x == 1) {
      // cefclient's macOS OSR path emits KEYDOWN followed by CHAR for a native
      // key press. The CHAR event is also required for non-printing navigation
      // keys such as Tab and Return to reach Blink's default actions.
      k.type = KEYEVENT_KEYDOWN;
      host->SendKeyEvent(k);
      k.type = KEYEVENT_CHAR;
      host->SendKeyEvent(k);
    } else {
      k.type = KEYEVENT_KEYUP;
      host->SendKeyEvent(k);
    }
  }
}

// <!-- APPEND-NET -->

std::string g_url;  // UI thread

void DoNavigate(const std::string& url) {
  if (!g_browser) { g_url = url; return; }
  // The Java side resends VIEW immediately after the helper connects. If it
  // repeats the URL that CreateBrowser is already loading, executing a hash
  // script here aborts that first document load and can leave OSR with no
  // paint at all. Treat an identical URL as an idempotent command.
  if (g_url == url) return;
  // A route can arrive while the previous document is still loading. A full
  // LoadURL is safer than injecting a fragment into a document that has not
  // finished booting; once loaded, same-document hash navigation stays fast.
  if (g_browser->IsLoading()) {
    g_browser->GetMainFrame()->LoadURL(url);
    g_url = url;
    return;
  }
  // Same document, only the #route changed -> switch in place (no reload stall).
  auto base = [](const std::string& u) { auto h = u.find('#'); return h == std::string::npos ? u : u.substr(0, h); };
  if (!g_url.empty() && base(g_url) == base(url)) {
    std::string frag; auto h = url.find('#'); if (h != std::string::npos) frag = url.substr(h + 1);
    std::string js = "location.hash='" + frag + "';";
    g_browser->GetMainFrame()->ExecuteJavaScript(js, "", 0);
  } else {
    g_browser->GetMainFrame()->LoadURL(url);
  }
  g_url = url;
}

// Returns false if the connection should close.
bool HandleLine(const std::string& line, bool& authed) {
  if (line.empty()) return true;
  if (!authed) {
    if (line.rfind("HELLO ", 0) == 0 && line.substr(6) == g_token) {
      authed = true; SendToClient("READY");
      // Force a fresh paint so the client gets a frame immediately, even if the
      // first (pre-connection) paint was emitted before it connected.
      PostUI([] { if (g_browser) g_browser->GetHost()->Invalidate(PET_VIEW); });
      return true;
    }
    Log("auth failed"); return false;
  }
  if (line.rfind("VIEW ", 0) == 0) {
    unsigned long long generation = 0;
    int w = 0, h = 0, consumed = 0;
    double s = 0;
    if (sscanf(line.c_str(), "VIEW %llu %d %d %lf %n",
               &generation, &w, &h, &s, &consumed) == 4
        && generation > 0 && w > 0 && h > 0 && s >= 0.5 && s <= 4.0
        && consumed > 0 && static_cast<size_t>(consumed) < line.size()) {
      std::string u = line.substr(static_cast<size_t>(consumed));
      PostUI([generation, w, h, s, u] {
        bool scale_changed = static_cast<float>(s) != g_scale;
        g_generation = static_cast<uint64_t>(generation);
        g_width = w;
        g_height = h;
        g_scale = static_cast<float>(s);
        // Keep the latest route even when the browser has not been created
        // yet. DoNavigate stores it and OnAfterCreated replays it above.
        DoNavigate(u);
        if (g_browser) {
          if (scale_changed) g_browser->GetHost()->NotifyScreenInfoChanged();
          g_browser->GetHost()->WasResized();
          g_browser->GetHost()->Invalidate(PET_VIEW);
        }
      });
    }
  } else if (line.rfind("RELEASE ", 0) == 0) {
    unsigned long long sequence = 0;
    int slot = -1;
      if (sscanf(line.c_str(), "RELEASE %llu %d", &sequence, &slot) == 2
        && sequence > 0 && sequence != UINT64_MAX
        && slot >= 0 && slot < static_cast<int>(kSlots)) {
      uint64_t expected = static_cast<uint64_t>(sequence);
      if (g_slot_sequence[slot].compare_exchange_strong(expected, 0)
          && g_paint_blocked.exchange(false)) {
        PostUI([] { if (g_browser) g_browser->GetHost()->Invalidate(PET_VIEW); });
      }
    }
  } else if (line.rfind("RELOAD ", 0) == 0) {
    unsigned long long generation = 0;
    if (sscanf(line.c_str(), "RELOAD %llu", &generation) == 1 && generation > 0) {
      PostUI([generation] {
        g_generation = static_cast<uint64_t>(generation);
        if (g_browser) g_browser->ReloadIgnoreCache();
      });
    }
  } else if (line.rfind("INPUT ", 0) == 0) {
    std::string rest = line.substr(6);
    PostUI([rest] { InjectInput(rest); });
  } else if (line == "PING") {
    SendToClient("PONG");
  } else if (line == "BYE") {
    return false;
  }
  return true;
}

void SocketThread(int port) {
  int srv = ::socket(AF_INET, SOCK_STREAM, 0);
  if (srv < 0) {
    Log("socket() failed");
    PostUI([] { RequestShutdownOnUI(); });
    return;
  }
  int one = 1; setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
  sockaddr_in addr{}; addr.sin_family = AF_INET; addr.sin_port = htons(port);
  addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  if (bind(srv, (sockaddr*)&addr, sizeof(addr)) < 0) {
    Log("bind %d failed", port);
    close(srv);
    PostUI([] { RequestShutdownOnUI(); });
    return;
  }
  if (listen(srv, 1) < 0) {
    Log("listen failed");
    close(srv);
    PostUI([] { RequestShutdownOnUI(); });
    return;
  }
  Log("listening on 127.0.0.1:%d", port);

  int fd = accept(srv, nullptr, nullptr);
  close(srv);
  if (fd < 0) {
    Log("accept failed");
    PostUI([] { RequestShutdownOnUI(); });
    return;
  }
#ifdef SO_NOSIGPIPE
  setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof(one));
#endif
  {
    std::lock_guard<std::mutex> lock(g_client_write_mutex);
    if (g_shutdown_requested.load()) {
      shutdown(fd, SHUT_RDWR);
      close(fd);
      return;
    }
    int expected = -1;
    if (!g_client_fd.compare_exchange_strong(expected, fd)) {
      Log("control connection already exists; rejecting duplicate client");
      shutdown(fd, SHUT_RDWR);
      close(fd);
      return;
    }
  }
  bool authed = false;
  std::string buf;
  char tmp[8192];
  for (;;) {
    ssize_t n = ::recv(fd, tmp, sizeof(tmp), 0);
    if (n <= 0) break;
    buf.append(tmp, n);
    size_t nl;
    while ((nl = buf.find('\n')) != std::string::npos) {
      std::string line = buf.substr(0, nl);
      buf.erase(0, nl + 1);
      if (!line.empty() && line.back() == '\r') line.pop_back();
      if (!HandleLine(line, authed)) {
        CloseClientConnection();
        PostUI([] { RequestShutdownOnUI(); });
        return;
      }
    }
  }
  // Game disconnected -> shut down.
  CloseClientConnection();
  Log("client gone; quitting");
  PostUI([] { RequestShutdownOnUI(); });
}

void ReleaseOwnedResources() {
  CloseClientConnection();
  if (g_shm != nullptr) {
    if (munmap(g_shm, g_shm_cap) != 0) {
      Log("munmap failed");
    }
    g_shm = nullptr;
    g_shm_cap = 0;
    g_slot_bytes = 0;
  }
  if (g_shm_fd >= 0) {
    close(g_shm_fd);
    g_shm_fd = -1;
  }
  if (g_cache_owned && !g_cache_path.empty()) {
    @autoreleasepool {
      NSString* path = [NSString stringWithUTF8String:g_cache_path.c_str()];
      NSError* error = nil;
      if ([[NSFileManager defaultManager] fileExistsAtPath:path]
          && ![[NSFileManager defaultManager] removeItemAtPath:path error:&error]) {
        Log("could not remove helper cache %s: %s", g_cache_path.c_str(),
            error.localizedDescription.UTF8String);
      }
    }
  }
  g_cache_path.clear();
  g_cache_owned = false;
}

// <!-- APPEND-MAIN -->

}  // namespace

int main(int argc, char* argv[]) {
  CefScopedLibraryLoader library_loader;
  if (!library_loader.LoadInMain()) {
    fprintf(stderr, "[opus-cef] FATAL: could not load CEF framework\n");
    return 1;
  }

  int port = 0;
  std::string url = "about:blank";
  std::string shm_path;
  std::string cache_path;
  // Total mapped bytes across all slots. Sized for a 4K slot so any window the
  // game can open fits without renegotiating the mapping.
  size_t shm_cap = static_cast<size_t>(3840) * 2160 * 4 * kSlots;
  for (int i = 1; i < argc; ++i) {
    std::string a = argv[i];
    if (a == "--port" && i + 1 < argc) port = atoi(argv[++i]);
    else if (a == "--token" && i + 1 < argc) g_token = argv[++i];
    else if (a == "--url" && i + 1 < argc) url = argv[++i];
    else if (a == "--shm" && i + 1 < argc) shm_path = argv[++i];
    else if (a == "--shm-size" && i + 1 < argc) shm_cap = strtoull(argv[++i], nullptr, 10);
    else if (a == "--cache" && i + 1 < argc) cache_path = argv[++i];
    else if (a == "--width" && i + 1 < argc) g_width = atoi(argv[++i]);
    else if (a == "--height" && i + 1 < argc) g_height = atoi(argv[++i]);
    else if (a == "--scale" && i + 1 < argc) g_scale = strtof(argv[++i], nullptr);
    else if (a == "--generation" && i + 1 < argc) g_generation = strtoull(argv[++i], nullptr, 10);
  }
  g_url = url;
  if (g_scale < 0.5f || g_scale > 4.0f) g_scale = 1.0f;
  if (g_generation == 0) g_generation = 1;
  for (size_t index = 0; index < kSlots; ++index) g_slot_sequence[index].store(0);
  g_paint_blocked.store(false);

  // Chromium treats the cache directory as a process singleton. A caller may
  // supply and own a persistent path, but the normal game path creates a unique
  // helper-owned directory and removes only that directory after CefShutdown.
  g_cache_owned = cache_path.empty();
  if (g_cache_owned) {
    const char* tmp = getenv("TMPDIR");
    const char* root = (tmp && *tmp) ? tmp : "/tmp/";
    char cache_template[PATH_MAX];
    snprintf(cache_template, sizeof(cache_template), "%s%sopus-cef-cache-XXXXXX",
             root, root[strlen(root) - 1] == '/' ? "" : "/");
    char* created = mkdtemp(cache_template);
    if (created == nullptr) {
      Log("FATAL: could not create helper cache");
      return 1;
    }
    g_cache_path = created;
  } else {
    g_cache_path = cache_path;
  }

  constexpr size_t kMaxShmBytes = static_cast<size_t>(3840) * 2160 * 4 * kSlots;
  if (shm_path.empty() || shm_cap < kSlots * 4
      || shm_cap % kSlots != 0 || shm_cap > kMaxShmBytes) {
    Log("FATAL: valid --shm and --shm-size are required");
    ReleaseOwnedResources();
    return 1;
  }
  g_shm_fd = open(shm_path.c_str(), O_RDWR);
  if (g_shm_fd < 0 || ftruncate(g_shm_fd, shm_cap) != 0) {
    Log("FATAL: cannot open/size shm %s", shm_path.c_str());
    ReleaseOwnedResources();
    return 1;
  }
  void* mapping = mmap(nullptr, shm_cap, PROT_READ | PROT_WRITE, MAP_SHARED,
                       g_shm_fd, 0);
  if (mapping == MAP_FAILED) {
    Log("FATAL: mmap failed");
    ReleaseOwnedResources();
    return 1;
  }
  g_shm = static_cast<unsigned char*>(mapping);
  g_shm_cap = shm_cap;
  g_slot_bytes = shm_cap / kSlots;
  Log("shm mapped %s (%zu bytes, %zu slots of %zu)", shm_path.c_str(),
      shm_cap, kSlots, g_slot_bytes);
  Log("view %dx%d dip @ scale %.2f", g_width, g_height, g_scale);

  CefMainArgs main_args(argc, argv);
  int result = 0;
  @autoreleasepool {
    [NSApplication sharedApplication];
    [NSApp setActivationPolicy:NSApplicationActivationPolicyAccessory];

    CefRefPtr<OpusApp> app(new OpusApp(url));
    CefSettings settings;
    settings.windowless_rendering_enabled = true;
    settings.no_sandbox = true;
    settings.external_message_pump = false;
    CefString(&settings.root_cache_path).FromString(g_cache_path);
    Log("root_cache_path %s", g_cache_path.c_str());

    if (!CefInitialize(main_args, settings, app.get(), nullptr)) {
      Log("FATAL: CefInitialize failed");
      result = 1;
    } else {
      if (port > 0) {
        std::thread(SocketThread, port).detach();
      } else {
        Log("no --port; closing helper");
        PostUI([] { RequestShutdownOnUI(); });
      }
      CefRunMessageLoop();
      // Every message-loop exit above either had no browser or was emitted by
      // OnBeforeClose, so no live browser reaches CefShutdown.
      CefShutdown();
    }
  }
  ReleaseOwnedResources();
  return result;
}
