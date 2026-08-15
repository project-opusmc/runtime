package dev.rbw.core;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-overhead, local-only diagnostics for the managed Minecraft process.
 *
 * <p>The launcher creates the output file with private permissions before the
 * JVM starts. This class intentionally records timings and counters only: it
 * never records usernames, chat, server addresses, packet payloads, or auth
 * material.</p>
 */
public final class ClientTelemetry {
    static final String FILE_PROPERTY = "rbw.diagnostics.file";
    private static final int SCHEMA_VERSION = 1;
    private static final long SUMMARY_INTERVAL_NANOS = 5_000_000_000L;
    private static final long ATTACK_CORRELATION_NANOS = 2_000_000_000L;
    private static final int NETWORK_SAMPLE_RATE = 8;
    private static final int EVENT_QUEUE_CAPACITY = 2048;

    private static final Object WRITER_LOCK = new Object();
    private static final Object WINDOW_LOCK = new Object();
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean ENABLED = new AtomicBoolean();
    private static final AtomicInteger QUEUED_EVENTS = new AtomicInteger();
    private static final AtomicLong INBOUND_PACKETS = new AtomicLong();
    private static final AtomicLong OUTBOUND_PACKETS = new AtomicLong();
    private static final AtomicLong NETWORK_SAMPLE_COUNTER = new AtomicLong();
    private static final AtomicLong LAST_INBOUND_NANOS = new AtomicLong();
    private static final AtomicLong ATTACK_SEQUENCE = new AtomicLong();
    private static final AtomicLong ATTACK_INTENTS = new AtomicLong();
    private static final AtomicLong ATTACK_PACKETS = new AtomicLong();
    private static final AtomicLong ATTACK_FEEDBACK = new AtomicLong();
    private static final AtomicLong DROPPED_EVENTS = new AtomicLong();
    private static final ConcurrentLinkedQueue<String> EVENTS = new ConcurrentLinkedQueue<String>();

    // The game-loop hooks run at frame/tick frequency. Reuse a one-slot array
    // per thread so measuring them does not allocate a boxed Long each time.
    private static final ThreadLocal<long[]> FRAME_START_NANOS = nanosSlot();
    private static final ThreadLocal<long[]> TICK_START_NANOS = nanosSlot();
    private static final ThreadLocal<long[]> RENDER_START_NANOS = nanosSlot();
    private static final TimingWindow FRAME_WINDOW = new TimingWindow(1024);
    private static final TimingWindow TICK_WINDOW = new TimingWindow(512);
    private static final TimingWindow RENDER_WINDOW = new TimingWindow(1024);
    private static final TimingWindow INBOUND_GAP_WINDOW = new TimingWindow(512);

    private static final long PROCESS_START_NANOS = System.nanoTime();
    private static volatile BufferedWriter writer;
    private static volatile Thread writerThread;
    private static volatile long lastSummaryNanos;
    private static volatile long lastAttackIntentNanos;
    private static volatile long lastAttackPacketNanos;
    private static volatile long lastAttackSequence;
    private static volatile long lastGcCollectionCount;
    private static volatile long lastGcCollectionTimeMillis;

    private ClientTelemetry() {
    }

    /** Starts telemetry only when the launcher supplied a private output path. */
    public static void startFromSystemProperties() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        String configured = System.getProperty(FILE_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) {
            return;
        }

        try {
            Path output = Paths.get(configured).toAbsolutePath().normalize();
            if (!Files.isRegularFile(output)) {
                System.err.println("[OPUS/DIAG] diagnostics output was not prepared by the launcher");
                return;
            }
            synchronized (WRITER_LOCK) {
                writer = Files.newBufferedWriter(
                        output,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND);
            }
            GcTotals totals = gcTotals();
            lastGcCollectionCount = totals.collectionCount;
            lastGcCollectionTimeMillis = totals.collectionTimeMillis;
            lastSummaryNanos = System.nanoTime();
            ENABLED.set(true);
            startWriterThread();
            System.out.println("[OPUS/DIAG] telemetry started");
            writeEvent(
                    "session_start",
                    "\"java_version\":\"" + escape(System.getProperty("java.version", "unknown"))
                            + "\",\"java_vm\":\""
                            + escape(System.getProperty("java.vm.name", "unknown"))
                            + "\",\"os_arch\":\""
                            + escape(System.getProperty("os.arch", "unknown"))
                            + "\",\"max_memory_bytes\":" + Runtime.getRuntime().maxMemory());
            Runtime.getRuntime().addShutdownHook(new Thread(
                    new Runnable() {
                        @Override
                        public void run() {
                            shutdown();
                        }
                    },
                    "rbw-client-telemetry"));
        } catch (IOException | RuntimeException error) {
            System.err.println("[OPUS/DIAG] could not start diagnostics: " + error.getClass().getSimpleName());
            closeWriter();
        }
    }

    public static void bootstrapLoaded(int gameClasspathEntries, int transformerCount) {
        writeEvent(
                "bootstrap_loaded",
                "\"game_classpath_entries\":" + gameClasspathEntries
                        + ",\"transformer_count\":" + transformerCount);
    }

    public static void lifecycle(String stage) {
        writeEvent("lifecycle", "\"stage\":\"" + escape(stage) + "\"");
    }

    public static void failure(Throwable failure) {
        writeEvent(
                "failure",
                "\"type\":\"" + escape(failure.getClass().getName()) + "\"");
    }

    public static void frameStarted() {
        if (ENABLED.get()) {
            FRAME_START_NANOS.get()[0] = System.nanoTime();
        }
    }

    public static void frameFinished() {
        if (!ENABLED.get()) {
            return;
        }
        long[] slot = FRAME_START_NANOS.get();
        long started = slot[0];
        if (started == Long.MIN_VALUE) {
            return;
        }
        long now = System.nanoTime();
        recordTiming(FRAME_WINDOW, now - started, now);
        slot[0] = Long.MIN_VALUE;
    }

    public static void tickStarted() {
        if (ENABLED.get()) {
            TICK_START_NANOS.get()[0] = System.nanoTime();
        }
    }

    public static void tickFinished() {
        if (!ENABLED.get()) {
            return;
        }
        long[] slot = TICK_START_NANOS.get();
        long started = slot[0];
        if (started == Long.MIN_VALUE) {
            return;
        }
        long now = System.nanoTime();
        recordTiming(TICK_WINDOW, now - started, now);
        slot[0] = Long.MIN_VALUE;
    }

    public static void renderStarted() {
        if (ENABLED.get()) {
            RENDER_START_NANOS.get()[0] = System.nanoTime();
        }
    }

    public static void renderFinished() {
        if (!ENABLED.get()) {
            return;
        }
        long[] slot = RENDER_START_NANOS.get();
        long started = slot[0];
        if (started == Long.MIN_VALUE) {
            return;
        }
        long now = System.nanoTime();
        recordTiming(RENDER_WINDOW, now - started, now);
        slot[0] = Long.MIN_VALUE;
    }

    /** Records a local left-click action before the controller decides if it can attack. */
    public static void attackInput() {
        ClientConfigUi.recordClick();
        if (!ENABLED.get()) {
            return;
        }
        long now = System.nanoTime();
        long sequence = ATTACK_SEQUENCE.incrementAndGet();
        ATTACK_INTENTS.incrementAndGet();
        lastAttackIntentNanos = now;
        lastAttackSequence = sequence;
        writeEvent("attack_input", "\"sequence\":" + sequence);
    }

    /** Records that Minecraft queued its C02 attack action with Netty. */
    public static void attackPacketQueued() {
        if (!ENABLED.get()) {
            return;
        }
        long now = System.nanoTime();
        long sequence = lastAttackSequence;
        long inputNanos = lastAttackIntentNanos;
        if (sequence == 0 || inputNanos == 0 || now - inputNanos > ATTACK_CORRELATION_NANOS) {
            sequence = ATTACK_SEQUENCE.incrementAndGet();
            lastAttackSequence = sequence;
        }
        lastAttackPacketNanos = now;
        ATTACK_PACKETS.incrementAndGet();
        long inputToQueueNanos = inputNanos == 0 ? -1 : now - inputNanos;
        writeEvent(
                "attack_packet_queued",
                "\"sequence\":" + sequence
                        + ",\"input_to_queue_us\":" + microseconds(inputToQueueNanos));
    }

    /** Counts packet flow without retaining packet payloads or server data. */
    public static void outboundPacket(Object ignoredPacket) {
        if (ENABLED.get()) {
            OUTBOUND_PACKETS.incrementAndGet();
        }
    }

    /** Samples inbound inter-arrival timing and counts packets without retaining payloads. */
    public static void inboundPacket(Object ignoredPacket) {
        if (!ENABLED.get()) {
            return;
        }
        INBOUND_PACKETS.incrementAndGet();
        long now = System.nanoTime();
        long previous = LAST_INBOUND_NANOS.getAndSet(now);
        if (previous > 0
                && NETWORK_SAMPLE_COUNTER.incrementAndGet() % NETWORK_SAMPLE_RATE == 0
                && now > previous) {
            synchronized (WINDOW_LOCK) {
                INBOUND_GAP_WINDOW.add(now - previous);
            }
        }
    }

    public static void connectionOpened() {
        writeEvent("connection_opened", "");
    }

    public static void connectionClosed() {
        writeEvent("connection_closed", "");
    }

    /**
     * Observes a status packet after the server has sent it to this client.
     * The association is deliberately marked unverified: a client cannot prove
     * that a status packet belongs to its own attack or that a server accepted
     * a hit.
     */
    public static void entityStatus(int status) {
        if (!ENABLED.get()) {
            return;
        }
        int normalizedStatus = status & 0xff;
        long packetNanos = lastAttackPacketNanos;
        long now = System.nanoTime();
        long delta = packetNanos == 0 ? -1 : now - packetNanos;
        if ((normalizedStatus == 2 || normalizedStatus == 3)
                && delta >= 0 && delta <= ATTACK_CORRELATION_NANOS) {
            ATTACK_FEEDBACK.incrementAndGet();
            writeEvent(
                    "entity_status_after_attack",
                    "\"sequence\":" + lastAttackSequence
                            + ",\"status\":" + normalizedStatus
                            + ",\"packet_to_status_us\":" + microseconds(delta)
                            + ",\"association\":\"unverified_client_signal\"");
        }
    }

    private static void recordTiming(TimingWindow window, long elapsedNanos, long now) {
        if (elapsedNanos < 0) {
            return;
        }
        synchronized (WINDOW_LOCK) {
            window.add(elapsedNanos);
            if (now - lastSummaryNanos >= SUMMARY_INTERVAL_NANOS) {
                emitSummaryLocked(now, false);
            }
        }
    }

    private static void shutdown() {
        if (!ENABLED.get()) {
            return;
        }
        synchronized (WINDOW_LOCK) {
            emitSummaryLocked(System.nanoTime(), true);
        }
        writeEvent("session_end", "");
        ENABLED.set(false);
        waitForWriter();
    }

    private static void emitSummaryLocked(long now, boolean terminal) {
        if (!ENABLED.get()) {
            return;
        }
        TimingSnapshot frames = FRAME_WINDOW.snapshotAndReset();
        TimingSnapshot ticks = TICK_WINDOW.snapshotAndReset();
        TimingSnapshot renders = RENDER_WINDOW.snapshotAndReset();
        TimingSnapshot inboundGaps = INBOUND_GAP_WINDOW.snapshotAndReset();
        GcTotals totals = gcTotals();
        long gcCount = delta(totals.collectionCount, lastGcCollectionCount);
        long gcTime = delta(totals.collectionTimeMillis, lastGcCollectionTimeMillis);
        lastGcCollectionCount = totals.collectionCount;
        lastGcCollectionTimeMillis = totals.collectionTimeMillis;
        long elapsedMillis = Math.max(1L, (now - lastSummaryNanos) / 1_000_000L);
        lastSummaryNanos = now;
        Runtime runtime = Runtime.getRuntime();
        long committedMemory = runtime.totalMemory();
        long usedMemory = committedMemory - runtime.freeMemory();
        writeEvent(
                terminal ? "session_summary" : "performance_window",
                "\"window_ms\":" + elapsedMillis
                        + ",\"frame_ms\":" + frames.toJson()
                        + ",\"tick_ms\":" + ticks.toJson()
                        + ",\"render_ms\":" + renders.toJson()
                        + ",\"inbound_gap_ms\":" + inboundGaps.toJson()
                        + ",\"network\":{\"inbound_packets\":" + INBOUND_PACKETS.getAndSet(0)
                        + ",\"outbound_packets\":" + OUTBOUND_PACKETS.getAndSet(0)
                        + ",\"inbound_gap_sample_rate\":" + NETWORK_SAMPLE_RATE + "}"
                        + ",\"gc\":{\"collections\":" + gcCount
                        + ",\"collection_time_ms\":" + gcTime + "}"
                        + ",\"memory\":{\"used_bytes\":" + usedMemory
                        + ",\"committed_bytes\":" + committedMemory
                        + ",\"max_bytes\":" + runtime.maxMemory() + "}"
                        + ",\"attack\":{\"inputs\":" + ATTACK_INTENTS.getAndSet(0)
                        + ",\"packets_queued\":" + ATTACK_PACKETS.getAndSet(0)
                        + ",\"unverified_status_signals\":" + ATTACK_FEEDBACK.getAndSet(0)
                        + ",\"dropped_events\":" + DROPPED_EVENTS.getAndSet(0) + "}");
    }

    private static GcTotals gcTotals() {
        long count = 0;
        long time = 0;
        List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean collector : collectors) {
            long collectorCount = collector.getCollectionCount();
            long collectorTime = collector.getCollectionTime();
            if (collectorCount > 0) {
                count += collectorCount;
            }
            if (collectorTime > 0) {
                time += collectorTime;
            }
        }
        return new GcTotals(count, time);
    }

    private static long delta(long current, long previous) {
        return current >= previous ? current - previous : 0;
    }

    private static void writeEvent(String event, String fields) {
        if (!ENABLED.get()) {
            return;
        }
        String line = "{\"schema\":" + SCHEMA_VERSION
                + ",\"event\":\"" + escape(event)
                + "\",\"t_ms\":" + elapsedMillis();
        if (!fields.isEmpty()) {
            line += ',' + fields;
        }
        line += '}';
        if (!reserveEventSlot()) {
            return;
        }
        EVENTS.offer(line);
    }

    private static boolean reserveEventSlot() {
        while (true) {
            int queued = QUEUED_EVENTS.get();
            if (queued >= EVENT_QUEUE_CAPACITY) {
                DROPPED_EVENTS.incrementAndGet();
                return false;
            }
            if (QUEUED_EVENTS.compareAndSet(queued, queued + 1)) {
                return true;
            }
        }
    }

    private static void startWriterThread() {
        Thread created = new Thread(new Runnable() {
            @Override
            public void run() {
                drainEvents();
            }
        }, "rbw-diagnostics-writer");
        created.setDaemon(true);
        writerThread = created;
        created.start();
    }

    private static void drainEvents() {
        long lastFlushNanos = System.nanoTime();
        try {
            while (ENABLED.get() || !EVENTS.isEmpty()) {
                String line = EVENTS.poll();
                if (line == null) {
                    sleepBeforeDrain();
                    continue;
                }
                QUEUED_EVENTS.decrementAndGet();
                writer.write(line);
                writer.newLine();
                long now = System.nanoTime();
                if (now - lastFlushNanos >= 1_000_000_000L) {
                    writer.flush();
                    lastFlushNanos = now;
                }
            }
            writer.flush();
        } catch (IOException error) {
            ENABLED.set(false);
            EVENTS.clear();
            QUEUED_EVENTS.set(0);
        } finally {
            closeWriter();
        }
    }

    private static void sleepBeforeDrain() {
        try {
            Thread.sleep(25L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitForWriter() {
        Thread active = writerThread;
        if (active == null || active == Thread.currentThread()) {
            return;
        }
        try {
            active.join(2_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static long elapsedMillis() {
        return (System.nanoTime() - PROCESS_START_NANOS) / 1_000_000L;
    }

    private static ThreadLocal<long[]> nanosSlot() {
        return new ThreadLocal<long[]>() {
            @Override
            protected long[] initialValue() {
                return new long[] {Long.MIN_VALUE};
            }
        };
    }

    private static String milliseconds(long nanos) {
        if (nanos < 0) {
            return "null";
        }
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0d);
    }

    private static String microseconds(long nanos) {
        return nanos < 0 ? "null" : Long.toString(nanos / 1_000L);
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.toString();
    }

    private static void closeWriter() {
        synchronized (WRITER_LOCK) {
            if (writer == null) {
                return;
            }
            try {
                writer.close();
            } catch (IOException ignored) {
                // Diagnostics must never prevent Minecraft from shutting down.
            } finally {
                writer = null;
            }
        }
    }

    private static final class GcTotals {
        private final long collectionCount;
        private final long collectionTimeMillis;

        private GcTotals(long collectionCount, long collectionTimeMillis) {
            this.collectionCount = collectionCount;
            this.collectionTimeMillis = collectionTimeMillis;
        }
    }

    private static final class TimingWindow {
        private final long[] values;
        private int size;
        private int next;

        private TimingWindow(int capacity) {
            this.values = new long[capacity];
        }

        private void add(long value) {
            values[next] = value;
            next = (next + 1) % values.length;
            if (size < values.length) {
                size++;
            }
        }

        private TimingSnapshot snapshotAndReset() {
            long[] snapshot = Arrays.copyOf(values, size);
            size = 0;
            next = 0;
            return TimingSnapshot.from(snapshot);
        }
    }

    private static final class TimingSnapshot {
        private final int samples;
        private final long p50;
        private final long p95;
        private final long p99;
        private final long maximum;

        private TimingSnapshot(int samples, long p50, long p95, long p99, long maximum) {
            this.samples = samples;
            this.p50 = p50;
            this.p95 = p95;
            this.p99 = p99;
            this.maximum = maximum;
        }

        private static TimingSnapshot from(long[] values) {
            if (values.length == 0) {
                return new TimingSnapshot(0, 0, 0, 0, 0);
            }
            Arrays.sort(values);
            return new TimingSnapshot(
                    values.length,
                    percentile(values, 0.50d),
                    percentile(values, 0.95d),
                    percentile(values, 0.99d),
                    values[values.length - 1]);
        }

        private static long percentile(long[] values, double percentile) {
            int index = (int) Math.ceil(percentile * values.length) - 1;
            return values[Math.max(0, Math.min(index, values.length - 1))];
        }

        private String toJson() {
            return "{\"samples\":" + samples
                    + ",\"p50\":" + milliseconds(p50)
                    + ",\"p95\":" + milliseconds(p95)
                    + ",\"p99\":" + milliseconds(p99)
                    + ",\"max\":" + milliseconds(maximum) + "}";
        }
    }
}
