package dev.rbw.client.ui.component;

import dev.rbw.client.ui.UiInput;
import dev.rbw.client.ui.UiRenderer;
import dev.rbw.client.ui.layout.UiLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A component container with one explicit layout strategy. */
public final class UiContainer extends UiComponent {
    private final UiLayout layout;
    private final List<UiComponent> children = new ArrayList<UiComponent>();

    public UiContainer(UiLayout layout) {
        if (layout == null) {
            throw new IllegalArgumentException("layout is required");
        }
        this.layout = layout;
    }

    public UiContainer add(UiComponent child) {
        if (child == null) {
            throw new IllegalArgumentException("child is required");
        }
        children.add(child);
        return this;
    }

    public List<UiComponent> children() {
        return Collections.unmodifiableList(children);
    }

    @Override
    public void layout(dev.rbw.client.ui.UiBounds nextBounds) {
        super.layout(nextBounds);
        layout.layout(nextBounds, children);
    }

    @Override
    public void render(UiRenderer renderer, UiInput input) {
        for (UiComponent child : children) {
            child.render(renderer, input);
        }
    }
}
