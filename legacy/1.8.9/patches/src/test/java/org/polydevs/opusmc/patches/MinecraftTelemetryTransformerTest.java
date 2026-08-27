package org.polydevs.opusmc.patches;

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
                        MinecraftTelemetryTransformer.VOID_DESCRIPTOR),
                TelemetryTransformerTestSupport.methodWithIntConstant(
                        MinecraftTelemetryTransformer.FRAME_RATE_METHOD,
                        MinecraftTelemetryTransformer.FRAME_RATE_DESCRIPTOR,
                        30));

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
        assertEquals(1, TelemetryTransformerTestSupport.countStaticCalls(
                transformed,
                MinecraftTelemetryTransformer.LIVE_RESIZE_DISPLAY_OWNER,
                MinecraftTelemetryTransformer.LIVE_RESIZE_FRAME_RATE_METHOD,
                MinecraftTelemetryTransformer.LIVE_RESIZE_FRAME_RATE_DESCRIPTOR));
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
                        MinecraftTelemetryTransformer.VOID_DESCRIPTOR),
                TelemetryTransformerTestSupport.methodWithIntConstant(
                        MinecraftTelemetryTransformer.FRAME_RATE_METHOD,
                        MinecraftTelemetryTransformer.FRAME_RATE_DESCRIPTOR,
                        30));

        assertThrows(
                IllegalStateException.class,
                () -> new MinecraftTelemetryTransformer().transform(
                        MinecraftTelemetryTransformer.TARGET_CLASS, incomplete));
    }
}
