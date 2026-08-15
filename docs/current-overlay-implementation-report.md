# Báo cáo kỹ thuật: phương pháp overlay UI hiện tại

**Dự án:** RBW Client  
**Mục đích:** mô tả trung thực implementation hiện tại để expert review.  
**Trạng thái:** technical vertical slice đang chạy; chưa phải implementation đạt yêu cầu UI/flow tham chiếu Lunar Client.

## 1. Tóm tắt

RBW chạy Minecraft Java Edition 1.8.9 bằng Forge 11.15.1.2318 cùng OptiFine HD U M5. Overlay in-game không được render bởi launcher React/Tauri. Nó là một Forge client-side mod Java 8 được Forge nạp vào class loader của game.

Vertical slice hiện tại đã chứng minh:

- module Forge client-side được nạp vào game;
- Right Shift được đăng ký qua KeyBinding;
- pause menu vanilla có nút Client Options;
- hai entry point gọi cùng một GuiScreen typed;
- FPS đọc từ Minecraft.getDebugFPS() và có persistence JSON;
- launcher truyền đường dẫn config cho game qua JVM system property.

Tuy nhiên RbwClientScreen hiện tại là prototype bị từ chối. Nó là một settings form duy nhất, không phải HUD editor, Mod Hub hay module-detail flow theo UI tham chiếu. Nó không được coi là nền UI sản phẩm.

## 2. Runtime và build lane

| Thành phần | Công nghệ / phiên bản | Vai trò |
|---|---|---|
| Game | Minecraft Java Edition 1.8.9 | Runtime mục tiêu |
| Mod loader | Forge 11.15.1.2318 | API event, GUI và renderer phía client |
| Tối ưu đồ họa | OptiFine HD U M5 | Được stage như Forge mod |
| Mod overlay | Java 8, ForgeGradle 2.1.3, mappings stable_22 | UI, input, HUD render |
| Launcher | Tauri 2 + Rust + React | Auth/offline profile, cài runtime, launch, lưu config |
| Config bridge | JSON + JVM -D property | Chia sẻ setting launcher ↔ game |

Client mod là rbw-forge-client-0.0.1-preview.3.jar. Nó là FML mod bình thường, không có FMLCorePlugin trong manifest. Gradle kiểm tra điều này sau reobfuscation tại [build.gradle](/Users/zvwgvx/Project/rbw-client/game/client-mod/build.gradle:127).

## 3. Chuỗi launch

1. Tauri/Rust xác nhận Forge, OptiFine, Java và artifact RBW theo hash lock.
2. Runtime stage OptiFine, coremod cũ và typed client mod vào thư mục mods riêng của profile.
3. Java được chạy qua dev.rbw.bootstrap.ForgeBootstrapMain.
4. Forge nạp mod rbwclient và gọi event lifecycle của mod.
5. Client mod đăng ký keybind, Forge event listener và HUD renderer.

Launch mode và việc stage artifact hiện ở [launch.rs](/Users/zvwgvx/Project/rbw-client/launcher/rbw-runtime/src/launch.rs:107) và [launch.rs](/Users/zvwgvx/Project/rbw-client/launcher/rbw-runtime/src/launch.rs:388).

Hai product bundle là:

- RBW Client.app: Premium launcher.
- RBW Client Demo.app: offline/demo launcher, có offline username và data root riêng.

## 4. In-game overlay implementation

### 4.1 Entry point

RbwClientMod là FML entry point, clientSideOnly = true; trong lifecycle nó tạo ClientOverlayController và đăng ký hook. Xem [RbwClientMod.java](/Users/zvwgvx/Project/rbw-client/game/client-mod/src/main/java/dev/rbw/client/RbwClientMod.java:15).

### 4.2 Input và entry point

ClientOverlayController dùng typed Forge APIs, không dùng reflection hoặc ASM để dựng UI:

- KeyBinding với Keyboard.KEY_RSHIFT;
- TickEvent.ClientTickEvent để nhận phím;
- GuiScreenEvent.InitGuiEvent.Post để thêm Client Options vào GuiIngameMenu;
- GuiScreenEvent.ActionPerformedEvent.Post để xử lý click;
- RenderGameOverlayEvent.Text để render FPS HUD.

Right Shift và Client Options cùng gọi openOptions(), rồi mở new RbwClientScreen(this). Xem [ClientOverlayController.java](/Users/zvwgvx/Project/rbw-client/game/client-mod/src/main/java/dev/rbw/client/ClientOverlayController.java:35).

Forge sinh ASMEventHandler ở package khác. Một phiên bản trước để controller package-private đã gây IllegalAccessError và crash khi pause menu khởi tạo. Bản preview.3 đã đổi controller thành public.

### 4.3 Screen hiện tại

RbwClientScreen kế thừa GuiScreen, không pause game và vẽ thủ công bằng drawRect, Minecraft FontRenderer và texture RBW. Controls là subclass GuiButton.

Màn này chỉ có Performance/FPS:

- enable/disable;
- cycle anchor;
- tăng/giảm scale;
- tăng/giảm opacity;
- Done/Escape đóng screen.

Mã nằm ở [RbwClientScreen.java](/Users/zvwgvx/Project/rbw-client/game/client-mod/src/main/java/dev/rbw/client/RbwClientScreen.java:17).

**Đánh giá:** đây là phần phải thay thế. Layout form cố định, đã xảy ra overlap ở tỷ lệ thực tế. Nó không có route, Mod Hub, module grid, profile sidebar, HUD editor, resize handle, hover actions hay visual language tham chiếu.

### 4.4 HUD renderer

FpsOverlayRenderer chỉ render khi settings.enabled là true, lấy giá trị từ Minecraft.getDebugFPS(), tôn trọng anchor/offset/scale/opacity và dùng FontRenderer.drawStringWithShadow. Xem [FpsOverlayRenderer.java](/Users/zvwgvx/Project/rbw-client/game/client-mod/src/main/java/dev/rbw/client/FpsOverlayRenderer.java:15).

Không có CPS, armor, clock, coordinates hay widget giả trong renderer hiện tại. FPS widget chưa có drag, resize hay cog HUD-editor interaction.

## 5. Persistence và data bridge

Launcher tạo và lưu utility-settings-v1.json với schemaVersion 1. Khi migrate từ schema cũ, mọi utility được set disabled để ngăn HUD tự xuất hiện từ state cũ.

Khi launch, launcher thêm JVM argument:

    -Drbw.utility.settings.file=<absolute path>/utility-settings-v1.json

Xem [launch.rs](/Users/zvwgvx/Project/rbw-client/launcher/rbw-runtime/src/launch.rs:313) và [lib.rs](/Users/zvwgvx/Project/rbw-client/desktop/src-tauri/src/lib.rs:1146).

Trong game, UtilitySettingsStore đọc cùng file bằng Gson. Chỉ key utilities.fps được typed client mod dùng/update; key khác chỉ được preserve trong JSON. Ghi file dùng temporary file + atomic move. Xem [UtilitySettingsStore.java](/Users/zvwgvx/Project/rbw-client/game/client-mod/src/main/java/dev/rbw/client/UtilitySettingsStore.java:21).

Hiện chưa có IPC hai chiều live; launcher và game đồng bộ qua file.

## 6. Rendering và blur

Hiện không có blur thật. RbwClientScreen chỉ vẽ scrim alpha đen. Không có framebuffer capture, downsample, FBO, shader pass, shader reload hoặc OptiFine compatibility layer.

Vì vậy UI hiện tại không đáp ứng yêu cầu background blur.

## 7. Legacy coremod: tình trạng và rủi ro

Repository có coremod/ASM cũ. Typed Forge mod mới không dùng nó để render UI, nhưng ForgeBootstrap vẫn stage rbw-forge-coremod.jar cùng typed client mod vì contract bootstrap hiện yêu cầu nó.

Demo và UI-preview thêm -Drbw.legacy.ui.disabled=true để UI legacy không chạy. Premium hiện không tự thêm cờ này vì code dùng feature guard qa-edition hoặc ui-preview.

Rủi ro kiến trúc:

1. Hai đường UI còn tồn tại trong artifact/runtime.
2. Forge vẫn phát hiện coremod trong mods directory và warning về coremod.
3. Premium và Demo không hoàn toàn cùng guard behavior.

Khuyến nghị: tách telemetry cần thiết khỏi coremod cũ hoặc sửa Forge bootstrap để không stage coremod khi typed UI là đường UI duy nhất.

## 8. Bằng chứng test

Đã chạy thành công:

- game/client-mod Gradle verifyClientArtifact;
- game Gradle test + prepareBootstrap;
- Rust test rbw-runtime: 35 tests pass;
- Rust test rbw-desktop qa-edition: 11 tests pass;
- TypeScript check.

Smoke launch Demo sau khi sửa access modifier ghi nhận:

    RBW client options registered: Right Shift and the pause-menu Client Options button open the real utility surface.
    RBW Forge client module loaded; the typed Client Options surface is ready.
    game.status = running

Không có automated visual acceptance test. User visual test đã xác nhận screen mở được nhưng rejected về product UI/flow.

## 9. Chênh lệch với target UI

Implementation hiện tại thiếu:

- custom game main-menu overlay;
- HUD Editor mở bằng Right Shift;
- widget selection, drag, resize, delete, settings;
- Mod Hub có header/tabs, profile sidebar, category toolbar, search và module grid;
- Module Detail route dùng chung cho card/cog HUD;
- Esc → Client Options đi thẳng Mod Hub;
- Edit HUD Layout đi về đúng HUD editor;
- framebuffer blur và fallback OptiFine-safe;
- responsive layout và visual regression testing theo design canvas.

## 10. Câu hỏi cần expert review

1. Với Forge 1.8.9 + OptiFine, kiến trúc render nào an toàn nhất cho blur: custom GuiScreen + FBO hai pass, hay HUD render layer riêng với input router?
2. Có nên loại bỏ hoàn toàn legacy coremod khỏi Forge bootstrap, hay giữ telemetry dưới dạng FML mod riêng?
3. Nên thiết kế routing/UI state thế nào để MainMenu, HudEditor, ModHub và ModuleDetail(moduleId) dùng một overlay renderer?
4. Nên dùng design canvas/scale strategy nào để khớp reference 1280×720 nhưng không vỡ ở GUI scale/resolution khác?
5. Cách render icon/font/texture, clipping và scrolling nào phù hợp Minecraft 1.8.9 mà không phụ thuộc asset Lunar?
6. Nên chọn config model nào để launcher/game tránh race condition khi cùng đọc/ghi JSON?

## 11. Kết luận

Typed Forge client mod là hướng đúng hơn GUI ASM/reflection cũ cho input, screen lifecycle, persistence và renderer thật. Nhưng UI hiện tại không thể tiếp tục làm nền visual. Bước tiếp theo phải là xây shared overlay framework theo state machine/reference flow, sau đó mới đưa utility thật vào Mod Hub/HUD editor. Không nên thêm module, button hay mock card nào vào RbwClientScreen hiện tại.
