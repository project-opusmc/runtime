package dev.rbw.bootstrap;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class GameArgumentProtocol {
    private static final int MAX_ARGUMENTS = 256;
    private static final int MAX_ARGUMENT_LENGTH = 1024 * 1024;

    private GameArgumentProtocol() {
    }

    static String[] read(InputStream input) throws IOException {
        DataInputStream data = new DataInputStream(input);
        int count = data.readInt();
        if (count < 0 || count > MAX_ARGUMENTS) {
            throw new IOException("Invalid game argument count: " + count);
        }

        String[] arguments = new String[count];
        for (int index = 0; index < count; index++) {
            int length = data.readInt();
            if (length < 0 || length > MAX_ARGUMENT_LENGTH) {
                throw new IOException("Invalid game argument length: " + length);
            }
            byte[] bytes = new byte[length];
            data.readFully(bytes);
            arguments[index] = new String(bytes, StandardCharsets.UTF_8);
        }
        if (data.read() != -1) {
            throw new IOException("Game argument protocol contains trailing bytes");
        }
        return arguments;
    }
}

