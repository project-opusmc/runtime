package org.polydevs.opusmc.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class GameArgumentProtocolTest {
    @Test
    void readsLengthPrefixedUtf8Arguments() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeInt(2);
            writeArgument(data, "--username");
            writeArgument(data, "name with spaces");
        }

        assertArrayEquals(
                new String[] {"--username", "name with spaces"},
                GameArgumentProtocol.read(new ByteArrayInputStream(bytes.toByteArray())));
    }

    @Test
    void rejectsTrailingData() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeInt(0);
            data.writeByte(1);
        }
        assertThrows(
                IOException.class,
                () -> GameArgumentProtocol.read(new ByteArrayInputStream(bytes.toByteArray())));
    }

    private static void writeArgument(DataOutputStream data, String argument) throws IOException {
        byte[] bytes = argument.getBytes(StandardCharsets.UTF_8);
        data.writeInt(bytes.length);
        data.write(bytes);
    }
}

