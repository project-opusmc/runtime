package dev.rbw.client.ui.layout;

import dev.rbw.client.ui.UiBounds;
import dev.rbw.client.ui.component.UiComponent;
import java.util.List;

/** Overlays every child on the same bounds. */
public final class UiStack implements UiLayout {
    @Override
    public void layout(UiBounds bounds, List<UiComponent> children) {
        for (UiComponent child : children) {
            child.layout(bounds);
        }
    }
}
