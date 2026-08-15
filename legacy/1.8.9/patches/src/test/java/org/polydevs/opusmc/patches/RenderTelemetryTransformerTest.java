package org.polydevs.opusmc.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class RenderTelemetryTransformerTest {
    @Test
    void injectsRenderHooksAtEveryReturnOfTheVerifiedMethod() {
        byte[] original = TelemetryTransformerTestSupport.classWithMethods(
                RenderTelemetryTransformer.TARGET_CLASS,
                TelemetryTransformerTestSupport.methodWithTwoReturns(
                        RenderTelemetryTransformer.RENDER_METHOD,
                        RenderTelemetryTransformer.RENDER_DESCRIPTOR));

        byte[] transformed = new RenderTelemetryTransformer().transform(
                RenderTelemetryTransformer.TARGET_CLASS, original);

        assertEquals(1, TelemetryTransformerTestSupport.countTelemetryCalls(
                transformed, "renderStarted", "()V"));
        assertEquals(2, TelemetryTransformerTestSupport.countTelemetryCalls(
                transformed, "renderFinished", "()V"));
    }

    @Test
    void failsClosedWhenTheRenderAnchorIsMissing() {
        byte[] incomplete = TelemetryTransformerTestSupport.classWithMethods(
                RenderTelemetryTransformer.TARGET_CLASS,
                TelemetryTransformerTestSupport.method(
                        RenderTelemetryTransformer.RENDER_METHOD,
                        "()V"));

        assertThrows(
                IllegalStateException.class,
                () -> new RenderTelemetryTransformer().transform(
                        RenderTelemetryTransformer.TARGET_CLASS, incomplete));
    }
}
