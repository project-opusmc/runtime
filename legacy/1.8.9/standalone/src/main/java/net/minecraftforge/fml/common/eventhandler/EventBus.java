package net.minecraftforge.fml.common.eventhandler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class EventBus {
    private static final class Listener {
        final Object target;
        final Method method;
        final EventPriority priority;

        Listener(Object target, Method method, EventPriority priority) {
            this.target = target;
            this.method = method;
            this.priority = priority;
        }

        void invoke(Event event) {
            try {
                method.invoke(target, event);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    private static final Comparator<Listener> PRIORITY_COMPARATOR = new Comparator<Listener>() {
        @Override
        public int compare(Listener a, Listener b) {
            return Integer.compare(a.priority.ordinal(), b.priority.ordinal());
        }
    };

    private final Map<Class<?>, List<Listener>> listeners = new ConcurrentHashMap<Class<?>, List<Listener>>();

    public void register(Object target) {
        if (target == null) {
            return;
        }
        for (Method method : target.getClass().getMethods()) {
            SubscribeEvent annotation = method.getAnnotation(SubscribeEvent.class);
            if (annotation != null && method.getParameterTypes().length == 1) {
                Class<?> eventType = method.getParameterTypes()[0];
                method.setAccessible(true);
                List<Listener> list = listeners.get(eventType);
                if (list == null) {
                    list = new CopyOnWriteArrayList<Listener>();
                    listeners.put(eventType, list);
                }
                boolean alreadyPresent = false;
                for (Listener existing : list) {
                    if (existing.target == target && existing.method.equals(method)) {
                        alreadyPresent = true;
                        break;
                    }
                }
                if (!alreadyPresent) {
                    list.add(new Listener(target, method, annotation.priority()));
                }
            }
        }
    }

    public void unregister(Object target) {
        if (target == null) {
            return;
        }
        for (List<Listener> list : listeners.values()) {
            List<Listener> toRemove = new ArrayList<Listener>();
            for (Listener l : list) {
                if (l.target == target) {
                    toRemove.add(l);
                }
            }
            list.removeAll(toRemove);
        }
    }

    public boolean post(Event event) {
        if (event == null) {
            return false;
        }
        List<Listener> matching = new ArrayList<Listener>();
        Class<?> eventClass = event.getClass();
        for (Map.Entry<Class<?>, List<Listener>> entry : listeners.entrySet()) {
            if (entry.getKey().isAssignableFrom(eventClass)) {
                matching.addAll(entry.getValue());
            }
        }
        if (!matching.isEmpty()) {
            if (matching.size() > 1) {
                Collections.sort(matching, PRIORITY_COMPARATOR);
            }
            for (Listener listener : matching) {
                listener.invoke(event);
            }
        }
        return event.isCanceled();
    }
}
