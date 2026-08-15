package dev.rbw.client.ui.layout;

import dev.rbw.client.ui.UiBounds;
import dev.rbw.client.ui.component.UiComponent;
import java.util.List;

/** Converts a component tree into logical GUI-space bounds. */
public interface UiLayout {
    void layout(UiBounds bounds, List<UiComponent> children);
}
