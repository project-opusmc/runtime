package org.polydevs.opusmc.client.interop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Performs the vanilla Minecraft status query without entering a game session.
 *
 * <p>The CEF route polls an in-memory cache while this class owns bounded
 * background sockets. That leaves the Minecraft client thread free of DNS and
 * network waits, while still returning the server's actual MOTD, player count,
 * version label and latency.</p>
 */
final class ServerStatusProbe {
    private static final int MINECRAFT_1_8_9_PROTOCOL = 47;
    private static final int DEFAULT_PORT = 25565;
    private static final int CONNECT_TIMEOUT_MS = 2500;
    private static final int STATUS_TIMEOUT_MS = 3000;
    private static final int PING_TIMEOUT_MS = 1200;
    private static final int CACHE_TTL_MS = 30000;
    private static final int MAX_PACKET_BYTES = 512 * 1024;
    private static final int MAX_STRING_BYTES = 256 * 1024;
    private static final int MAX_DISPLAY_CHARACTERS = 512;

    private final ExecutorService workers = Executors.newFixedThreadPool(4, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "opus-server-status");
            thread.setDaemon(true);
            return thread;
        }
    });
    private final ConcurrentMap<String, String> trackedAddresses =
            new ConcurrentHashMap<String, String>();
    private final ConcurrentMap<String, Snapshot> snapshots =
            new ConcurrentHashMap<String, Snapshot>();
    private final ConcurrentMap<String, Boolean> inFlight =
            new ConcurrentHashMap<String, Boolean>();

    /**
     * Replaces the tracked saved-server set and immediately refreshes it. Rows
     * follow the bridge's {name, address} contract.
     */
    void refresh(List<String[]> rows) {
        Map<String, String> next = new LinkedHashMap<String, String>();
        if (rows != null) {
            for (String[] row : rows) {
                String address = row != null && row.length > 1 ? row[1] : "";
                String normalized = normalize(address);
                if (!normalized.isEmpty()) {
                    next.put(normalized, address.trim());
                }
            }
        }

        for (String key : trackedAddresses.keySet()) {
            if (!next.containsKey(key)) {
                trackedAddresses.remove(key);
                snapshots.remove(key);
                inFlight.remove(key);
            }
        }

        long now = System.currentTimeMillis();
        for (Map.Entry<String, String> entry : next.entrySet()) {
            trackedAddresses.put(entry.getKey(), entry.getValue());
            schedule(entry.getKey(), entry.getValue(), now, true);
        }
    }

    /**
     * Returns cached results immediately. Expired entries are re-queued in the
     * background; callers never wait for DNS or a server timeout.
     */
    List<Snapshot> snapshots() {
        long now = System.currentTimeMillis();
        List<Snapshot> result = new ArrayList<Snapshot>();
        for (Map.Entry<String, String> entry : trackedAddresses.entrySet()) {
            schedule(entry.getKey(), entry.getValue(), now, false);
            Snapshot snapshot = snapshots.get(entry.getKey());
            result.add(snapshot == null
                    ? Snapshot.checking(entry.getValue(), now)
                    : snapshot);
        }
        Collections.sort(result, new Comparator<Snapshot>() {
            @Override
            public int compare(Snapshot left, Snapshot right) {
                return left.address.compareToIgnoreCase(right.address);
            }
        });
        return result;
    }

    void stop() {
        workers.shutdownNow();
        trackedAddresses.clear();
        snapshots.clear();
        inFlight.clear();
    }

    private void schedule(final String key, final String address, long now, boolean force) {
        Snapshot current = snapshots.get(key);
        if (!force && current != null
                && (current.isChecking() || now - current.updatedAtMs < CACHE_TTL_MS)) {
            return;
        }
        if (inFlight.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }

        snapshots.put(key, Snapshot.checking(address, now));
        try {
            workers.execute(new Runnable() {
                @Override
                public void run() {
                    Snapshot result = query(address);
                    if (address.equals(trackedAddresses.get(key))) {
                        snapshots.put(key, result);
                    }
                    inFlight.remove(key);
                }
            });
        } catch (RuntimeException failure) {
            inFlight.remove(key);
            snapshots.put(key, Snapshot.offline(address, System.currentTimeMillis()));
        }
    }

    private static Snapshot query(String address) {
        long now = System.currentTimeMillis();
        try {
            Endpoint endpoint = Endpoint.parse(address);
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS);
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(STATUS_TIMEOUT_MS);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());

                sendHandshake(output, endpoint);
                sendStatusRequest(output);
                StatusPayload payload = readStatusResponse(input);
                Long pingMs = queryLatency(socket, input, output);
                return Snapshot.online(
                        address,
                        payload.motd,
                        payload.motdRuns,
                        payload.favicon,
                        payload.onlinePlayers,
                        payload.maxPlayers,
                        payload.version,
                        pingMs == null ? -1L : pingMs.longValue(),
                        System.currentTimeMillis());
            } finally {
                socket.close();
            }
        } catch (Exception ignored) {
            return Snapshot.offline(address, now);
        }
    }

    private static void sendHandshake(DataOutputStream output, Endpoint endpoint) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(buffer);
        writeVarInt(body, 0);
        writeVarInt(body, MINECRAFT_1_8_9_PROTOCOL);
        writeString(body, endpoint.host);
        body.writeShort(endpoint.port);
        writeVarInt(body, 1);
        writePacket(output, buffer.toByteArray());
    }

    private static void sendStatusRequest(DataOutputStream output) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(buffer);
        writeVarInt(body, 0);
        writePacket(output, buffer.toByteArray());
    }

    private static StatusPayload readStatusResponse(DataInputStream input) throws IOException {
        DataInputStream packet = packetStream(input);
        if (readVarInt(packet) != 0) {
            throw new IOException("Unexpected server status packet");
        }
        String body = readString(packet);
        JsonElement parsed = new JsonParser().parse(body);
        if (!parsed.isJsonObject()) {
            throw new IOException("Invalid server status JSON");
        }
        JsonObject object = parsed.getAsJsonObject();
        com.google.gson.JsonArray motdRuns = ServerDescription.runs(object.get("description"));
        String motd = ServerDescription.plain(motdRuns);
        JsonObject players = object.has("players") && object.get("players").isJsonObject()
                ? object.getAsJsonObject("players") : null;
        JsonObject version = object.has("version") && object.get("version").isJsonObject()
                ? object.getAsJsonObject("version") : null;
        return new StatusPayload(
                motd,
                motdRuns.toString(),
                favicon(object),
                number(players, "online"),
                number(players, "max"),
                string(version, "name"));
    }

    private static Long queryLatency(
            Socket socket, DataInputStream input, DataOutputStream output) {
        try {
            socket.setSoTimeout(PING_TIMEOUT_MS);
            long started = System.nanoTime();
            socketPing(output);
            DataInputStream packet = packetStream(input);
            if (readVarInt(packet) != 1) {
                return null;
            }
            packet.readLong();
            return Long.valueOf(Math.max(0L,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
        } catch (IOException ignored) {
            return null;
        }
    }

    private static void socketPing(DataOutputStream output) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(buffer);
        writeVarInt(body, 1);
        body.writeLong(System.currentTimeMillis());
        writePacket(output, buffer.toByteArray());
    }

    private static DataInputStream packetStream(DataInputStream input) throws IOException {
        int length = readVarInt(input);
        if (length <= 0 || length > MAX_PACKET_BYTES) {
            throw new IOException("Invalid server status packet length");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }

    private static void writePacket(DataOutputStream output, byte[] body) throws IOException {
        if (body.length <= 0 || body.length > MAX_PACKET_BYTES) {
            throw new IOException("Invalid outbound server status packet");
        }
        writeVarInt(output, body.length);
        output.write(body);
        output.flush();
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("Server address is too long");
        }
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = readVarInt(input);
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Invalid server status string length");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeVarInt(DataOutputStream output, int value) throws IOException {
        do {
            int part = value & 0x7F;
            value >>>= 7;
            if (value != 0) {
                part |= 0x80;
            }
            output.writeByte(part);
        } while (value != 0);
    }

    private static int readVarInt(DataInputStream input) throws IOException {
        int result = 0;
        int position = 0;
        while (true) {
            int part = input.readUnsignedByte();
            result |= (part & 0x7F) << (position++ * 7);
            if (position > 5) {
                throw new IOException("Server status varint is too large");
            }
            if ((part & 0x80) == 0) {
                return result;
            }
        }
    }

    private static String displayText(JsonElement element) {
        return ServerDescription.plain(ServerDescription.runs(element));
    }

    private static String favicon(JsonObject object) {
        try {
            if (!object.has("favicon") || !object.get("favicon").isJsonPrimitive()) return "";
            String data = object.get("favicon").getAsString();
            String prefix = "data:image/png;base64,";
            if (!data.startsWith(prefix) || data.length()>100000) return "";
            byte[] png = java.util.Base64.getDecoder().decode(data.substring(prefix.length()));
            if (png.length<24 || png[0]!=(byte)137 || png[1]!='P' || png[2]!='N' || png[3]!='G') return "";
            java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(png);
            if (header.getInt(16)!=64 || header.getInt(20)!=64) return "";
            return data;
        } catch (RuntimeException invalid) { return ""; }
    }

    private static int number(JsonObject object, String key) {
        if (object == null || !object.has(key)) {
            return -1;
        }
        try {
            return Math.max(-1, object.get(key).getAsInt());
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key)) {
            return "";
        }
        return displayText(object.get(key));
    }

    private static String normalize(String address) {
        return address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    }

    static final class Snapshot {
        final String address;
        final String state;
        final String motd;
        final String motdRuns;
        final String favicon;
        final int onlinePlayers;
        final int maxPlayers;
        final String version;
        final long pingMs;
        final long updatedAtMs;

        private Snapshot(
                String address,
                String state,
                String motd,
                String motdRuns,
                String favicon,
                int onlinePlayers,
                int maxPlayers,
                String version,
                long pingMs,
                long updatedAtMs) {
            this.address = address;
            this.state = state;
            this.motd = motd;
            this.motdRuns = motdRuns;
            this.favicon = favicon;
            this.onlinePlayers = onlinePlayers;
            this.maxPlayers = maxPlayers;
            this.version = version;
            this.pingMs = pingMs;
            this.updatedAtMs = updatedAtMs;
        }

        static Snapshot checking(String address, long now) {
            return new Snapshot(address, "checking", "", "[]", "", -1, -1, "", -1L, now);
        }

        static Snapshot online(
                String address,
                String motd,
                String motdRuns,
                String favicon,
                int onlinePlayers,
                int maxPlayers,
                String version,
                long pingMs,
                long now) {
            return new Snapshot(address, "online", motd, motdRuns, favicon, onlinePlayers, maxPlayers,
                    version, pingMs, now);
        }

        static Snapshot offline(String address, long now) {
            return new Snapshot(address, "offline", "", "[]", "", -1, -1, "", -1L, now);
        }

        boolean isChecking() {
            return "checking".equals(state);
        }
    }

    private static final class StatusPayload {
        final String motd;
        final String motdRuns;
        final String favicon;
        final int onlinePlayers;
        final int maxPlayers;
        final String version;

        StatusPayload(String motd, String motdRuns, String favicon, int onlinePlayers, int maxPlayers, String version) {
            this.motd = motd;
            this.motdRuns = motdRuns;
            this.favicon = favicon;
            this.onlinePlayers = onlinePlayers;
            this.maxPlayers = maxPlayers;
            this.version = version;
        }
    }

    private static final class Endpoint {
        final String host;
        final int port;

        Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        static Endpoint parse(String source) {
            String address = source == null ? "" : source.trim();
            if (address.isEmpty() || address.length() > 255) {
                throw new IllegalArgumentException("Invalid server address");
            }

            String host = address;
            int port = DEFAULT_PORT;
            if (address.startsWith("[")) {
                int close = address.indexOf(']');
                if (close <= 1) {
                    throw new IllegalArgumentException("Invalid IPv6 server address");
                }
                host = address.substring(1, close);
                String suffix = address.substring(close + 1);
                if (!suffix.isEmpty()) {
                    if (!suffix.startsWith(":")) {
                        throw new IllegalArgumentException("Invalid server address suffix");
                    }
                    port = parsePort(suffix.substring(1));
                }
            } else {
                int firstColon = address.indexOf(':');
                int lastColon = address.lastIndexOf(':');
                if (firstColon >= 0 && firstColon == lastColon) {
                    host = address.substring(0, firstColon);
                    port = parsePort(address.substring(firstColon + 1));
                }
            }

            if (host.trim().isEmpty()) {
                throw new IllegalArgumentException("Server host is empty");
            }
            return new Endpoint(host, port);
        }

        private static int parsePort(String value) {
            try {
                int port = Integer.parseInt(value);
                if (port <= 0 || port > 65535) {
                    throw new IllegalArgumentException("Server port is invalid");
                }
                return port;
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("Server port is invalid", failure);
            }
        }
    }
}
