package dev.rbw.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class RbwCoreTest {
    @Test
    void lifecycleRejectsUnexpectedTransition() {
        assertEquals(LifecycleStage.CREATED, RbwCore.stage());
        assertThrows(
                IllegalStateException.class,
                () -> RbwCore.transition(LifecycleStage.RUNNING, LifecycleStage.SHUTDOWN));
    }
}

