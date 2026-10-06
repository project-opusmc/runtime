package org.polydevs.opusmc.client;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

/** Client-thread work drained outside Minecraft's lock-held scheduledTasks loop. */
final class ClientFrameTasks {
    private static final int MAX_TASKS_PER_FRAME = 64;
    private final ConcurrentLinkedQueue<FutureTask<?>> pending =
            new ConcurrentLinkedQueue<FutureTask<?>>();
    private volatile boolean closed;

    <T> FutureTask<T> submit(Callable<T> action) {
        FutureTask<T> task = new FrameTask<T>(this, action);
        if (closed) {
            task.cancel(false);
            return task;
        }
        pending.add(task);
        if (closed && pending.remove(task)) task.cancel(false);
        return task;
    }

    FutureTask<Void> submit(Runnable action) {
        FutureTask<Void> task = new FrameTask<Void>(this, new Callable<Void>() {
            @Override public Void call() {
                action.run();
                return null;
            }
        });
        if (closed) {
            task.cancel(false);
            return task;
        }
        pending.add(task);
        if (closed && pending.remove(task)) task.cancel(false);
        return task;
    }

    void drain() {
        for (int count = 0; count < MAX_TASKS_PER_FRAME && !closed; count++) {
            FutureTask<?> task = pending.poll();
            if (task == null) return;
            task.run();
        }
    }

    synchronized void close() {
        closed = true;
        FutureTask<?> task;
        while ((task = pending.poll()) != null) task.cancel(false);
    }

    /** Cancellation must win before the client claims a native action. */
    private static final class FrameTask<T> extends FutureTask<T> {
        // 0 = queued, 1 = claimed by the client, 2 = cancelled before start.
        private final AtomicInteger claim = new AtomicInteger();
        private final ClientFrameTasks owner;

        FrameTask(ClientFrameTasks owner, Callable<T> action) {
            super(action);
            this.owner = owner;
        }

        @Override public void run() {
            synchronized (owner) {
                if (owner.closed) {
                    cancel(false);
                    return;
                }
                if (!claim.compareAndSet(0, 1)) return;
            }
            // The lifecycle monitor covers only the claim, never game work.
            super.run();
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            // Never mark an executing game/GL teardown cancelled while its
            // side effects continue; its real completion remains observable.
            return claim.compareAndSet(0, 2) && super.cancel(false);
        }
    }
}
