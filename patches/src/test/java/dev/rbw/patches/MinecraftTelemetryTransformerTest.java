package dev.rbw.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class MinecraftTelemetryTransformerTest {
    @Test
    void injectsFrameTickAndInputHooksAtVerifiedAnchors() {
        byte[] original = TelemetryTransformerTestSupport.classWithMethods(
                MinecraftTelemetryTransformer.TARGET_CLASS,
                TelemetryTransformerTestSupport.methodWithTwoReturns(
                        MinecraftTelemetryTransformer.FRAME_LOOP_METHOD,
                        MinecraftTelemetryTransformer.VOID_DESCRIPTOR),
                TelemetryTransformerTestSupport.method(
                        MinecraftTelemetryTransformer.TICK_METHOD,
                        MinecraftTelemetryTransformer.VOID_DESCRIPTOR),
                TelemetryTransformerTestSupport.method(
                        MinecraftTelemetryTransformer.LEFT_CLICK_METHOD,
                        MinecraftTelemetryTransformer.VOID_DESCRIPTOR));

        byte[] transformed = new MinecraftTelemetryTransformer().transform(
                MinecraftTelemetryTransformer.TARGET_CLASS, original);

        assertEquals(1, TelemetryTransformerTestSupport.countTelemetryCalls(
                transformed, "frameStarted", "()V"));
        assertEquals(2, TelemetryTransformerTestSupport.countTelemetryCalls(
                transformed, "frameFinished", "()V"));
        assertEquals(1, TelemetryTransformerTestSupport.countTelemetryCalls(
                transformed, "tickStarted", "()V"));
        assertEquals(1, TelemetryTransformerTestSupport.countTelemetryCalls(
                transformed, "tickFinished", "()V"));
        assertEquals(1, TelemetryTransformerTestSupport.countTelemetryCalls(
                transformed, "attackInput", "()V"));
    }

    @Test
    void failsClosedWhenTheLeftClickAnchorIsMissing() {
        byte[] incomplete = TelemetryTransformerTestSupport.classWithMethods(
                MinecraftTelemetryTransformer.TARGET_CLASS,
                TelemetryTransformerTestSupport.method(
                        MinecraftTelemetryTransformer.FRAME_LOOP_METHOD,
                        MinecraftTelemetryTransformer.VOID_DESCRIPTOR),
                TelemetryTransformerTestSupport.method(
                        MinecraftTelemetryTransformer.TICK_METHOD,
                        MinecraftTelemetryTransformer.VOID_DESCRIPTOR));

        assertThrows(
                IllegalStateException.class,
                () -> new MinecraftTelemetryTransformer().transform(
                        MinecraftTelemetryTransformer.TARGET_CLASS, incomplete));
    }
}
