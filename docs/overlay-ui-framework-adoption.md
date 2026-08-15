# Quyết định áp dụng kiến trúc UI overlay

**Ngày:** 2026-08-11  
**Nguồn:** technical review độc lập về implementation Forge/OptiFine hiện tại.  
**Trạng thái:** quyết định kiến trúc cho refactor; chưa phải uỷ quyền đóng gói một UI mới.

## Quyết định

Giữ nguyên integration path:

- Forge client-side mod;
- KeyBinding Right Shift;
- pause-menu Client Options integration;
- Forge HUD render event;
- launcher Tauri/Rust;
- JSON settings file được truyền qua JVM system property;
- OptiFine trong cùng Forge runtime.

Thay toàn bộ product UI phía trên integration path. Không mở rộng RbwClientScreen prototype, GuiButton nội bộ, coordinate layout cố định hay renderer HUD riêng lẻ.

Không sử dụng external desktop overlay, transparent native window, OpenGL injection ngoài Minecraft hoặc React/Tauri để render UI in-game.

## Flow sản phẩm có tính bắt buộc

Flow dưới đây do product reference của người dùng quyết định và có ưu tiên hơn flow ví dụ tổng quát trong technical review:

| Trigger | Route đích |
|---|---|
| Game launch | MainMenu overlay |
| Right Shift trong gameplay | HudEditor |
| Esc → Client Options | ModHub |
| HudEditor → Mods | ModHub |
| ModHub → Edit HUD Layout | HudEditor |
| Module card hoặc cog của HUD widget | ModuleDetail cùng moduleId |

Không có route nào được phép mở generic settings form.

## Boundary của hệ mới

    Minecraft/Forge
          ↓
    RbwClientScreen (adapter duy nhất)
          ↓
    UiRuntime
          ├── router + page state
          ├── input/focus/scroll/drag
          ├── component tree + layout
          ├── animation clock
          └── UiRenderer
                   ↓
             Minecraft/OpenGL backend

RbwClientScreen chỉ chuyển lifecycle/input của Minecraft cho UiRuntime. Nó không tự tính layout, không tạo internal GuiButton và không ghi module settings.

ClientOverlayController chỉ giữ Minecraft integration: key binding, pause-menu insertion, route open và forwarding Forge HUD event. Nó không render module hay chứa module business logic.

## Route model

UiRuntime phải có một route state duy nhất:

    MainMenu
    HudEditor
    ModHub
    ModuleDetail(moduleId)

Page transition không tạo GuiScreen riêng cho từng page. Escape/close phải xác định theo route history; không được quay về một form trung gian.

## Component và layout

Product UI dùng component nội bộ:

    Component
    Container
    Text
    Image
    Button
    Toggle
    Slider
    SearchInput
    Dropdown
    ScrollView
    ModuleCard
    Tooltip

Layout dùng primitive:

    Row
    Column
    Grid
    Stack
    Padding
    Spacer
    Align
    Anchor
    Scroll
    Bounds

Absolute coordinates chỉ tồn tại ở renderer sau layout. Một component không tự quyết định coordinate của sibling. Design canvas có thể dùng để so reference 1280×720, nhưng layout phải vẫn đúng ở GUI scale, window resize, fullscreen và Retina.

GuiButton chỉ được phép dùng cho nút Client Options chèn vào pause menu vanilla. Nó không được dùng bên trong RBW product UI.

## Renderer contract

UiRenderer là API duy nhất component/module dùng để vẽ:

    beginFrame / endFrame
    rect / roundedRect / border / shadow
    texture
    text / measureText / truncate
    pushClip / popClip

OpenGL chỉ được gọi trong renderer backend. Component, page, module và widget không gọi GL11 hoặc Minecraft draw primitives trực tiếp.

Renderer phải dùng GlStateGuard để capture/restore blend, depth test, alpha test, texture, color, shader program, scissor, stencil, viewport, framebuffer và matrix state. Không bind texture trực tiếp mà làm lệch cache của Minecraft GlStateManager.

Scissor conversion, mouse coordinates và framebuffer coordinates phải được chuẩn hoá qua một coordinate space duy nhất để xử lý HiDPI/Retina chính xác.

Blur không thuộc UI foundation. Sau khi layout/render foundation ổn định, blur phải là FBO/downsample/two-pass shader có state restore đầy đủ và fallback an toàn khi OptiFine/shader-pack path không được hỗ trợ.

## Module và HUD contract

Tách ba abstraction:

    Module: functionality, metadata, enabled state, option schema
    HudWidget: phần render của module trên game HUD
    UI Component: control xuất hiện trong client interface

Forge HUD event chỉ chuyển vào HudManager. Mỗi module không tự đăng ký Forge render event.

    Forge HUD event
          ↓
      HudManager
          ↓
      HudWidget instances
          ↓
       UiRenderer

FpsOverlayRenderer phải được migrate thành FpsModule + FpsWidget. Cùng FpsWidget render cả NORMAL và EDITOR context; HUD editor vẽ selection, drag, resize handle, remove action, settings action và snap guideline ở lớp editor, không duplicate widget preview.

Catalog card, HUD gear và ModuleDetail phải resolve cùng moduleId, option schema và persisted state.

## Persistence

Giữ utility-settings-v1 JSON bridge và atomic write. Mở rộng schema có migration rõ ràng để chứa:

    modules
    hud layout
    module options
    profiles
    UI preferences

Không được tạo widget/card/setting cho module chưa có data source, runtime behavior, persistence và acceptance test thật. Fresh profile và migrated profile phải im lặng cho đến khi user enable module.

## Coremod disposition

Typed Forge module là đường UI duy nhất. Legacy ASM/coremod không được render UI.

Trước production, cần chọn một trong hai hướng:

1. tách telemetry cần thiết sang FML mod; hoặc
2. không stage coremod khi typed UI là đường duy nhất.

Demo và Premium phải có guard behavior nhất quán. Không được để Premium vô tình nạp legacy UI trong khi Demo chặn nó.

## Phases và exit gate

### Phase A — freeze/migration contract

- Không mở rộng RbwClientScreen.
- Không thêm widget/card/settings mock.
- Ghi rõ legacy coremod disposition.
- Định nghĩa Module, HudWidget và config schema ownership.

Exit gate: mỗi route/module/widget có một owner rõ ràng; không có hai đường UI active.

### Phase B — UI foundation

- UiRuntime, route state, bounds, layout primitives, input dispatch.
- UiRenderer, clip stack, GlStateGuard.
- Component tree và core controls.

Exit gate: cùng functionality FPS prototype chạy bằng framework mới, không overlap ở GUI scale mục tiêu, không GUI button nội bộ và không GL state leak.

### Phase C — shared FPS module/HUD

- FpsModule, FpsWidget, HudManager.
- module registry và shared detail option schema.
- renderer NORMAL/EDITOR chung.

Exit gate: fresh profile không có HUD; enable/disable thực sự thay live HUD; restart giữ đúng state.

### Phase D — Lunar-derived pages

- MainMenu overlay.
- HudEditor theo flow Right Shift.
- ModHub theo flow Client Options.
- ModuleDetail dùng chung từ catalog và HUD cog.

Exit gate: mọi action hiển thị đều thay đổi behavior/data thật; RShift, Client Options và Edit HUD Layout đi đúng route bắt buộc.

### Phase E — visual quality và blur

- font subsystem có metric chung với layout;
- rounded shapes, border, shadow, animation;
- OptiFine-safe blur;
- screenshot comparison ở actual Minecraft GUI scale.

Exit gate: UI text sharp, world blur thật, repeated open/close không gây render corruption cho Minecraft/OptiFine.

## Các acceptance gate không được bỏ qua

1. Capture real in-game screen ở actual user resolution, không dùng launcher screenshot.
2. Right Shift mở HudEditor; Client Options mở ModHub; Edit HUD Layout mở cùng HudEditor.
3. Hover live widget hiển thị resize, remove và settings; settings route trùng ModuleDetail.
4. Tất cả module visible có data thật và control hoạt động/persist.
5. Fresh/migrated profile không có HUD không được user enable.
6. Không font corruption, texture cache corruption, leaked GL state hoặc crash report.
7. Test windowed, fullscreen, GUI scale và HiDPI trước khi cài lại app.

## Kết luận

Technical vertical slice đã hoàn thành nhiệm vụ chứng minh Forge integration. Product implementation tiếp theo là một refactor UI framework có boundary rõ ràng, không phải tiếp tục chỉnh sửa form prototype.
