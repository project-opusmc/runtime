package dev.rbw.client;

import dev.rbw.client.ui.UiBounds;
import dev.rbw.client.ui.UiRenderer;
import dev.rbw.client.ui.UiRuntime;
import dev.rbw.client.ui.component.UiActionButton;
import dev.rbw.client.ui.UiFontWeight;
import net.minecraft.util.ResourceLocation;

/** Shared workspace shell used by the real module catalogue and module detail. */
abstract class RbwPanelPage extends RbwUiPage {
    private static final ResourceLocation WORDMARK =
            new ResourceLocation("rbwclient", "textures/gui/rbw-wordmark-transparent.png");

    final UiActionButton close;
    UiBounds frame = new UiBounds(0, 0, 0, 0);
    UiBounds content = new UiBounds(0, 0, 0, 0);
    boolean compact;

    RbwPanelPage(ClientOverlayController controller, UiRuntime runtime) {
        super(controller, runtime);
        close = button("X", UiActionButton.Tone.QUIET, new Runnable() {
            @Override
            public void run() {
                RbwPanelPage.this.runtime.requestClose();
            }
        });
    }

    @Override
    public void layout(UiBounds nextViewport) {
        super.layout(nextViewport);
        // Lunar's workspace is intentionally a near-max desktop surface: its
        // stable rail, catalogue and detail page need room to retain the same
        // hierarchy at the reference 1280x720 framebuffer size.
        int width = boundedExtent(Math.round(viewport.width * 0.78F), 360, 500, viewport.width - 24);
        int height = boundedExtent(Math.round(viewport.height * 0.85F), 224, 310, viewport.height - 24);
        frame = new UiBounds((viewport.width - width) / 2, (viewport.height - height) / 2, width, height);
        compact = frame.width < 470 || frame.height < 280;
        int headerHeight = compact ? 32 : 34;
        content = new UiBounds(frame.x, frame.y + headerHeight, frame.width, frame.height - headerHeight);

        layoutControl(close, frame.right() - 28, frame.y + (compact ? 8 : 9), 16, 16);
    }

    final void renderShell(UiRenderer renderer) {
        UiTheme theme = theme();
        renderer.fill(viewport, 0x7A000000);
        // Keep the workspace as smoked glass rather than an opaque app window:
        // the blurred world and live HUD remain legible at its edges.
        renderer.roundedRect(frame, 5, 0xE8121419);
        renderer.fill(new UiBounds(frame.x, frame.y, frame.width, content.y - frame.y), 0xEB0D0F13);
        renderer.fill(new UiBounds(frame.x, content.y - 1, frame.width, 1), 0xCC30343A);
        int logoWidth = compact ? 94 : 112;
        int logoHeight = Math.round(logoWidth * 640.0F / 1800.0F);
        renderer.textureRegion(
                WORDMARK,
                new UiBounds(frame.x + 14, frame.y + (content.y - frame.y - logoHeight) / 2, logoWidth, logoHeight),
                0.055D,
                0.20D,
                0.72D,
                0.81D);
    }

    final void sectionLabel(UiRenderer renderer, String label, int x, int y) {
        renderer.uiText(label, x, y, 6.3F, UiFontWeight.SEMIBOLD, 0.45F, 0xFFA7A7AA);
    }

    final void bodyText(UiRenderer renderer, String label, int x, int y, float size, int color) {
        renderer.uiText(label, x, y, size, UiFontWeight.SEMIBOLD, 0.0F, color);
    }

    private static int boundedExtent(int preferred, int minimum, int maximum, int available) {
        int safeAvailable = Math.max(1, available);
        int value = Math.max(minimum, Math.min(maximum, preferred));
        return Math.min(value, safeAvailable);
    }
}
