package org.polydevs.opusmc.client.ui.layout;

import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.component.UiComponent;
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
