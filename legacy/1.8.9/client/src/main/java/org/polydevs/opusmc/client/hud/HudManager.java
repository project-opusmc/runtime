package org.polydevs.opusmc.client.hud;

import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.UiInput;
import org.polydevs.opusmc.client.ui.UiRenderer;
import org.polydevs.opusmc.client.ui.render.MinecraftUiRenderer;
import org.polydevs.opusmc.client.ui.render.MinecraftUiScale;
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

    private enum Corner {
        TOP_LEFT(-1, -1), TOP_RIGHT(1, -1), BOTTOM_LEFT(-1, 1), BOTTOM_RIGHT(1, 1);

        final int xDirection;
        final int yDirection;

        Corner(int xDirection, int yDirection) {
            this.xDirection = xDirection;
            this.yDirection = yDirection;
        }
    }

    private static final int FRAME_PADDING = 3;
    private static final int HANDLE_SIZE = 8;
    private static final int HANDLE_HIT_SIZE = 12;
    private static final int CONTROL_SIZE = 14;

    private final List<HudWidget> widgets;
    private final Map<String, UiBounds> widgetFrames = new HashMap<String, UiBounds>();
    private final Map<String, UiBounds> editorBounds = new HashMap<String, UiBounds>();
    private String selectedModuleId;
    private String chromeModuleId;
    private String activeModuleId;
    private DragMode dragMode = DragMode.NONE;
    private int lastMouseX;
    private int lastMouseY;
    private UiBounds closeControl = new UiBounds(0, 0, 0, 0);
    private UiBounds settingsControl = new UiBounds(0, 0, 0, 0);
    private final UiBounds[] resizeControls = new UiBounds[Corner.values().length];
    private Corner resizeCorner;
    private HudRenderContext editorContext;
    private UiBounds editorCanvas;
    private List<UiBounds> editorExclusions = Collections.emptyList();
    private UiBounds resizeStartFrame;
    private int resizeStartScale;
    private int resizeStartPointerX;
    private int resizeStartPointerY;
    private boolean editing = true;
    private boolean grid;
    private boolean snap = true;
    private boolean inspecting;
    private UndoStep pendingUndo;
    private final java.util.Deque<UndoStep> history = new java.util.ArrayDeque<UndoStep>();

    private static final class UndoStep {
        final HudWidget widget;
        final UiBounds frame;
        final int scale;
        UndoStep(HudWidget widget, UiBounds frame) { this.widget = widget; this.frame = frame; this.scale = widget.scale(); }
    }

    public void setEditing(boolean value) {
        if (editing == value) return;
        cancelPointerCapture();
        editing = value;
    }

    public String[] editorState() { return new String[] {String.valueOf(editing), String.valueOf(grid), String.valueOf(snap), String.valueOf(!history.isEmpty())}; }

    /** Snapshot of the very same live bounds rendered and hit-tested by this editor. */
    public String editorStateJson() {
        com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        data.addProperty("editing",editing); data.addProperty("grid",grid); data.addProperty("snap",snap);
        data.addProperty("canUndo",!history.isEmpty()); data.addProperty("inspecting",inspecting);
        data.addProperty("pointerCaptured",hasPointerCapture());
        UiBounds viewport = editorContext == null ? new UiBounds(0,0,0,0) : editorContext.viewport();
        data.addProperty("canvasWidth",viewport.width); data.addProperty("canvasHeight",viewport.height);
        HudWidget widget = registeredWidgetById(selectedModuleId);
        if (widget != null && editorContext != null) {
            UiBounds bounds = widget.bounds(editorContext);
            com.google.gson.JsonObject selected = new com.google.gson.JsonObject();
            selected.addProperty("id",widget.moduleId()); selected.addProperty("enabled",widget.isVisible());
            selected.addProperty("x",bounds.x); selected.addProperty("y",bounds.y);
            selected.addProperty("width",bounds.width); selected.addProperty("height",bounds.height);
            selected.addProperty("scale",widget.scale()); selected.addProperty("minScale",50); selected.addProperty("maxScale",400);
            data.add("selected",selected);
        }
        return data.toString();
    }

    /** CEF inspector commands operate in native logical pixels, with shared persistence/undo. */
    public boolean editorCommand(String operation, String moduleId, int x, int y) {
        if (!editing || hasPointerCapture() || editorContext == null) return false;
        if ("close-properties".equals(operation)) { inspecting=false; return true; }
        HudWidget widget = registeredWidgetById(moduleId);
        if (widget == null) return false;
        if ("select".equals(operation)) { selectedModuleId=moduleId; inspecting=true; return true; }
        if (!widget.isVisible()) return false;
        UiBounds viewport=editorContext.viewport(), frame=widget.bounds(editorContext);
        UndoStep before=new UndoStep(widget,frame);
        if ("scale".equals(operation)) {
            int fitScale = Math.min(viewport.width * widget.scale() / Math.max(1,frame.width),
                    viewport.height * widget.scale() / Math.max(1,frame.height));
            widget.resizeToScale(Math.min(x,fitScale));
            UiBounds scaled=widget.bounds(editorContext);
            widget.moveBy(viewport,scaled,Math.max(0,Math.min(viewport.width-scaled.width,frame.x))-scaled.x,
                    Math.max(0,Math.min(viewport.height-scaled.height,frame.y))-scaled.y);
        } else {
            int targetX=frame.x, targetY=frame.y;
            if ("position".equals(operation)) { targetX=x; targetY=y; }
            else if ("nudge".equals(operation)) { targetX=frame.x+x; targetY=frame.y+y; }
            else if ("reset".equals(operation)) { targetX=widget.defaultX(); targetY=widget.defaultY(); }
            else if ("align-left".equals(operation)) targetX=0;
            else if ("align-center".equals(operation)) targetX=(viewport.width-frame.width)/2;
            else if ("align-right".equals(operation)) targetX=viewport.width-frame.width;
            else if ("align-top".equals(operation)) targetY=0;
            else if ("align-middle".equals(operation)) targetY=(viewport.height-frame.height)/2;
            else if ("align-bottom".equals(operation)) targetY=viewport.height-frame.height;
            else return false;
            widget.moveBy(viewport,frame,targetX-frame.x,targetY-frame.y);
        }
        UiBounds after=widget.bounds(editorContext);
        if (before.scale==widget.scale() && frame.x==after.x && frame.y==after.y) return true;
        if (!widget.commitEditorChange()) { widget.cancelEditorChange(); return false; }
        selectedModuleId=moduleId; inspecting=true;
        history.addLast(before); while(history.size()>50) history.removeFirst();
        return true;
    }

    public boolean nudge(int x, int y) {
        HudWidget widget = widgetById(selectedModuleId);
        if (!editing || widget == null || hasPointerCapture() || editorContext == null) return false;
        UiBounds frame = widget.bounds(editorContext);
        UndoStep before = new UndoStep(widget, frame);
        widget.moveBy(editorContext.viewport(), frame, x, y);
        if (!widget.commitEditorChange()) return false;
        history.addLast(before); while (history.size() > 50) history.removeFirst();
        return true;
    }

    public void toggleGrid() { grid = !grid; }
    public void toggleSnap() { snap = !snap; }

    public boolean undo() {
        if (hasPointerCapture() || editorContext == null || history.isEmpty()) return false;
        UndoStep step = history.removeLast();
        if (!step.widget.isVisible()) return false;
        step.widget.resizeToScale(step.scale);
        UiBounds current = step.widget.bounds(editorContext);
        step.widget.moveBy(editorContext.viewport(), current, step.frame.x - current.x, step.frame.y - current.y);
        return step.widget.commitEditorChange();
    }

    public HudManager(List<HudWidget> widgets) {
        this.widgets = Collections.unmodifiableList(new ArrayList<HudWidget>(widgets));
    }

    /** Only receives the game screen's accepted, current-revision CEF geometry. */
    public void updateEditorCanvas(UiBounds canvas, List<UiBounds> exclusions) {
        editorCanvas = canvas;
        editorExclusions = Collections.unmodifiableList(new ArrayList<UiBounds>(exclusions));
    }

    public void render(Minecraft minecraft, ScaledResolution resolution) {
        UiBounds viewport = MinecraftUiScale.viewport(minecraft);
        render(minecraft, new MinecraftUiRenderer(minecraft), viewport);
    }

    public void render(Minecraft minecraft, UiRenderer renderer, UiBounds viewport) {
        if (renderer == null || viewport == null) {
            return;
        }
        renderer.beginFrame(viewport);
        try {
            renderWidgets(new HudRenderContext(minecraft, renderer, viewport));
        } finally {
            renderer.endFrame();
        }
    }

    /** Called from the Opus HUD Editor page while its UI frame is already active. */
    public void renderEditor(Minecraft minecraft, UiRenderer renderer, UiBounds viewport, UiInput input) {
        HudRenderContext context = new HudRenderContext(minecraft, renderer, viewport);
        editorContext = context;
        editorBounds.clear();
        widgetFrames.clear();
        renderWidgets(context);
        if (!editing) return;
        if (grid) {
            for (int x = viewport.width / 20; x < viewport.width; x += Math.max(1, viewport.width / 20))
                renderer.line(x, 0, x, viewport.height, 1, 0x22FFFFFF);
            for (int y = viewport.height / 20; y < viewport.height; y += Math.max(1, viewport.height / 20))
                renderer.line(0, y, viewport.width, y, 1, 0x22FFFFFF);
        }
        // Editing is immediately discoverable, even before any hover. These
        // are outlines of the real enabled widgets, not preview placeholders.
        for (UiBounds bounds : editorBounds.values()) {
            renderer.border(bounds.inset(-FRAME_PADDING), 1, 0x889FA4AE);
        }
        UiBounds outline = preparePointer(input.mouseX, input.mouseY);
        if (outline == null) {
            return;
        }

        renderer.border(outline, 1, 0xFFE6E7EB);
        for (UiBounds handle : resizeControls) {
            renderResizeControl(renderer, handle.inset((HANDLE_HIT_SIZE - HANDLE_SIZE) / 2));
        }
        if (settingsControl.width > 0) renderSettingsControl(renderer, settingsControl);
        if (closeControl.width > 0) renderCloseControl(renderer, closeControl);
    }

    public EditorAction editorMouseDown(int mouseX, int mouseY, int button) {
        if (button != 0 || !editing) {
            return null;
        }
        // Mouse events can arrive before the next draw pass (notably during
        // route changes), so derive native hit targets from this exact point.
        preparePointer(mouseX, mouseY);
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
        Corner corner = resizeCornerAt(mouseX, mouseY);
        if (corner != null) {
            HudWidget widget = selectedOrHoveredWidget(mouseX, mouseY);
            if (widget != null) {
                selectedModuleId = widget.moduleId();
                activeModuleId = widget.moduleId();
                dragMode = DragMode.RESIZE;
                resizeStartFrame = widgetFrames.get(widget.moduleId());
                if (resizeStartFrame == null) {
                    return null;
                }
                resizeStartScale = widget.scale();
                pendingUndo = new UndoStep(widget, resizeStartFrame);
                resizeCorner = corner;
                resizeStartPointerX = mouseX;
                resizeStartPointerY = mouseY;
                lastMouseX = mouseX;
                lastMouseY = mouseY;
                return new EditorAction(true, null);
            }
        }

        String moduleId = findModuleAt(mouseX, mouseY);
        if (moduleId != null) {
            pendingUndo = new UndoStep(widgetById(moduleId), widgetFrames.get(moduleId));
            selectedModuleId = moduleId;
            activeModuleId = moduleId;
            dragMode = DragMode.MOVE;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return new EditorAction(true, null);
        }
        selectedModuleId = null;
        inspecting = false;
        clearControls();
        return null;
    }

    /** Whether the native editor, rather than CEF chrome, owns this point. */
    public boolean editorOwnsPoint(int mouseX, int mouseY) {
        return editing && (findModuleAt(mouseX, mouseY) != null || isOverEditorControl(mouseX, mouseY));
    }

    /** True while a native widget body or resize handle owns a left-button drag. */
    public boolean hasPointerCapture() {
        return dragMode != DragMode.NONE && activeModuleId != null;
    }

    public boolean editorMouseDrag(UiBounds viewport, int mouseX, int mouseY, int button) {
        if (button != 0 || dragMode == DragMode.NONE || activeModuleId == null) {
            return false;
        }
        HudWidget widget = widgetById(activeModuleId);
        if (widget == null || editorContext == null) {
            cancelPointerCapture();
            return false;
        }
        HudRenderContext context = new HudRenderContext(editorContext.minecraft(), editorContext.renderer(), viewport);
        UiBounds bounds = widget.bounds(context);
        int deltaX = mouseX - lastMouseX;
        int deltaY = mouseY - lastMouseY;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        boolean changed;
        if (dragMode == DragMode.MOVE) {
            if (snap) {
                deltaX = snapped(bounds.x + deltaX, bounds.width, viewport.width) - bounds.x;
                deltaY = snapped(bounds.y + deltaY, bounds.height, viewport.height) - bounds.y;
            }
            changed = widget.moveBy(viewport, bounds, deltaX, deltaY);
        } else {
            changed = resizeWidget(context, widget);
        }
        UiBounds current = widget.bounds(context);
        widgetFrames.put(activeModuleId, current);
        editorBounds.put(activeModuleId, current);
        return changed;
    }

    private boolean resizeWidget(HudRenderContext context, HudWidget widget) {
        UiBounds viewport = context.viewport();
        UiBounds start = resizeStartFrame;
        if (start == null || start.width <= 0 || start.height <= 0 || resizeCorner == null) {
            return false;
        }
        int deltaX = lastMouseX - resizeStartPointerX;
        int deltaY = lastMouseY - resizeStartPointerY;
        // Project the pointer onto the original corner diagonal. Both axes
        // contribute according to the widget's aspect ratio; neither can
        // stretch the content or abruptly replace the other while dragging.
        double squaredDiagonal = (double) start.width * start.width + (double) start.height * start.height;
        double ratio = 1.0D + (deltaX * (double) start.width * resizeCorner.xDirection
                + deltaY * (double) start.height * resizeCorner.yDirection) / squaredDiagonal;
        int targetScale = (int) Math.round(resizeStartScale * ratio);
        // Repair a retained position outside a smaller window before choosing
        // the fixed corner. Reachable starting frames keep that corner exact.
        int startX = Math.max(0, Math.min(viewport.width - start.width, start.x));
        int startY = Math.max(0, Math.min(viewport.height - start.height, start.y));
        int fixedX = Math.max(0, Math.min(viewport.width,
                resizeCorner.xDirection < 0 ? startX + start.width : startX));
        int fixedY = Math.max(0, Math.min(viewport.height,
                resizeCorner.yDirection < 0 ? startY + start.height : startY));
        int availableWidth = resizeCorner.xDirection < 0 ? fixedX : viewport.width - fixedX;
        int availableHeight = resizeCorner.yDirection < 0 ? fixedY : viewport.height - fixedY;
        int maxWidthScale = availableWidth * resizeStartScale / start.width;
        int maxHeightScale = availableHeight * resizeStartScale / start.height;
        targetScale = Math.min(targetScale, Math.min(maxWidthScale, maxHeightScale));
        boolean scaleChanged = widget.resizeToScale(targetScale);
        UiBounds resized = widget.bounds(context);
        int nextX = resizeCorner.xDirection < 0 ? fixedX - resized.width : fixedX;
        int nextY = resizeCorner.yDirection < 0 ? fixedY - resized.height : fixedY;
        boolean offsetsUpdated = widget.moveBy(viewport, resized, nextX - resized.x, nextY - resized.y);
        return scaleChanged || offsetsUpdated;
    }

    private static int snapped(int position, int size, int extent) {
        for (int target : new int[] {0, (extent - size) / 2, extent - size})
            if (Math.abs(position - target) <= 5) return target;
        return position;
    }

    public boolean editorMouseUp(int button) {
        if (button != 0 || dragMode == DragMode.NONE) {
            return false;
        }
        HudWidget widget = registeredWidgetById(activeModuleId);
        activeModuleId = null;
        dragMode = DragMode.NONE;
        resizeStartFrame = null;
        resizeCorner = null;
        resizeStartScale = 0;
        resizeStartPointerX = 0;
        resizeStartPointerY = 0;
        boolean committed = widget != null && widget.commitEditorChange();
        if (widget != null) inspecting=true;
        if (committed && pendingUndo != null) {
            history.addLast(pendingUndo);
            while (history.size() > 50) history.removeFirst();
        }
        pendingUndo = null;
        return committed;
    }

    /** Cancels any native drag/resize ownership when its route or screen closes. */
    public void cancelPointerCapture() {
        pendingUndo = null;
        HudWidget widget = registeredWidgetById(activeModuleId);
        if (widget != null) {
            widget.cancelEditorChange();
        }
        activeModuleId = null;
        dragMode = DragMode.NONE;
        resizeStartFrame = null;
        resizeCorner = null;
        resizeStartScale = 0;
        resizeStartPointerX = 0;
        resizeStartPointerY = 0;
        selectedModuleId = null;
        inspecting = false;
        clearControls();
    }

    private void renderWidgets(HudRenderContext context) {
        for (HudWidget widget : widgets) {
            if (widget.isVisible()) {
                widget.renderNormal(context);
                UiBounds measured = widget.bounds(context);
                widgetFrames.put(widget.moduleId(), measured);
                editorBounds.put(widget.moduleId(), editorBounds(measured));
            }
        }
    }

    private String findModuleAt(int mouseX, int mouseY) {
        for (int index = widgets.size() - 1; index >= 0; index--) {
            HudWidget widget = widgets.get(index);
            UiBounds bounds = editorBounds.get(widget.moduleId());
            if (widget.isVisible() && bounds != null) {
                UiBounds outline = bounds.inset(-FRAME_PADDING);
                if (outline.contains(mouseX, mouseY)) return widget.moduleId();
                for (Corner corner : Corner.values()) {
                    if (cornerControl(outline, corner).contains(mouseX, mouseY)) return widget.moduleId();
                }
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
        HudWidget widget = registeredWidgetById(moduleId);
        return widget != null && widget.isVisible() ? widget : null;
    }

    private HudWidget registeredWidgetById(String moduleId) {
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
        java.util.Arrays.fill(resizeControls, null);
    }

    /** Recomputes the native editor chrome and returns its outline. */
    private UiBounds preparePointer(int mouseX, int mouseY) {
        String hoveredModuleId = findModuleAt(mouseX, mouseY);
        String previousChromeModuleId = chromeModuleId;
        String nextChromeModuleId = selectedModuleId != null ? selectedModuleId : hoveredModuleId;
        // The controls sit just outside the widget bounds. Retain their owner
        // while the pointer crosses that small gap.
        if (nextChromeModuleId == null
                && previousChromeModuleId != null
                && isOverEditorControl(mouseX, mouseY)) {
            nextChromeModuleId = previousChromeModuleId;
        }
        chromeModuleId = nextChromeModuleId;
        if (nextChromeModuleId == null) {
            clearControls();
            return null;
        }

        UiBounds bounds = editorBounds.get(nextChromeModuleId);
        if (bounds == null) {
            clearControls();
            return null;
        }
        UiBounds outline = bounds.inset(-FRAME_PADDING);
        for (Corner corner : Corner.values()) {
            resizeControls[corner.ordinal()] = cornerControl(outline, corner);
        }
        // Selected actions retain their owner while crossing the gap or an
        // overlapping widget; hover only reveals the frame and its handles.
        settingsControl = new UiBounds(0, 0, 0, 0);
        closeControl = new UiBounds(0, 0, 0, 0);
        if (selectedModuleId != null && selectedModuleId.equals(nextChromeModuleId)) {
            UiBounds toolbar = toolbarBounds(outline);
            if (toolbar != null) {
                settingsControl = new UiBounds(toolbar.x, toolbar.y, CONTROL_SIZE, CONTROL_SIZE);
                closeControl = new UiBounds(toolbar.x + CONTROL_SIZE + 4, toolbar.y, CONTROL_SIZE, CONTROL_SIZE);
            }
        }
        return outline;
    }

    private UiBounds toolbarBounds(UiBounds outline) {
        if (editorContext == null) return null;
        UiBounds viewport = editorContext.viewport();
        UiBounds area = editorCanvas != null && editorCanvas.width > 0 && editorCanvas.height > 0
                ? editorCanvas.intersect(viewport) : viewport;
        int width = CONTROL_SIZE * 2 + 4;
        int centeredX = outline.x + (outline.width - width) / 2;
        int centeredY = outline.y + (outline.height - CONTROL_SIZE) / 2;
        int[][] candidates = {{centeredX, outline.y - CONTROL_SIZE - 8},
                {centeredX, outline.bottom() + 8},
                {outline.right() + 8, centeredY}, {outline.x - width - 8, centeredY}};
        for (int[] point : candidates) {
            int x = Math.max(area.x, Math.min(area.right() - width, point[0]));
            int y = Math.max(area.y, Math.min(area.bottom() - CONTROL_SIZE, point[1]));
            UiBounds candidate = new UiBounds(x, y, width, CONTROL_SIZE);
            if (candidate.right() > area.right() || candidate.bottom() > area.bottom()
                    || overlaps(candidate, outline)) continue;
            boolean blocked = false;
            for (UiBounds handle : resizeControls) {
                if (handle != null && overlaps(candidate, handle)) { blocked = true; break; }
            }
            if (blocked) continue;
            for (UiBounds exclusion : editorExclusions) {
                if (overlaps(candidate, exclusion)) { blocked = true; break; }
            }
            if (!blocked) return candidate;
        }
        return null;
    }

    private static boolean overlaps(UiBounds first, UiBounds second) {
        UiBounds intersection = first.intersect(second);
        return intersection.width > 0 && intersection.height > 0;
    }

    private UiBounds cornerControl(UiBounds outline, Corner corner) {
        int x = corner.xDirection < 0 ? outline.x : outline.right();
        int y = corner.yDirection < 0 ? outline.y : outline.bottom();
        if (editorContext != null) {
            UiBounds viewport = editorContext.viewport();
            x = Math.max(viewport.x + HANDLE_HIT_SIZE / 2,
                    Math.min(viewport.right() - HANDLE_HIT_SIZE / 2, x));
            y = Math.max(viewport.y + HANDLE_HIT_SIZE / 2,
                    Math.min(viewport.bottom() - HANDLE_HIT_SIZE / 2, y));
        }
        return new UiBounds(x - HANDLE_HIT_SIZE / 2, y - HANDLE_HIT_SIZE / 2,
                HANDLE_HIT_SIZE, HANDLE_HIT_SIZE);
    }

    private Corner resizeCornerAt(int x, int y) {
        Corner nearest = null;
        long distance = Long.MAX_VALUE;
        for (Corner corner : Corner.values()) {
            UiBounds control = resizeControls[corner.ordinal()];
            if (control == null || !control.contains(x, y)) continue;
            int dx = x - (control.x + control.width / 2);
            int dy = y - (control.y + control.height / 2);
            long candidate = (long) dx * dx + (long) dy * dy;
            if (candidate < distance) {
                distance = candidate;
                nearest = corner;
            }
        }
        return nearest;
    }

    /**
     * Selection geometry is the content itself; symmetric padding belongs
     * only to editor chrome, never to movement or proportional resize math.
     */
    private static UiBounds editorBounds(UiBounds widgetBounds) {
        return widgetBounds;
    }

    private boolean isOverEditorControl(int mouseX, int mouseY) {
        if (closeControl.contains(mouseX, mouseY) || settingsControl.contains(mouseX, mouseY)) return true;
        for (UiBounds control : resizeControls) {
            if (control != null && control.contains(mouseX, mouseY)) return true;
        }
        return false;
    }

    private static void renderResizeControl(UiRenderer renderer, UiBounds bounds) {
        renderer.roundedRect(bounds, 2, 0xE513141A);
        renderer.border(bounds, 1, 0xFFE6E7EB);
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
