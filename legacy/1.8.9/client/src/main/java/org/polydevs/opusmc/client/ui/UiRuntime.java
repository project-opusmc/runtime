package org.polydevs.opusmc.client.ui;

/**
 * Owns page routing, lifecycle and input dispatch. Minecraft's GuiScreen is
 * intentionally reduced to a thin adapter around this runtime.
 */
public final class UiRuntime {
    public interface PageFactory {
        UiPage create(UiRoute route, UiRuntime runtime);
    }

    private final UiRenderer renderer;
    private final PageFactory pageFactory;
    private UiRoute route;
    private UiPage page;
    private UiBounds viewport = new UiBounds(0, 0, 0, 0);
    private boolean closeRequested;

    public UiRuntime(UiRenderer renderer, PageFactory pageFactory, UiRoute initialRoute) {
        if (renderer == null || pageFactory == null || initialRoute == null) {
            throw new IllegalArgumentException("renderer, pageFactory and initialRoute are required");
        }
        this.renderer = renderer;
        this.pageFactory = pageFactory;
        navigate(initialRoute);
    }

    public UiRoute route() {
        return route;
    }

    public UiBounds viewport() {
        return viewport;
    }

    public void resize(int width, int height) {
        viewport = new UiBounds(0, 0, width, height);
        if (page != null) {
            page.layout(viewport);
        }
    }

    public void navigate(UiRoute nextRoute) {
        if (nextRoute == null) {
            throw new IllegalArgumentException("nextRoute is required");
        }
        if (nextRoute.equals(route) && page != null) {
            return;
        }
        if (page != null) {
            page.dispose();
        }
        route = nextRoute;
        page = pageFactory.create(nextRoute, this);
        if (page == null) {
            throw new IllegalStateException("page factory returned null for " + nextRoute.kind());
        }
        if (page != null) {
            page.layout(viewport);
        }
    }

    public void requestClose() {
        closeRequested = true;
    }

    public boolean consumeCloseRequest() {
        boolean requested = closeRequested;
        closeRequested = false;
        return requested;
    }

    public void render(int mouseX, int mouseY, float partialTicks) {
        if (page == null) {
            return;
        }
        renderer.beginFrame(viewport);
        try {
            page.render(renderer, new UiInput(mouseX, mouseY, partialTicks));
        } finally {
            renderer.endFrame();
        }
    }

    public boolean mouseDown(int mouseX, int mouseY, int button) {
        return page != null && page.mouseDown(mouseX, mouseY, button);
    }

    public boolean mouseUp(int mouseX, int mouseY, int button) {
        return page != null && page.mouseUp(mouseX, mouseY, button);
    }

    public boolean mouseDrag(int mouseX, int mouseY, int button, long elapsedMillis) {
        return page != null && page.mouseDrag(mouseX, mouseY, button, elapsedMillis);
    }

    public boolean scroll(int amount) {
        return page != null && page.scroll(amount);
    }

    public boolean keyTyped(char typedCharacter, int keyCode) {
        return page != null && page.keyTyped(typedCharacter, keyCode);
    }

    public void dispose() {
        if (page != null) {
            page.dispose();
            page = null;
        }
    }
}
