package org.polydevs.opusmc.core;

import java.util.concurrent.atomic.AtomicReference;

public final class OpusCore {
    private static final AtomicReference<LifecycleStage> STAGE =
            new AtomicReference<LifecycleStage>(LifecycleStage.CREATED);

    private OpusCore() {
    }

    public static LifecycleStage stage() {
        return STAGE.get();
    }

    public static void transition(LifecycleStage expected, LifecycleStage next) {
        if (!STAGE.compareAndSet(expected, next)) {
            throw new IllegalStateException(
                    "Invalid Opus lifecycle transition: expected " + expected + ", actual " + STAGE.get()
                            + ", requested " + next);
        }
        System.out.println("[OPUS/CORE] lifecycle=" + next);
        ClientTelemetry.lifecycle(next.name());
    }
}
