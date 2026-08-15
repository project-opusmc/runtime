package dev.rbw.client.ui;

/** Immutable logical GUI-space bounds shared by layout, input, and rendering. */
public final class UiBounds {
    public final int x;
    public final int y;
    public final int width;
    public final int height;

    public UiBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
    }

    public int right() {
        return x + width;
    }

    public int bottom() {
        return y + height;
    }

    public boolean contains(int pointX, int pointY) {
        return pointX >= x && pointY >= y && pointX < right() && pointY < bottom();
    }

    public UiBounds inset(int amount) {
        return inset(amount, amount, amount, amount);
    }

    public UiBounds inset(int left, int top, int right, int bottom) {
        return new UiBounds(
                x + left,
                y + top,
                Math.max(0, width - left - right),
                Math.max(0, height - top - bottom));
    }

    public UiBounds intersect(UiBounds other) {
        int nextLeft = Math.max(x, other.x);
        int nextTop = Math.max(y, other.y);
        int nextRight = Math.min(right(), other.right());
        int nextBottom = Math.min(bottom(), other.bottom());
        return new UiBounds(
                nextLeft,
                nextTop,
                Math.max(0, nextRight - nextLeft),
                Math.max(0, nextBottom - nextTop));
    }
}
