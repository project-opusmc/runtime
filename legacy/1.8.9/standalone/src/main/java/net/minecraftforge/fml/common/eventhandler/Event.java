package net.minecraftforge.fml.common.eventhandler;

public class Event {
    private boolean isCanceled = false;

    public boolean isCancelable() {
        return false;
    }

    public boolean isCanceled() {
        return isCanceled;
    }

    public void setCanceled(boolean cancel) {
        this.isCanceled = cancel;
    }

    public enum Result {
        DENY,
        DEFAULT,
        ALLOW
    }
}
