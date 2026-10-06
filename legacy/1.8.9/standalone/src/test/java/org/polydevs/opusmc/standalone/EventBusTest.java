package org.polydevs.opusmc.standalone;

import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.fml.common.eventhandler.Cancelable;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class EventBusTest {

    static class CustomBaseEvent extends Event {}

    @Cancelable
    static class CustomCancelableEvent extends CustomBaseEvent {}

    static class TestListener {
        final List<String> log = new ArrayList<String>();

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onLowest(CustomBaseEvent event) {
            log.add("LOWEST");
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onHighest(CustomBaseEvent event) {
            log.add("HIGHEST");
        }

        @SubscribeEvent
        public void onNormal(CustomBaseEvent event) {
            log.add("NORMAL");
        }
    }

    @Test
    public void testEventBusOrderingAndInheritance() {
        EventBus bus = new EventBus();
        TestListener listener = new TestListener();
        bus.register(listener);
        // Registering twice should be deduplicated
        bus.register(listener);

        CustomCancelableEvent event = new CustomCancelableEvent();
        boolean canceled = bus.post(event);

        assertFalse(canceled);
        assertEquals(3, listener.log.size());
        assertEquals("HIGHEST", listener.log.get(0));
        assertEquals("NORMAL", listener.log.get(1));
        assertEquals("LOWEST", listener.log.get(2));
    }

    @Test
    public void testEventCancellation() {
        EventBus bus = new EventBus();
        bus.register(new Object() {
            @SubscribeEvent
            public void onEvent(CustomCancelableEvent event) {
                event.setCanceled(true);
            }
        });

        CustomCancelableEvent event = new CustomCancelableEvent();
        assertTrue(bus.post(event));
        assertTrue(event.isCanceled());
    }
}
