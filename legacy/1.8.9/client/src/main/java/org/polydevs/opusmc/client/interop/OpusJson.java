package org.polydevs.opusmc.client.interop;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal JSON helpers for the fixed Opus bridge protocol. Minecraft 1.8.9
 * ships Gson at runtime, but the compile-only ForgeGradle lane does not expose
 * it reliably, so the bridge stays dependency-free on purpose.
 */
final class OpusJson {
    private static final Pattern STRING_VALUE = Pattern.compile(
            "\"([A-Za-z0-9_]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private OpusJson() {
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        out.append(String.format("\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                    break;
            }
        }
        return out.toString();
    }

    /** Builds {"a":"v","b":"v2"} from alternating key/value arguments. */
    static String object(String... keyValues) {
        StringBuilder out = new StringBuilder();
        out.append('{');
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            if (index > 0) {
                out.append(',');
            }
            out.append('"').append(escape(keyValues[index])).append("\":\"")
                    .append(escape(keyValues[index + 1])).append('"');
        }
        out.append('}');
        return out.toString();
    }

    static String array(List<String> values) {
        StringBuilder out = new StringBuilder();
        out.append('[');
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            out.append('"').append(escape(values.get(index))).append('"');
        }
        out.append(']');
        return out.toString();
    }

    static String extractString(String json, String key) {
        if (json == null || key == null) {
            return null;
        }
        Matcher matcher = STRING_VALUE.matcher(json);
        while (matcher.find()) {
            if (key.equals(matcher.group(1))) {
                return unescape(matcher.group(2));
            }
        }
        return null;
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '\\' || index + 1 >= value.length()) {
                out.append(character);
                continue;
            }
            char next = value.charAt(++index);
            switch (next) {
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'u':
                    if (index + 4 < value.length()) {
                        try {
                            out.append((char) Integer.parseInt(
                                    value.substring(index + 1, index + 5), 16));
                            index += 4;
                        } catch (NumberFormatException ignored) {
                            out.append('u');
                        }
                    } else {
                        out.append('u');
                    }
                    break;
                default:
                    out.append(next);
                    break;
            }
        }
        return out.toString();
    }
}
