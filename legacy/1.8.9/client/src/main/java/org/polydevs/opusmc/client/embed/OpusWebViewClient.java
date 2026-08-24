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
 * out of the mapping with zero copies. The mapping is double-buffered by the
 * helper, so the game never uploads a half-written frame.</p>
 *
 * <p>Everything here is fail-closed: an error marks the embedded surface as
 * failed so the Minecraft host can show its minimal lifecycle shell. There is
 * deliberately no second product UI fallback.</p>
 */
public final class OpusWebViewClient {
    /** Bytes reserved per frame slot: enough for one 4K BGRA frame. */
    private static final long SLOT_BYTES = 3840L * 2160L * 4L;
    private static final int MAX_FRAME_WIDTH = 3840;
    private static final int MAX_FRAME_HEIGHT = 2160;
    /** Must match kSlots in native/opus-cef-helper/src/opus_main.mm. */
    private static final int SLOTS = 2;
    private static final long SHM_BYTES = SLOT_BYTES * SLOTS;

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
    private final Object writeLock = new Object();

    private volatile boolean running;
    private volatile boolean failed;
    private volatile boolean ready;
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
    private int width = 1280;
    private int height = 720;
    private float scale = 1.0F;
    private String url = "about:blank";
    private volatile long generation = 1L;

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
                    "--shm-size", Long.toString(SHM_BYTES),
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
     * Creates and maps the shared frame buffer. The file is sparse: the length is
     * reserved up front so the mapping never has to be renegotiated on resize,
     * but only the pages actually painted consume disk.
     */
    private void openSharedBuffer() throws IOException {
        shmFile = File.createTempFile("opus-cef-", ".shm");
        shmFile.deleteOnExit();
        shmHandle = new RandomAccessFile(shmFile, "rw");
        shmHandle.setLength(SHM_BYTES);
        shm = shmHandle.getChannel().map(FileChannel.MapMode.READ_ONLY, 0L, SHM_BYTES);
        shm.order(ByteOrder.nativeOrder());
    }

    /** Returns the most recent frame and clears it, or null if none is new. */
    public Frame consumeFrame() {
        return latest.getAndSet(null);
    }

    /** Releases a published shared-memory slot after upload or rejection. */
    public void release(Frame frame) {
        if (frame != null && frame.sequence > 0L
                && frame.slot >= 0 && frame.slot < SLOTS) {
            sendLine("RELEASE " + frame.sequence + " " + frame.slot);
        }
    }

    /** True only for a frame from the current route/viewport generation. */
    public boolean isCurrent(Frame frame, int logicalWidth, int logicalHeight, float deviceScale) {
        if (frame == null || frame.generation != generation) {
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
    public void resize(int width, int height, float scale) {
        if (!running || failed) {
            return;
        }
        float nextScale = clampScale(scale);
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        if (w == this.width && h == this.height && nextScale == this.scale) {
            return;
        }
        this.width = w;
        this.height = h;
        this.scale = nextScale;
        advanceGeneration();
        sendView();
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
    public void reload() {
        if (!running || failed) {
            return;
        }
        advanceGeneration();
        sendLine("RELOAD " + generation);
    }

    private void sendView() {
        sendLine("VIEW " + generation + " " + width + " " + height + " " + scale + " " + url);
    }

    private void advanceGeneration() {
        if (generation >= Long.MAX_VALUE - 1L) {
            log.error("Opus CEF navigation generation exhausted; refusing to reuse frame ownership ids");
            fail();
            return;
        }
        generation++;
        Frame stale = latest.getAndSet(null);
        release(stale);
    }

    // ---- input ------------------------------------------------------------
    // The helper speaks plain tokens rather than encoded JSON: these events are
    // fed straight into CEF's SendMouseEvent/SendKeyEvent, so there is no DOM
    // synthesis step that would need a structured payload. Coordinates are in
    // the same logical units as the viewport passed to start()/resize().

    public void sendMouseMove(int x, int y) {
        sendLine("INPUT move " + x + " " + y);
    }

    /** Tells the helper that the synthetic pointer left the browser surface. */
    public void sendMouseLeave(int x, int y) {
        sendLine("INPUT leave " + x + " " + y);
    }

    /** button: 0 left, 1 right, 2 middle (LWJGL's own numbering). */
    public void sendMouseButton(int x, int y, int button, boolean down) {
        sendLine("INPUT " + (down ? "down " : "up ") + x + " " + y + " " + button);
    }

    /** deltaY follows the wheel convention: positive scrolls the view up. */
    public void sendWheel(int x, int y, int deltaY) {
        sendLine("INPUT wheel " + x + " " + y + " " + deltaY);
    }

    /** Text input: a Unicode code point that should be inserted. */
    public void sendChar(int codePoint) {
        sendLine("INPUT char " + codePoint);
    }

    /** Non-text keys, as a Windows virtual-key code (what CEF expects). */
    public void sendKey(int windowsVirtualKey, boolean down) {
        sendLine("INPUT key " + windowsVirtualKey + " " + (down ? 1 : 0));
    }

    public synchronized void stop() {
        if (stopping) {
            return;
        }
        stopping = true;
        running = false;
        ready = false;
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
            if (sequence <= 0L || frameGeneration <= 0L || slot < 0 || slot >= SLOTS
                    || w <= 0 || h <= 0 || w > MAX_FRAME_WIDTH || h > MAX_FRAME_HEIGHT
                    || offset < 0L) {
                releaseHeaderSlot(sequence, slot);
                warnOnce("Opus CEF helper sent an out-of-range frame: " + header);
                return;
            }
            long bytes = (long) w * h * 4L;
            long expectedOffset = (long) slot * SLOT_BYTES;
            if (offset != expectedOffset || offset > SHM_BYTES
                    || bytes <= 0L || bytes > SHM_BYTES - offset) {
                releaseHeaderSlot(sequence, slot);
                warnOnce("Opus CEF frame " + w + "x" + h + " at " + offset
                        + " does not fit the shared buffer; dropping");
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
            if (frameGeneration != generation) {
                release(frame);
                return;
            }
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
                    Thread.sleep(50L);
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
