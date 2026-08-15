package org.polydevs.opusmc.client.ui.component;

import org.polydevs.opusmc.client.ui.UiInput;
import org.polydevs.opusmc.client.ui.UiRenderer;
import org.polydevs.opusmc.client.ui.layout.UiLayout;
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
    public void layout(org.polydevs.opusmc.client.ui.UiBounds nextBounds) {
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
