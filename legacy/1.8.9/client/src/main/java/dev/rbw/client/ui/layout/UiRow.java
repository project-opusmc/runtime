package dev.rbw.client.ui.layout;

import dev.rbw.client.ui.UiBounds;
import dev.rbw.client.ui.component.UiComponent;
import java.util.List;

/** Simple horizontal layout; components with no preferred width share remainder. */
public final class UiRow implements UiLayout {
    private final int spacing;

    public UiRow(int spacing) {
        this.spacing = Math.max(0, spacing);
    }

    @Override
    public void layout(UiBounds bounds, List<UiComponent> children) {
        int totalFixed = Math.max(0, children.size() - 1) * spacing;
        int flexible = 0;
        for (UiComponent child : children) {
            int preferred = child.preferredWidth();
            if (preferred > 0) {
                totalFixed += preferred;
            } else {
                flexible++;
            }
        }
        int available = Math.max(0, bounds.width - totalFixed);
        int flexibleWidth = flexible == 0 ? 0 : available / flexible;
        int remainder = flexible == 0 ? 0 : available % flexible;
        int x = bounds.x;
        for (UiComponent child : children) {
            int childWidth = child.preferredWidth();
            if (childWidth <= 0) {
                childWidth = flexibleWidth + (remainder-- > 0 ? 1 : 0);
            }
            child.layout(new UiBounds(x, bounds.y, childWidth, bounds.height));
            x += childWidth + spacing;
        }
    }
}
