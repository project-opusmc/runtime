package dev.rbw.client.ui.layout;

import dev.rbw.client.ui.UiBounds;
import dev.rbw.client.ui.component.UiComponent;
import java.util.List;

/** Simple vertical layout; components with no preferred height share remainder. */
public final class UiColumn implements UiLayout {
    private final int spacing;

    public UiColumn(int spacing) {
        this.spacing = Math.max(0, spacing);
    }

    @Override
    public void layout(UiBounds bounds, List<UiComponent> children) {
        int totalFixed = Math.max(0, children.size() - 1) * spacing;
        int flexible = 0;
        for (UiComponent child : children) {
            int preferred = child.preferredHeight();
            if (preferred > 0) {
                totalFixed += preferred;
            } else {
                flexible++;
            }
        }
        int available = Math.max(0, bounds.height - totalFixed);
        int flexibleHeight = flexible == 0 ? 0 : available / flexible;
        int remainder = flexible == 0 ? 0 : available % flexible;
        int y = bounds.y;
        for (UiComponent child : children) {
            int childHeight = child.preferredHeight();
            if (childHeight <= 0) {
                childHeight = flexibleHeight + (remainder-- > 0 ? 1 : 0);
            }
            child.layout(new UiBounds(bounds.x, y, bounds.width, childHeight));
            y += childHeight + spacing;
        }
    }
}
