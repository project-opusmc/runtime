package dev.rbw.client.module;

/** Runtime functionality; distinct from both a HUD widget and a UI component. */
public interface ClientModule {
    String id();

    String displayName();

    boolean isEnabled();

    boolean setEnabled(boolean enabled);
}
