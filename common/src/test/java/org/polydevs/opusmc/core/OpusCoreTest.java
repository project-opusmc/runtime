package org.polydevs.opusmc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class OpusCoreTest {
    @Test
    void lifecycleRejectsUnexpectedTransition() {
        assertEquals(LifecycleStage.CREATED, OpusCore.stage());
        assertThrows(
                IllegalStateException.class,
                () -> OpusCore.transition(LifecycleStage.RUNNING, LifecycleStage.SHUTDOWN));
    }
}

