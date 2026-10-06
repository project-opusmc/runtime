package org.polydevs.opusmc.client.embed;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.Logger;

/**
 * Drives the out-of-process CEF helper that renders the Opus web UI offscreen,
 * and hands the resulting BGRA frames to {@link OpusWebTextureSurface} for
 * upload as an in-game OpenGL texture.
 *
 * <p>CEF cannot be hosted inside this game's Java 8 / macOS arm64 runtime (see
 * docs/research-jcef-spike.md), but that limit only applies in-process: the
 * helper is a separate executable, so the browser engine is a real Chromium
 * while the game JVM stays on Java 8 and only touches OpenGL.</p>
 *
 * <p>Frames are passed through a shared memory-mapped file rather than the
 * control socket. A 4K frame is ~33 MB; pushing that through a socket every
 * paint was the instability ceiling of the previous WKWebView helper. Now the
 * socket carries only a one-line notification and the pixels are read straight
 * out of the mapping with zero copies. The mapping uses three ownership slots
 * and grows on demand when the framebuffer exceeds the current slot capacity.</p>
 *
 * <p>Everything here is fail-closed: an error marks the embedded surface as
 * failed so the Minecraft host can show its minimal lifecycle shell. There is
 * deliberately no second product UI fallback.</p>
 */
public final class OpusWebViewClient {
    private static final long MIN_SLOT_BYTES = 1L << 20;
    private static final long MAX_SLOT_BYTES = 128L << 20;
    private static final int MAX_FRAME_DIMENSION = 8192;
    /** Must match kSlots in native/opus-cef-helper/src/opus_main.mm. */
    private static final int SLOTS = 3;
    private static final long SHM_RESIZE_TIMEOUT_MS = 5000L;
    private static final long PAINT_RETRY_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(150L);

    private static final String HELPER_APP = "opus-webview-helper.app";
    private static final String HELPER_BIN = "opus-webview-helper";
    private static final String KEYCHAIN_GUARD = "opus-keychain-guard.dylib";
    private static final long HELPER_GRACEFUL_STOP_TIMEOUT_MS = 3000L;
    private static final long HELPER_FORCE_STOP_TIMEOUT_MS = 1000L;

    /**
     * One published browser frame. {@code pixels} is a read-only window onto the
     * shared mapping, not a copy, so it must be uploaded before the helper
     * recycles that slot — which is why {@link #consumeFrame()} is called once
     * per game frame and the result used immediately.
     */
    public static final class Frame {
        public final ByteBuffer pixels;
        public final long sequence;
        public final long generation;
        public final int slot;
        public final int width;
        public final int height;
        private final AtomicBoolean released = new AtomicBoolean();

        Frame(ByteBuffer pixels, long sequence, long generation, int slot, int width, int height) {
            this.pixels = pixels;
            this.sequence = sequence;
            this.generation = generation;
            this.slot = slot;
            this.width = width;
            this.height = height;
        }
    }

    private final Logger log;
    private final String helperPath;
    private final AtomicReference<Frame> latest = new AtomicReference<Frame>();
    private final AtomicInteger outstandingFrames = new AtomicInteger();
    private final Object writeLock = new Object();
    private final Object shmResizeLock = new Object();

    private volatile boolean running;
    private volatile boolean failed;
    private volatile boolean ready;
    private volatile boolean surfaceVisible = true;
    private volatile boolean stopping;
    private volatile boolean warned;
    private volatile long framesSeen;
    private Process helper;
    private Socket socket;
    private OutputStream out;
    private Thread reader;
    private File shmFile;
    private RandomAccessFile shmHandle;
    private volatile MappedByteBuffer shm;
    private volatile long slotBytes;
    private volatile long shmBytes;
    private MappedByteBuffer pendingShm;
    private long pendingSlotBytes;
    private long pendingShmBytes;
    private long pendingShmRequest;
    private long nextShmRequest;
    private boolean pendingShmResizeFailed;
    private volatile boolean transportResizing;
    private int width = 1280;
    private int height = 720;
    private float scale = 1.0F;
    private String url = "about:blank";
    private volatile long generation = 1L;
    private volatile long lastPublishedGeneration;
    private volatile long lastViewSentNanos;
    private long paintRetryGeneration;
    private int paintRetryCount;

    public OpusWebViewClient(Logger log, String helperPath) {
        this.log = log;
        this.helperPath = helperPath;
    }

    public boolean isFailed() {
        return failed;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isSurfaceVisible() {
        return running && !failed && surfaceVisible;
    }

    /** Park or reveal the same CEF browser without closing its process/socket. */
    public synchronized void setSurfaceVisible(boolean visible) {
        if (surfaceVisible == visible || !running || failed) {
            return;
        }
        if (!visible) {
            surfaceVisible = false;
            release(latest.getAndSet(null));
            sendLine("SURFACE HIDE");
            return;
        }
        advanceGeneration();
        if (failed) {
            return;
        }
        surfaceVisible = true;
        sendLine("SURFACE SHOW");
        sendView();
    }

    /**
     * Starts the helper process and connects to it. Returns false on failure so
     * the caller can enter the explicit failed lifecycle state.
     *
     * @param scale device scale factor: framebuffer pixels per logical unit of
     *              {@code width}/{@code height}. Passing the game's real ratio
     *              makes the texture pixel-exact instead of an upscaled blur.
     */
    public synchronized boolean start(int width, int height, float scale, String url) {
        if (running || failed) {
            return running;
        }
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.scale = clampScale(scale);
        this.url = url;
        try {
            openSharedBuffer();
            int port = reserveLoopbackPort();
            String token = randomToken();
            ProcessBuilder helperProcess = new ProcessBuilder(
                    helperPath,
                    "--port", Integer.toString(port),
                    "--token", token,
                    "--shm", shmFile.getAbsolutePath(),
                    "--shm-size", Long.toString(shmBytes),
                    "--url", this.url,
                    "--width", Integer.toString(this.width),
                    "--height", Integer.toString(this.height),
                    "--scale", Float.toString(this.scale),
                    "--generation", Long.toString(this.generation));
            installKeychainGuard(helperProcess.environment(), helperPath);
            helper = helperProcess.redirectErrorStream(true).start();
            drainProcessOutput(helper);

            // The helper binds asynchronously; retry the loopback connect for a
            // short window before giving up.
            socket = connectWithRetry(port, 10000);
            if (socket == null) {
                throw new IOException("helper did not accept a loopback connection");
            }
            socket.setTcpNoDelay(true);
            out = socket.getOutputStream();

            sendLine("HELLO " + token);
            running = true;
            reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    readLoop();
                }
            }, "opus-webview-reader");
            reader.setDaemon(true);
            reader.start();

            // The helper already launched with these, but resend so a helper that
            // was reused (or started before the final layout was known) agrees
            // with the game about geometry.
            sendView();
            log.info("Opus CEF helper started: {}x{} @ {}x scale, shm {}",
                    this.width, this.height, this.scale, shmFile.getAbsolutePath());
            return true;
        } catch (Throwable failure) {
            log.warn("Opus embedded web view could not start", failure);
            fail();
            // The caller only retains successfully started clients. Clean up
            // here so a failed connect/handshake cannot orphan the helper,
            // mapping or temporary shared-memory file with no owner left to
            // invoke stop().
            stop();
            return false;
        }
    }

    /**
     * Creates the initial sparse shared buffer sized for the current viewport.
     * Larger viewports grow the same file through the SHM_RESIZE handshake.
     */
    private void openSharedBuffer() throws IOException {
        long required = requiredFrameBytes(width, height, scale);
        slotBytes = chooseSlotCapacity(required);
        shmBytes = checkedTotalBytes(slotBytes);
        shmFile = File.createTempFile("opus-cef-", ".shm");
        shmFile.deleteOnExit();
        shmHandle = new RandomAccessFile(shmFile, "rw");
        shmHandle.setLength(shmBytes);
        shm = shmHandle.getChannel().map(FileChannel.MapMode.READ_ONLY, 0L, shmBytes);
        shm.order(ByteOrder.nativeOrder());
    }

    /** Returns the most recent frame and clears it, or null if none is new. */
    public Frame consumeFrame() {
        Frame frame = latest.getAndSet(null);
        if (!surfaceVisible) {
            release(frame);
            return null;
        }
        if (frame == null) {
            retryCurrentGenerationPaintIfNeeded();
        }
        return frame;
    }

    private void retryCurrentGenerationPaintIfNeeded() {
        if (!running || failed || !surfaceVisible || transportResizing
                || generation == lastPublishedGeneration) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastViewSentNanos < PAINT_RETRY_INTERVAL_NANOS) {
            return;
        }
        if (paintRetryGeneration != generation) {
            paintRetryGeneration = generation;
            paintRetryCount = 0;
        }
        paintRetryCount++;
        if (paintRetryCount == 1 || paintRetryCount % 20 == 0) {
            log.info("Opus CEF generation {} has not painted yet; retrying VIEW ({})",
                    generation, paintRetryCount);
        }
        sendView();
    }

    /** Releases a published shared-memory slot after upload or rejection. */
    public void release(Frame frame) {
        if (frame != null && frame.sequence > 0L
                && frame.slot >= 0 && frame.slot < SLOTS
                && frame.released.compareAndSet(false, true)) {
            sendLine("RELEASE " + frame.sequence + " " + frame.slot);
            int remaining = outstandingFrames.decrementAndGet();
            if (remaining < 0) {
                outstandingFrames.set(0);
                log.error("Opus CEF frame ownership accounting underflow");
                fail();
            }
        }
    }

    /** True only for a frame from the current route/viewport generation. */
    public boolean isCurrent(Frame frame, int logicalWidth, int logicalHeight, float deviceScale) {
        if (!surfaceVisible || frame == null || frame.generation != generation) {
            return false;
        }
        double safeScale = clampScale(deviceScale);
        long expectedWidthLong = Math.max(1L, Math.round(Math.max(1, logicalWidth) * safeScale));
        long expectedHeightLong = Math.max(1L, Math.round(Math.max(1, logicalHeight) * safeScale));
        if (expectedWidthLong > Integer.MAX_VALUE || expectedHeightLong > Integer.MAX_VALUE) {
            return false;
        }
        int expectedWidth = (int) expectedWidthLong;
        int expectedHeight = (int) expectedHeightLong;
        return frame.width == expectedWidth && frame.height == expectedHeight;
    }

    /**
     * Updates the logical viewport and the device scale factor together. Both are
     * sent only when they change, so calling this every frame is free.
     */
    public synchronized void resize(int width, int height, float scale) {
        if (!running || failed) {
            return;
        }
        float nextScale = clampScale(scale);
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        if (w == this.width && h == this.height && nextScale == this.scale) {
            return;
        }
        final long required;
        try {
            required = requiredFrameBytes(w, h, nextScale);
        } catch (IllegalArgumentException invalid) {
            log.warn("Opus CEF viewport is outside the supported transport envelope", invalid);
            fail();
            return;
        }
        if (!ensureSharedCapacity(required)) {
            return;
        }
        this.width = w;
        this.height = h;
        this.scale = nextScale;
        advanceGeneration();
        sendView();
    }

    public int sharedSlotCount() {
        return SLOTS;
    }

    public long sharedSlotBytes() {
        return slotBytes;
    }

    public long sharedBufferBytes() {
        return shmBytes;
    }

    public int outstandingFrameCount() {
        return outstandingFrames.get();
    }

    private boolean ensureSharedCapacity(long required) {
        if (required <= slotBytes) {
            return true;
        }
        final long targetSlot;
        final long targetTotal;
        try {
            targetSlot = chooseSlotCapacity(required);
            targetTotal = checkedTotalBytes(targetSlot);
        } catch (IllegalArgumentException invalid) {
            log.warn("Opus CEF shared-memory growth request is invalid", invalid);
            fail();
            return false;
        }

        synchronized (shmResizeLock) {
            if (required <= slotBytes) {
                return true;
            }
            if (pendingShmRequest != 0L || shmHandle == null) {
                log.warn("Opus CEF shared-memory resize overlapped another transport transition");
                fail();
                return false;
            }

            transportResizing = true;
            Frame stale = latest.getAndSet(null);
            release(stale);
            if (outstandingFrames.get() != 0) {
                transportResizing = false;
                log.warn("Opus CEF cannot grow shared memory while {} frame(s) are still owned",
                        outstandingFrames.get());
                fail();
                return false;
            }
            try {
                shmHandle.setLength(targetTotal);
                MappedByteBuffer mapping = shmHandle.getChannel().map(
                        FileChannel.MapMode.READ_ONLY, 0L, targetTotal);
                mapping.order(ByteOrder.nativeOrder());
                pendingShm = mapping;
            } catch (IOException failure) {
                transportResizing = false;
                log.warn("Opus CEF could not grow its shared frame buffer", failure);
                fail();
                return false;
            }

            if (nextShmRequest >= Long.MAX_VALUE - 1L) {
                transportResizing = false;
                log.error("Opus CEF shared-memory request sequence exhausted");
                fail();
                return false;
            }
            long request = ++nextShmRequest;
            pendingShmRequest = request;
            pendingSlotBytes = targetSlot;
            pendingShmBytes = targetTotal;
            pendingShmResizeFailed = false;
            sendLine("SHM_RESIZE " + request + " " + targetTotal);

            long deadline = System.currentTimeMillis() + SHM_RESIZE_TIMEOUT_MS;
            while (pendingShmRequest == request && running && !failed) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    shmResizeLock.wait(Math.min(remaining, 100L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    pendingShmResizeFailed = true;
                    break;
                }
            }

            if (pendingShmRequest == request) {
                pendingShmRequest = 0L;
                pendingShm = null;
                pendingSlotBytes = 0L;
                pendingShmBytes = 0L;
                transportResizing = false;
                log.warn("Opus CEF timed out waiting for shared-memory resize ACK");
                fail();
                return false;
            }
            if (pendingShmResizeFailed || slotBytes < required) {
                transportResizing = false;
                log.warn("Opus CEF shared-memory resize failed");
                fail();
                return false;
            }
            return true;
        }
    }

    private static long requiredFrameBytes(int logicalWidth, int logicalHeight, float scale) {
        long rasterWidth = Math.max(1L, Math.round(Math.max(1, logicalWidth) * (double) clampScale(scale)));
        long rasterHeight = Math.max(1L, Math.round(Math.max(1, logicalHeight) * (double) clampScale(scale)));
        if (rasterWidth > MAX_FRAME_DIMENSION || rasterHeight > MAX_FRAME_DIMENSION) {
            throw new IllegalArgumentException("frame dimensions exceed " + MAX_FRAME_DIMENSION
                    + ": " + rasterWidth + "x" + rasterHeight);
        }
        if (rasterWidth > Long.MAX_VALUE / rasterHeight
                || rasterWidth * rasterHeight > Long.MAX_VALUE / 4L) {
            throw new IllegalArgumentException("frame byte size overflows");
        }
        long bytes = rasterWidth * rasterHeight * 4L;
        if (bytes <= 0L || bytes > MAX_SLOT_BYTES) {
            throw new IllegalArgumentException("frame requires " + bytes
                    + " bytes; max slot is " + MAX_SLOT_BYTES);
        }
        return bytes;
    }

    private static long chooseSlotCapacity(long required) {
        if (required <= 0L || required > MAX_SLOT_BYTES) {
            throw new IllegalArgumentException("unsupported slot requirement: " + required);
        }
        long capacity = MIN_SLOT_BYTES;
        while (capacity < required && capacity < MAX_SLOT_BYTES) {
            capacity <<= 1;
        }
        if (capacity < required || capacity > MAX_SLOT_BYTES) {
            throw new IllegalArgumentException("could not size slot for " + required);
        }
        return capacity;
    }

    private static long checkedTotalBytes(long oneSlot) {
        if (oneSlot <= 0L || oneSlot > Long.MAX_VALUE / SLOTS) {
            throw new IllegalArgumentException("shared-memory size overflow");
        }
        return oneSlot * SLOTS;
    }

    public void navigate(String url) {
        if (!running || failed || url == null || url.equals(this.url)) {
            return;
        }
        this.url = url;
        advanceGeneration();
        sendView();
    }

    /**
     * Forces the helper to genuinely reload the current document. Used by the
     * navigation desync recovery: a plain {@link #navigate} to the same
     * route is a no-op, so recovery must ask for a real reload instead.
     */
    public synchronized long reload() {
        if (!running || failed) {
            return -1L;
        }
        advanceGeneration();
        if (failed) {
            return -1L;
        }
        sendLine("RELOAD " + generation);
        return generation;
    }

    /** Repaint the already-loaded route after React has committed and ACKed it. */
    public synchronized long requestCommittedFrame() {
        if (!running || failed || !surfaceVisible) {
            return -1L;
        }
        advanceGeneration();
        if (failed) {
            return -1L;
        }
        sendView();
        return generation;
    }

    /** True after CEF has published a frame for this generation or a newer one. */
    public boolean hasPublishedGeneration(long expectedGeneration) {
        return expectedGeneration > 0L && lastPublishedGeneration >= expectedGeneration;
    }

    private void sendView() {
        lastViewSentNanos = System.nanoTime();
        sendLine("VIEW " + generation + " " + width + " " + height + " " + scale + " " + url);
    }

    private void advanceGeneration() {
        if (generation >= Long.MAX_VALUE - 1L) {
            log.error("Opus CEF navigation generation exhausted; refusing to reuse frame ownership ids");
            fail();
            return;
        }
        generation++;
        paintRetryGeneration = generation;
        paintRetryCount = 0;
        Frame stale = latest.getAndSet(null);
        release(stale);
    }

    // ---- input ------------------------------------------------------------
    // The helper speaks plain tokens rather than encoded JSON: these events are
    // fed straight into CEF's SendMouseEvent/SendKeyEvent, so there is no DOM
    // synthesis step that would need a structured payload. Coordinates are in
    // the same logical units as the viewport passed to start()/resize().

    public void sendMouseMove(int x, int y) {
        sendMouseMove(x, y, 0);
    }

    public void sendMouseMove(int x, int y, int modifiers) {
        if (!surfaceVisible) return;
        sendLine("INPUT " + generation + " move " + x + " " + y + " " + modifiers);
    }

    /** Tells the helper that the synthetic pointer left the browser surface. */
    public void sendMouseLeave(int x, int y) {
        sendMouseLeave(x, y, 0);
    }

    public void sendMouseLeave(int x, int y, int modifiers) {
        if (!surfaceVisible) return;
        sendLine("INPUT " + generation + " leave " + x + " " + y + " " + modifiers);
    }

    /** button: 0 left, 1 right, 2 middle (LWJGL's own numbering). */
    public void sendMouseButton(int x, int y, int button, boolean down) {
        sendMouseButton(x, y, button, down, 0);
    }

    public void sendMouseButton(int x, int y, int button, boolean down, int modifiers) {
        if (!surfaceVisible) return;
        sendLine("INPUT " + generation + " " + (down ? "down " : "up ")
                + x + " " + y + " " + button + " " + modifiers);
    }

    /** deltaY follows the wheel convention: positive scrolls the view up. */
    public void sendWheel(int x, int y, int deltaY) {
        sendWheel(x, y, deltaY, 0);
    }

    public void sendWheel(int x, int y, int deltaY, int modifiers) {
        if (!surfaceVisible) return;
        sendLine("INPUT " + generation + " wheel " + x + " " + y + " " + deltaY
                + " " + modifiers);
    }

    /** Text input: a Unicode BMP code point that should be inserted. */
    public void sendChar(int codePoint) {
        sendChar(codePoint, 0);
    }

    public void sendChar(int codePoint, int modifiers) {
        if (!surfaceVisible || codePoint < 0 || codePoint > 0xFFFF) {
            return;
        }
        sendLine("INPUT " + generation + " char " + codePoint + " " + modifiers);
    }

    /** Non-text keys, as a Windows virtual-key code (what CEF expects). */
    public void sendKey(int windowsVirtualKey, boolean down) {
        sendKey(windowsVirtualKey, down, 0);
    }

    public void sendKey(int windowsVirtualKey, boolean down, int modifiers) {
        if (!surfaceVisible) return;
        sendLine("INPUT " + generation + " key " + windowsVirtualKey + " "
                + (down ? 1 : 0) + " " + modifiers);
    }

    public synchronized void stop() {
        if (stopping) {
            return;
        }
        stopping = true;
        running = false;
        ready = false;
        surfaceVisible = false;
        Frame outstanding = latest.getAndSet(null);
        release(outstanding);
        sendLineQuietly("BYE");
        // Keep the control socket alive while CEF closes its browser and exits
        // through OnBeforeClose. This makes close/reopen sequential instead of
        // treating an immediate local socket close as the shutdown protocol.
        stopHelperProcess();
        closeQuietly(socket);
        socket = null;
        out = null;
        Thread activeReader = reader;
        reader = null;
        if (activeReader != null && activeReader != Thread.currentThread()) {
            try {
                activeReader.join(HELPER_FORCE_STOP_TIMEOUT_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        latest.set(null);
        releaseSharedBuffer();
    }

    private void stopHelperProcess() {
        Process process = helper;
        helper = null;
        if (process == null) {
            return;
        }
        try {
            if (process.waitFor(HELPER_GRACEFUL_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return;
            }
            log.warn("Opus CEF helper did not stop after BYE; terminating it");
            process.destroy();
            if (!process.waitFor(HELPER_FORCE_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(HELPER_FORCE_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void releaseSharedBuffer() {
        // The mapping itself cannot be unmapped explicitly on Java 8; dropping the
        // reference leaves it to the collector. The backing file is deleted so it
        // does not outlive the session.
        shm = null;
        synchronized (shmResizeLock) {
            pendingShm = null;
            pendingSlotBytes = 0L;
            pendingShmBytes = 0L;
            pendingShmRequest = 0L;
            pendingShmResizeFailed = false;
            transportResizing = false;
            outstandingFrames.set(0);
            slotBytes = 0L;
            shmBytes = 0L;
            shmResizeLock.notifyAll();
        }
        if (shmHandle != null) {
            try {
                shmHandle.close();
            } catch (IOException ignored) {
                // Shutting down anyway.
            }
            shmHandle = null;
        }
        if (shmFile != null) {
            if (!shmFile.delete()) {
                shmFile.deleteOnExit();
            }
            shmFile = null;
        }
    }

    private void readLoop() {
        try {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            while (running) {
                String header = readLine(in);
                if (header == null) {
                    // Clean EOF: the helper closed the control socket. Silent
                    // before, which made a helper that died on startup look
                    // identical to one that simply never painted.
                    if (running) {
                        log.warn("Opus CEF helper closed the control socket "
                                + "(frames received: {})", framesSeen);
                    }
                    break;
                }
                if (header.equals("READY")) {
                    ready = true;
                    log.info("Opus CEF helper is ready");
                } else if (header.startsWith("SHM_READY ")) {
                    handleSharedResizeReady(header);
                } else if (header.startsWith("SHM_ERROR ")) {
                    handleSharedResizeError(header);
                } else if (header.startsWith("FRAME ")) {
                    publishFrame(header);
                }
            }
        } catch (Throwable failure) {
            if (running) {
                log.warn("Opus embedded web view stream ended", failure);
            }
        } finally {
            if (stopping) {
                running = false;
                ready = false;
            } else {
                fail();
            }
        }
    }

    private void handleSharedResizeReady(String header) {
        String[] parts = header.split(" ");
        if (parts.length != 4) {
            warnOnce("Opus CEF helper sent malformed SHM_READY: " + header);
            return;
        }
        try {
            long request = Long.parseLong(parts[1]);
            long total = Long.parseLong(parts[2]);
            long oneSlot = Long.parseLong(parts[3]);
            synchronized (shmResizeLock) {
                if (request != pendingShmRequest || request == 0L) {
                    return;
                }
                if (pendingShm == null || total != pendingShmBytes
                        || oneSlot != pendingSlotBytes || total != oneSlot * SLOTS) {
                    pendingShmResizeFailed = true;
                } else {
                    shm = pendingShm;
                    slotBytes = oneSlot;
                    shmBytes = total;
                    pendingShmResizeFailed = false;
                    log.info("Opus CEF shared-memory transport grew to {} slots x {} bytes",
                            SLOTS, oneSlot);
                }
                transportResizing = false;
                pendingShm = null;
                pendingSlotBytes = 0L;
                pendingShmBytes = 0L;
                pendingShmRequest = 0L;
                shmResizeLock.notifyAll();
            }
        } catch (RuntimeException malformed) {
            synchronized (shmResizeLock) {
                pendingShmResizeFailed = true;
                transportResizing = false;
                pendingShmRequest = 0L;
                shmResizeLock.notifyAll();
            }
            warnOnce("Opus CEF helper sent unparseable SHM_READY: " + header);
        }
    }

    private void handleSharedResizeError(String header) {
        String[] parts = header.split(" ", 3);
        long request = 0L;
        if (parts.length >= 2) {
            try {
                request = Long.parseLong(parts[1]);
            } catch (RuntimeException ignored) {
                request = 0L;
            }
        }
        synchronized (shmResizeLock) {
            if (request == pendingShmRequest && request != 0L) {
                pendingShmResizeFailed = true;
                transportResizing = false;
                pendingShm = null;
                pendingSlotBytes = 0L;
                pendingShmBytes = 0L;
                pendingShmRequest = 0L;
                shmResizeLock.notifyAll();
            }
        }
        warnOnce("Opus CEF helper rejected shared-memory resize: " + header);
    }

    /** Parses {@code FRAME <seq> <generation> <slot> <w> <h> <offset>}. */
    private void publishFrame(String header) {
        String[] parts = header.split(" ");
        if (parts.length != 7) {
            warnOnce("Opus CEF helper sent a malformed frame header: " + header);
            return;
        }
        MappedByteBuffer mapping = shm;
        if (mapping == null) {
            return;
        }
        long sequence = 0L;
        int slot = -1;
        try {
            sequence = Long.parseLong(parts[1]);
            long frameGeneration = Long.parseLong(parts[2]);
            slot = Integer.parseInt(parts[3]);
            int w = Integer.parseInt(parts[4]);
            int h = Integer.parseInt(parts[5]);
            long offset = Long.parseLong(parts[6]);
            if (transportResizing) {
                releaseHeaderSlot(sequence, slot);
                return;
            }
            if (sequence <= 0L || frameGeneration <= 0L || slot < 0 || slot >= SLOTS
                    || w <= 0 || h <= 0 || w > MAX_FRAME_DIMENSION || h > MAX_FRAME_DIMENSION
                    || offset < 0L) {
                releaseHeaderSlot(sequence, slot);
                warnOnce("Opus CEF helper sent an out-of-range frame: " + header);
                return;
            }
            long bytes = (long) w * h * 4L;
            long currentSlotBytes = slotBytes;
            long currentShmBytes = shmBytes;
            long expectedOffset = (long) slot * currentSlotBytes;
            if (currentSlotBytes <= 0L || currentShmBytes <= 0L
                    || offset != expectedOffset || offset > currentShmBytes
                    || bytes <= 0L || bytes > currentSlotBytes
                    || bytes > currentShmBytes - offset) {
                releaseHeaderSlot(sequence, slot);
                warnOnce("Opus CEF frame " + w + "x" + h + " at " + offset
                        + " does not fit the negotiated shared buffer; dropping");
                return;
            }
            ByteBuffer view = mapping.duplicate();
            view.position((int) offset);
            view.limit((int) (offset + bytes));
            Frame frame = new Frame(
                    view.slice().order(ByteOrder.nativeOrder()),
                    sequence,
                    frameGeneration,
                    slot,
                    w,
                    h);
            outstandingFrames.incrementAndGet();
            if (!surfaceVisible || frameGeneration != generation) {
                release(frame);
                return;
            }
            lastPublishedGeneration = frameGeneration;
            Frame dropped = latest.getAndSet(frame);
            release(dropped);
            if (framesSeen++ == 0) {
                log.info("Opus CEF helper delivered its first frame: {}x{}", w, h);
            }
        } catch (RuntimeException malformed) {
            releaseHeaderSlot(sequence, slot);
            warnOnce("Opus CEF helper sent an unparseable frame header: " + header);
        }
    }

    /** Releases a malformed notification only when its sequence/slot are valid. */
    private void releaseHeaderSlot(long sequence, int slot) {
        if (sequence > 0L && slot >= 0 && slot < SLOTS) {
            sendLine("RELEASE " + sequence + " " + slot);
        }
    }

    /**
     * Logs a frame-path problem once. These arrive at up to 30 Hz, so an
     * unthrottled warning would bury the rest of the game log.
     */
    private void warnOnce(String message) {
        if (!warned) {
            warned = true;
            log.warn(message);
        }
    }

    private void fail() {
        if (!stopping) {
            failed = true;
        }
        running = false;
        ready = false;
        synchronized (shmResizeLock) {
            transportResizing = false;
            shmResizeLock.notifyAll();
        }
        closeQuietly(socket);
    }

    private void sendLine(String line) {
        if (failed) {
            return;
        }
        try {
            synchronized (writeLock) {
                if (out != null) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (IOException failure) {
            fail();
        }
    }

    private void sendLineQuietly(String line) {
        try {
            synchronized (writeLock) {
                if (out != null) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (IOException ignored) {
            // Shutting down anyway.
        }
    }

    private static float clampScale(float scale) {
        if (Float.isNaN(scale) || scale < 1.0F) {
            return 1.0F;
        }
        return scale > 4.0F ? 4.0F : scale;
    }

    /**
     * CEF's macOS certificate bridge can query the system Keychain even when
     * password storage is disabled. Inject the bundled guard before dyld loads
     * CEF so every browser subprocess inherits the same fail-closed policy.
     */
    private static void installKeychainGuard(java.util.Map<String, String> environment,
            String executablePath) throws IOException {
        File executable = new File(executablePath);
        File parent = executable.getParentFile();
        File contents = parent == null ? null : parent.getParentFile();
        if (contents == null || !"Contents".equals(contents.getName())) {
            throw new IOException("CEF helper must run from a macOS .app bundle");
        }
        File guard = new File(contents, "Frameworks/" + KEYCHAIN_GUARD);
        if (!guard.isFile()) {
            throw new IOException("CEF helper is missing " + KEYCHAIN_GUARD);
        }
        String existing = environment.get("DYLD_INSERT_LIBRARIES");
        environment.put("DYLD_INSERT_LIBRARIES",
                existing == null || existing.isEmpty()
                        ? guard.getAbsolutePath()
                        : guard.getAbsolutePath() + File.pathSeparator + existing);
        environment.put("OPUS_KEYCHAIN_GUARD", "1");
    }

    private static Socket connectWithRetry(int port, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                return new Socket(InetAddress.getByName("127.0.0.1"), port);
            } catch (IOException retry) {
                try {
                    Thread.sleep(15L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private static int reserveLoopbackPort() throws IOException {
        ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        try {
            return probe.getLocalPort();
        } finally {
            probe.close();
        }
    }

    private void drainProcessOutput(final Process process) {
        Thread pump = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    process.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Surface helper diagnostics (CEF startup, dropped
                        // frames, errors) in the game log.
                        log.info("[helper] {}", line);
                    }
                } catch (IOException ignored) {
                    // Process ended.
                }
            }
        }, "opus-webview-helper-log");
        pump.setDaemon(true);
        pump.start();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder builder = new StringBuilder();
        int value;
        while ((value = in.read()) != -1) {
            if (value == '\n') {
                return builder.toString();
            }
            if (value != '\r') {
                builder.append((char) value);
            }
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private static String randomToken() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        Random random = new Random();
        StringBuilder token = new StringBuilder(24);
        for (int index = 0; index < 24; index++) {
            token.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return token.toString();
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing anyway.
            }
        }
    }

    /**
     * Resolves the CEF helper executable inside its {@code .app} bundle. The
     * bundle cannot be extracted from the mod jar as a single file the way the
     * old Swift helper was — it carries the ~500 MB Chromium framework and five
     * subprocess bundles — so it is located on disk instead:
     *
     * <ol>
     *   <li>{@code -Dopus.webview.helper}: either the {@code .app} or the binary
     *       inside it.</li>
     *   <li>the dev build output under {@code native/opus-cef-helper/build},
     *       searched from the working directory upwards (ForgeGradle's runClient
     *       starts the game in a {@code run/} subdirectory).</li>
     *   <li>{@code opus-webview-helper.app} beside the game directory, which is
     *       where a packaged install ships it.</li>
     * </ol>
     *
     * Returns null when no helper is available; embedded mode then stays off and
     * the host enters its explicit minimal loading/error lifecycle shell. There
     * is no native product-screen fallback.
     */
    public static String resolveHelperPath(Logger log) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("mac")) {
            return null;
        }

        String explicit = System.getProperty("opus.webview.helper");
        if (explicit != null && !explicit.isEmpty()) {
            String resolved = executableIn(new File(explicit));
            if (resolved != null) {
                return resolved;
            }
            log.warn("opus.webview.helper does not point at the CEF helper: {}", explicit);
        }

        File directory = new File(System.getProperty("user.dir", "."));
        for (int depth = 0; depth < 5 && directory != null; depth++) {
            String resolved = executableIn(
                    new File(directory, "native/opus-cef-helper/build/" + HELPER_APP));
            if (resolved != null) {
                return resolved;
            }
            resolved = executableIn(new File(directory, HELPER_APP));
            if (resolved != null) {
                return resolved;
            }
            directory = directory.getParentFile();
        }

        log.info("Opus CEF helper not found ({} missing); embedded web UI stays off. "
                + "Build it with native/opus-cef-helper/build.sh or set "
                + "-Dopus.webview.helper.", HELPER_APP);
        return null;
    }

    /**
     * Accepts either a {@code .app} bundle or a direct executable and returns the
     * runnable binary path, or null if neither is usable.
     */
    private static String executableIn(File candidate) {
        if (candidate == null) {
            return null;
        }
        if (candidate.isDirectory()) {
            File binary = new File(candidate, "Contents/MacOS/" + HELPER_BIN);
            return binary.isFile() && binary.canExecute() ? binary.getAbsolutePath() : null;
        }
        return candidate.isFile() && candidate.canExecute()
                ? candidate.getAbsolutePath()
                : null;
    }
}
