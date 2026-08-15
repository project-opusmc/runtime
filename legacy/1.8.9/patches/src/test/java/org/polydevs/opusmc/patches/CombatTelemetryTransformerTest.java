package org.polydevs.opusmc.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class CombatTelemetryTransformerTest {
    @Test
    void injectsAttackPacketHookAtVerifiedControllerMethod() {
        byte[] original = TelemetryTransformerTestSupport.classWithMethods(
                CombatTelemetryTransformer.PLAYER_CONTROLLER_CLASS,
                TelemetryTransformerTestSupport.method(
                        CombatTelemetryTransformer.ATTACK_METHOD,
                        CombatTelemetryTransformer.ATTACK_DESCRIPTOR));

        byte[] transformed = new CombatTelemetryTransformer().transform(
                CombatTelemetryTransformer.PLAYER_CONTROLLER_CLASS, original);

        assertEquals(1, TelemetryTransformerTestSupport.countTelemetryCalls(
                transformed, "attackPacketQueued", "()V"));
    }

    @Test
    void injectsEntityStatusHookWithThePacketArgument() {
        byte[] original = TelemetryTransformerTestSupport.classWithMethods(
                CombatTelemetryTransformer.NET_HANDLER_CLASS,
                TelemetryTransformerTestSupport.method(
                        CombatTelemetryTransformer.ATTACK_METHOD,
                        CombatTelemetryTransformer.ENTITY_STATUS_DESCRIPTOR));

        byte[] transformed = new CombatTelemetryTransformer().transform(
                CombatTelemetryTransformer.NET_HANDLER_CLASS, original);

        assertEquals(1, TelemetryTransformerTestSupport.countMethodCalls(
                transformed, org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "gi", "a", "()B"));
        assertEquals(1, TelemetryTransformerTestSupport.countTelemetryCalls(
                transformed, "entityStatus", "(I)V"));
    }

    @Test
    void failsClosedWhenTheAttackControllerAnchorIsMissing() {
        byte[] incomplete = TelemetryTransformerTestSupport.classWithMethods(
                CombatTelemetryTransformer.PLAYER_CONTROLLER_CLASS,
                TelemetryTransformerTestSupport.method(CombatTelemetryTransformer.ATTACK_METHOD, "()V"));

        assertThrows(
                IllegalStateException.class,
                () -> new CombatTelemetryTransformer().transform(
                        CombatTelemetryTransformer.PLAYER_CONTROLLER_CLASS, incomplete));
    }

    @Test
    void failsClosedWhenTheEntityStatusAnchorIsMissing() {
        byte[] incomplete = TelemetryTransformerTestSupport.classWithMethods(
                CombatTelemetryTransformer.NET_HANDLER_CLASS,
                TelemetryTransformerTestSupport.method(CombatTelemetryTransformer.ATTACK_METHOD, "()V"));

        assertThrows(
                IllegalStateException.class,
                () -> new CombatTelemetryTransformer().transform(
                        CombatTelemetryTransformer.NET_HANDLER_CLASS, incomplete));
    }
}
