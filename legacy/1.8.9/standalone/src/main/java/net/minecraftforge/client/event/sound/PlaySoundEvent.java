package net.minecraftforge.client.event.sound;

import net.minecraftforge.fml.common.eventhandler.Event;

public class PlaySoundEvent extends Event {
    public final String name;
    public final Object sound;
    public final Object category;
    public Object result;

    public PlaySoundEvent(Object sound, String name, Object category) {
        this.sound = sound;
        this.name = name;
        this.category = category;
    }
}
