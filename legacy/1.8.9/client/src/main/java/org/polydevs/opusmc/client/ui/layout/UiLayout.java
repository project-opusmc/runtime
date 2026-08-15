package org.polydevs.opusmc.client.ui.layout;

import org.polydevs.opusmc.client.ui.UiBounds;
import org.polydevs.opusmc.client.ui.component.UiComponent;
import java.util.List;

/** Converts a component tree into logical GUI-space bounds. */
public interface UiLayout {
    void layout(UiBounds bounds, List<UiComponent> children);
}
