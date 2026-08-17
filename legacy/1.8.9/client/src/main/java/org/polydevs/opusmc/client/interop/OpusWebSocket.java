package org.polydevs.opusmc.client.interop;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.logging.log4j.Logger;

/**
 * RFC 6455 server session. Client frames are masked, server frames are not.
 * The same buffered stream that parsed the HTTP upgrade is reused so early
 * browser frames are never lost.
 */
final class OpusWebSocket implements Runnable {
    private static final int MAX_PAYLOAD = 1024 * 1024;
    private static final byte OP_TEXT = 0x1;
    private static final byte OP_CLOSE = 0x8;
    private static final byte OP_PING = 0x9;
    private static final byte OP_PONG = 0xA;

    private final Logger log;
    private final BufferedInputStream in;
    private final OutputStream out;
    private final OpusInteropServer server;
    private final Object writeLock = new Object();
    private final StringBuilder fragment = new StringBuilder();
    private volatile boolean open = true;

    OpusWebSocket(
            Logger log,
            BufferedInputStream in,
            OutputStream out,
            OpusInteropServer server) {
        this.log = log;
        this.in = in;
        this.out = out;
        this.server = server;
    }

    @Override
    public void run() {
        try {
            while (open) {
                int first = in.read();
                if (first < 0) {
                    break;
                }
                int second = in.read();
                if (second < 0) {
                    break;
                }
                boolean fin = (first & 0x80) != 0;
                int opcode = first & 0x0F;
                boolean masked = (second & 0x80) != 0;
                long length = second & 0x7F;
                if (length == 126) {
                    int high = in.read();
                    int low = in.read();
                    if (high < 0 || low < 0) {
                        break;
                    }
                    length = (high << 8) | low;
                } else if (length == 127) {
                    long extended = 0;
                    for (int index = 0; index < 8; index++) {
                        int next = in.read();
                        if (next < 0) {
                            return;
                        }
                        extended = (extended << 8) | next;
                    }
                    length = extended;
                }
                if (length < 0 || length > MAX_PAYLOAD) {
                    close(1009);
                    return;
                }
                byte[] mask = new byte[4];
                if (masked) {
                    readFully(mask);
                }
                byte[] payload = new byte[(int) length];
                readFully(payload);
                if (masked) {
                    for (int index = 0; index < payload.length; index++) {
                        payload[index] ^= mask[index & 3];
                    }
                }
                handleFrame(fin, opcode, payload);
            }
        } catch (IOException failure) {
            if (open) {
                log.debug("Opus bridge websocket closed: {}", failure.toString());
            }
        } catch (Throwable failure) {
            log.warn("Opus bridge websocket failed", failure);
        } finally {
            open = false;
            server.detach(this);
            try {
                in.close();
            } catch (IOException ignored) {
                // Closing anyway.
            }
            try {
                out.close();
            } catch (IOException ignored) {
                // Closing anyway.
            }
        }
    }

    private void handleFrame(boolean fin, int opcode, byte[] payload) {
        switch (opcode) {
            case OP_TEXT:
            case 0x0:
                String text = new String(payload, StandardCharsets.UTF_8);
                if (opcode == OP_TEXT) {
                    fragment.setLength(0);
                }
                fragment.append(text);
                if (fin) {
                    server.onMessage(fragment.toString());
                    fragment.setLength(0);
                }
                break;
            case OP_CLOSE:
                sendFrame(OP_CLOSE, payload);
                open = false;
                break;
            case OP_PING:
                sendFrame(OP_PONG, payload);
                break;
            case OP_PONG:
                break;
            default:
                close(1003);
                break;
        }
    }

    void sendText(String json) {
        sendFrame(OP_TEXT, json.getBytes(StandardCharsets.UTF_8));
    }

    void close(int code) {
        try {
            byte[] reason = new byte[] {
                    (byte) (code >> 8),
                    (byte) code,
            };
            sendFrame(OP_CLOSE, reason);
        } catch (Throwable ignored) {
            // Peer may already be gone.
        }
        open = false;
    }

    private void sendFrame(byte opcode, byte[] payload) {
        if (!open) {
            return;
        }
        try {
            synchronized (writeLock) {
                out.write(0x80 | opcode);
                int length = payload == null ? 0 : payload.length;
                if (length < 126) {
                    out.write(length);
                } else if (length <= 0xFFFF) {
                    out.write(126);
                    out.write(length >> 8);
                    out.write(length & 0xFF);
                } else {
                    out.write(127);
                    for (int shift = 56; shift >= 0; shift -= 8) {
                        out.write((length >>> shift) & 0xFF);
                    }
                }
                if (payload != null) {
                    out.write(payload);
                }
                out.flush();
            }
        } catch (IOException failure) {
            open = false;
        }
    }

    private void readFully(byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int count = in.read(target, offset, target.length - offset);
            if (count < 0) {
                throw new IOException("websocket stream ended early");
            }
            offset += count;
        }
    }
}
