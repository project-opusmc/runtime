package dev.rbw.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class NetworkTelemetryTransformerTest {
    private static final String CHANNEL_CONTEXT = "Lio/netty/channel/ChannelHandlerContext;";
    private static final String PACKET = "Lff;";

    @Test
    void injectsConnectionAndPacketHooksAtAllVerifiedAnchors() {
        byte[] original = networkManagerClass(true, true, true, true);

        byte[] transformed = new NetworkTelemetryTransformer().transform(
                NetworkTelemetryTransformer.TARGET_CLASS, original);

        assertEquals(1, TelemetryTransformerTestSupport.countTelemetryCalls(
                transformed, "connectionOpened", "()V"));
        assertEquals(1, TelemetryTransformerTestSupport.countTelemetryCalls(
                transformed, "connectionClosed", "()V"));
        assertEquals(1, TelemetryTransformerTestSupport.countTelemetryCalls(
                transformed, "outboundPacket", "(Ljava/lang/Object;)V"));
        assertEquals(1, TelemetryTransformerTestSupport.countTelemetryObjectCallsWithAload(
                transformed, "outboundPacket", 1));
        assertEquals(1, TelemetryTransformerTestSupport.countTelemetryCalls(
                transformed, "inboundPacket", "(Ljava/lang/Object;)V"));
        assertEquals(1, TelemetryTransformerTestSupport.countTelemetryObjectCallsWithAload(
                transformed, "inboundPacket", 2));
    }

    @Test
    void failsClosedWhenAnyRequiredNetworkAnchorIsMissing() {
        assertMissingAnchor(false, true, true, true);
        assertMissingAnchor(true, false, true, true);
        assertMissingAnchor(true, true, false, true);
        assertMissingAnchor(true, true, true, false);
    }

    private static void assertMissingAnchor(
            boolean includeActive,
            boolean includeInactive,
            boolean includeOutbound,
            boolean includeInbound) {
        byte[] incomplete = networkManagerClass(includeActive, includeInactive, includeOutbound, includeInbound);
        assertThrows(
                IllegalStateException.class,
                () -> new NetworkTelemetryTransformer().transform(
                        NetworkTelemetryTransformer.TARGET_CLASS, incomplete));
    }

    private static byte[] networkManagerClass(
            boolean includeActive,
            boolean includeInactive,
            boolean includeOutbound,
            boolean includeInbound) {
        return TelemetryTransformerTestSupport.classWithMethods(
                NetworkTelemetryTransformer.TARGET_CLASS,
                includeActive
                        ? TelemetryTransformerTestSupport.method("channelActive", "(" + CHANNEL_CONTEXT + ")V")
                        : null,
                includeInactive
                        ? TelemetryTransformerTestSupport.method("channelInactive", "(" + CHANNEL_CONTEXT + ")V")
                        : null,
                includeOutbound
                        ? TelemetryTransformerTestSupport.method("a", "(" + PACKET + ")V")
                        : null,
                includeInbound
                        ? TelemetryTransformerTestSupport.method("a", "(" + CHANNEL_CONTEXT + PACKET + ")V")
                        : null);
    }
}
