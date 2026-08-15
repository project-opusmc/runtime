package dev.rbw.client.hud;

import dev.rbw.client.ui.UiBounds;
import dev.rbw.client.ui.UiInput;
import dev.rbw.client.ui.UiRenderer;
import dev.rbw.client.ui.UiFontWeight;
import dev.rbw.client.ui.render.MinecraftUiRenderer;
import dev.rbw.client.ui.render.MinecraftUiScale;
import dev.rbw.client.UiTheme;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

/**
 * Sole Forge-HUD render entry. It also owns editor hit testing so normal HUD
 * rendering and editor selection always target the same widget geometry.
 */
public final class HudManager {
    public static final class EditorAction {
        private final boolean handled;
        private final String settingsModuleId;

        private EditorAction(boolean handled, String settingsModuleId) {
            this.handled = handled;
            this.settingsModuleId = settingsModuleId;
        }

        public boolean handled() {
            return handled;
        }

        public String settingsModuleId() {
            return settingsModuleId;
        }
    }

    private enum DragMode {
        NONE,
        MOVE,
        RESIZE
    }

    private final List<HudWidget> widgets;
    private final Map<String, UiBounds> editorBounds = new HashMap<String, UiBounds>();
    private String selectedModuleId;
    private String chromeModuleId;
    private String activeModuleId;
    private DragMode dragMode = DragMode.NONE;
    private int lastMouseX;
    private int lastMouseY;
    private UiBounds closeControl = new UiBounds(0, 0, 0, 0);
    private UiBounds settingsControl = new UiBounds(0, 0, 0, 0);
    private UiBounds resizeControl = new UiBounds(0, 0, 0, 0);

    public HudManager(List<HudWidget> widgets) {
        this.widgets = Collections.unmodifiableList(new ArrayList<HudWidget>(widgets));
    }

    public void render(Minecraft minecraft, ScaledResolution resolution) {
        UiBounds viewport = MinecraftUiScale.viewport(minecraft);
        MinecraftUiRenderer renderer = new MinecraftUiRenderer(minecraft);
        renderer.beginFrame(viewport);
        try {
            renderWidgets(new HudRenderContext(minecraft, renderer, viewport));
        } finally {
            renderer.endFrame();
        }
    }

    /** Called from the RBW HUD Editor page while its UI frame is already active. */
    public void renderEditor(Minecraft minecraft, UiRenderer renderer, UiBounds viewport, UiInput input) {
        HudRenderContext context = new HudRenderContext(minecraft, renderer, viewport);
        editorBounds.clear();
        renderWidgets(context);

        String hoveredModuleId = findModuleAt(input.mouseX, input.mouseY);
        String previousChromeModuleId = chromeModuleId;
        String chromeModuleId = selectedModuleId != null ? selectedModuleId : hoveredModuleId;
        // The toolbar sits just outside the widget bounds. Retain its owner
        // while crossing that small gap, otherwise the S/X/resize controls
        // disappear at the exact moment the player tries to use one.
        if (chromeModuleId == null && previousChromeModuleId != null && isOverEditorControl(input.mouseX, input.mouseY)) {
            chromeModuleId = previousChromeModuleId;
        }
        this.chromeModuleId = chromeModuleId;
        if (chromeModuleId == null) {
            clearControls();
            return;
        }

        UiBounds bounds = editorBounds.get(chromeModuleId);
        if (bounds == null) {
            clearControls();
            return;
        }

        UiBounds outline = bounds.inset(-3);
        renderer.border(outline, 1, 0xFFE6E7EB);
        // Match the observed HUD-editor anatomy: a compact resize handle at
        // the corner and low-profile settings/remove controls on the widget
        // boundary. There is no generic toolbar floating beside the value.
        resizeControl = new UiBounds(outline.x - 3, outline.y - 3, 10, 10);
        settingsControl = new UiBounds(outline.x + 2, outline.bottom() - 12, 10, 10);
        closeControl = new UiBounds(outline.right() - 12, outline.bottom() - 12, 10, 10);
        renderResizeControl(renderer, resizeControl);
        renderSettingsControl(renderer, settingsControl);
        renderCloseControl(renderer, closeControl);
    }

    public EditorAction editorMouseDown(int mouseX, int mouseY, int button) {
        if (button != 0) {
            return null;
        }
        if (closeControl.contains(mouseX, mouseY)) {
            HudWidget widget = selectedOrHoveredWidget(mouseX, mouseY);
            if (widget != null) {
                widget.disable();
                selectedModuleId = null;
                clearControls();
                return new EditorAction(true, null);
            }
            return null;
        }
        if (settingsControl.contains(mouseX, mouseY)) {
            HudWidget widget = selectedOrHoveredWidget(mouseX, mouseY);
            return widget == null ? null : new EditorAction(true, widget.moduleId());
        }
        if (resizeControl.contains(mouseX, mouseY)) {
            HudWidget widget = selectedOrHoveredWidget(mouseX, mouseY);
            if (widget != null) {
                selectedModuleId = widget.moduleId();
                activeModuleId = widget.moduleId();
                dragMode = DragMode.RESIZE;
                lastMouseX = mouseX;
                lastMouseY = mouseY;
                return new EditorAction(true, null);
            }
        }

        String moduleId = findModuleAt(mouseX, mouseY);
        if (moduleId != null) {
            selectedModuleId = moduleId;
            activeModuleId = moduleId;
            dragMode = DragMode.MOVE;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return new EditorAction(true, null);
        }
        selectedModuleId = null;
        clearControls();
        return null;
    }

    public boolean editorMouseDrag(UiBounds viewport, int mouseX, int mouseY, int button) {
        if (button != 0 || dragMode == DragMode.NONE || activeModuleId == null) {
            return false;
        }
        HudWidget widget = widgetById(activeModuleId);
        UiBounds bounds = editorBounds.get(activeModuleId);
        if (widget == null || bounds == null) {
            return false;
        }
        int deltaX = mouseX - lastMouseX;
        int deltaY = mouseY - lastMouseY;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        if (dragMode == DragMode.MOVE) {
            return widget.moveBy(viewport, deltaX, deltaY);
        }
        return widget.resizeBy(bounds, deltaX);
    }

    public boolean editorMouseUp(int button) {
        if (button != 0 || dragMode == DragMode.NONE) {
            return false;
        }
        HudWidget widget = widgetById(activeModuleId);
        activeModuleId = null;
        dragMode = DragMode.NONE;
        return widget != null && widget.commitEditorChange();
    }

    private void renderWidgets(HudRenderContext context) {
        for (HudWidget widget : widgets) {
            if (widget.isVisible()) {
                widget.renderNormal(context);
                editorBounds.put(widget.moduleId(), editorBounds(widget.bounds(context)));
            }
        }
    }

    private String findModuleAt(int mouseX, int mouseY) {
        for (int index = widgets.size() - 1; index >= 0; index--) {
            HudWidget widget = widgets.get(index);
            UiBounds bounds = editorBounds.get(widget.moduleId());
            if (bounds != null && bounds.inset(-3).contains(mouseX, mouseY)) {
                return widget.moduleId();
            }
        }
        return null;
    }

    private HudWidget selectedOrHoveredWidget(int mouseX, int mouseY) {
        HudWidget selected = widgetById(selectedModuleId);
        if (selected != null) {
            return selected;
        }
        HudWidget chrome = widgetById(chromeModuleId);
        return chrome != null ? chrome : widgetById(findModuleAt(mouseX, mouseY));
    }

    private HudWidget widgetById(String moduleId) {
        if (moduleId == null) {
            return null;
        }
        for (HudWidget widget : widgets) {
            if (moduleId.equals(widget.moduleId())) {
                return widget;
            }
        }
        return null;
    }

    private void clearControls() {
        chromeModuleId = null;
        closeControl = new UiBounds(0, 0, 0, 0);
        settingsControl = new UiBounds(0, 0, 0, 0);
        resizeControl = new UiBounds(0, 0, 0, 0);
    }

    /**
     * A text-only widget can be smaller than its real editor affordances.
     * Give the editor a minimal interaction surface without changing the
     * normal HUD geometry or inventing a second widget renderer.
     */
    private static UiBounds editorBounds(UiBounds widgetBounds) {
        return new UiBounds(
                widgetBounds.x,
                widgetBounds.y,
                Math.max(68, widgetBounds.width + 12),
                Math.max(30, widgetBounds.height + 14));
    }

    private boolean isOverEditorControl(int mouseX, int mouseY) {
        return closeControl.contains(mouseX, mouseY)
                || settingsControl.contains(mouseX, mouseY)
                || resizeControl.contains(mouseX, mouseY);
    }

    private static void renderResizeControl(UiRenderer renderer, UiBounds bounds) {
        renderer.roundedRect(bounds, 2, 0xE513141A);
        renderer.border(bounds, 1, 0xFFE6E7EB);
        renderer.line(bounds.x + 3.0F, bounds.y + 7.0F, bounds.x + 7.0F, bounds.y + 3.0F, 0.9F, 0xFFE6E7EB);
    }

    private static void renderSettingsControl(UiRenderer renderer, UiBounds bounds) {
        renderer.roundedRect(bounds, 2, 0xE513141A);
        float centerX = bounds.x + bounds.width / 2.0F;
        float centerY = bounds.y + bounds.height / 2.0F;
        renderer.ring(centerX, centerY, 2.2F, 0.9F, 0xFFE6E7EB);
        renderer.ring(centerX, centerY, 0.65F, 0.8F, 0xFFE6E7EB);
        renderer.line(centerX, centerY - 3.4F, centerX, centerY - 2.5F, 0.8F, 0xFFE6E7EB);
        renderer.line(centerX, centerY + 2.5F, centerX, centerY + 3.4F, 0.8F, 0xFFE6E7EB);
        renderer.line(centerX - 3.4F, centerY, centerX - 2.5F, centerY, 0.8F, 0xFFE6E7EB);
        renderer.line(centerX + 2.5F, centerY, centerX + 3.4F, centerY, 0.8F, 0xFFE6E7EB);
    }

    private static void renderCloseControl(UiRenderer renderer, UiBounds bounds) {
        renderer.roundedRect(bounds, 2, 0xD9261A1D);
        renderer.line(bounds.x + 3.0F, bounds.y + 3.0F, bounds.right() - 3.0F, bounds.bottom() - 3.0F, 0.9F, 0xFFF0E7E8);
        renderer.line(bounds.right() - 3.0F, bounds.y + 3.0F, bounds.x + 3.0F, bounds.bottom() - 3.0F, 0.9F, 0xFFF0E7E8);
    }
}
