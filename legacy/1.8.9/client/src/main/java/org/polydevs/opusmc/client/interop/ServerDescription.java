package org.polydevs.opusmc.client.interop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Bounded display-only projection of Minecraft chat components. No server actions survive. */
final class ServerDescription {
    private static final String[] NAMES = {"black","dark_blue","dark_green","dark_aqua","dark_red","dark_purple","gold","gray",
            "dark_gray","blue","green","aqua","red","light_purple","yellow","white"};
    private static final String[] COLORS = {"#000000","#0000aa","#00aa00","#00aaaa","#aa0000","#aa00aa","#ffaa00","#aaaaaa",
            "#555555","#5555ff","#55ff55","#55ffff","#ff5555","#ff55ff","#ffff55","#ffffff"};
    private static final String[] FLAGS = {"bold", "italic", "underlined", "strikethrough"};
    private final JsonArray runs = new JsonArray();
    private int remaining = 2048;

    static JsonArray runs(JsonElement component) {
        ServerDescription result = new ServerDescription();
        result.append(component, new JsonObject(), 0);
        return result.runs;
    }

    private void append(JsonElement value, JsonObject inherited, int depth) {
        if (value == null || value.isJsonNull() || depth > 32 || remaining <= 0 || runs.size() >= 128) return;
        if (value.isJsonPrimitive()) {
            String text;
            try { text = value.getAsString(); } catch (RuntimeException invalid) { return; }
            // A few legacy proxies nest a serialized component inside description.text.
            if (text.length() <= 32768 && (text.startsWith("{\"") || text.startsWith("[{"))) {
                try {
                    JsonElement nested = new JsonParser().parse(text);
                    if (nested.isJsonObject() || nested.isJsonArray()) { append(nested, inherited, depth+1); return; }
                } catch (RuntimeException invalid) { /* ordinary text */ }
            }
            StringBuilder clean = new StringBuilder();
            for (int i=0;i<text.length() && remaining>0;i++) {
                char character = text.charAt(i);
                if (!Character.isISOControl(character) || character=='\n' || character=='\t') {
                    clean.append(character); remaining--;
                }
            }
            if (clean.length()==0) return;
            JsonObject run = copy(inherited); run.addProperty("text", clean.toString()); runs.add(run);
            return;
        }
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) append(item,inherited,depth+1);
            return;
        }
        JsonObject component = value.getAsJsonObject(), style = copy(inherited);
        if (component.has("color") && component.get("color").isJsonPrimitive()) {
            String name = component.get("color").getAsString();
            if ("reset".equals(name)) style = new JsonObject();
            for (int i=0;i<NAMES.length;i++) if (NAMES[i].equals(name)) style.addProperty("color",COLORS[i]);
            if (name.matches("#[a-fA-F0-9]{6}")) style.addProperty("color",name);
        }
        for (String flag : FLAGS) if (component.has(flag) && component.get(flag).isJsonPrimitive()) {
            try { style.addProperty(flag,component.get(flag).getAsBoolean()); } catch (RuntimeException invalid) { }
        }
        if (component.has("text")) append(component.get("text"),style,depth+1);
        if (component.has("extra")) append(component.get("extra"),style,depth+1);
    }

    private static JsonObject copy(JsonObject source) {
        JsonObject result = new JsonObject();
        for (java.util.Map.Entry<String,JsonElement> entry : source.entrySet()) result.add(entry.getKey(),entry.getValue());
        return result;
    }

    static String plain(JsonArray runs) {
        StringBuilder out = new StringBuilder();
        for (JsonElement run : runs) out.append(run.getAsJsonObject().get("text").getAsString());
        return out.toString().replaceAll("§.", "").replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n").trim();
    }
}
