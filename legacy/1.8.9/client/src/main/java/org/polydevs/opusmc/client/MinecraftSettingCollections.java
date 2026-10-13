package org.polydevs.opusmc.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.Language;
import net.minecraft.client.resources.ResourcePackRepository;

/** All reads and writes are called on the Minecraft client thread. */
final class MinecraftSettingCollections {
    static List<String[]> read(String kind) {
        Minecraft mc = Minecraft.getMinecraft();
        List<String[]> rows = new ArrayList<String[]>();
        if ("language".equals(kind)) {
            String current = mc.getLanguageManager().getCurrentLanguage().getLanguageCode();
            for (Language language : mc.getLanguageManager().getLanguages()) {
                rows.add(row(language.getLanguageCode(), language.toString(), language.getLanguageCode(), language.getLanguageCode().equals(current), -1, true));
            }
        } else if ("packs".equals(kind)) {
            ResourcePackRepository repo = mc.getResourcePackRepository();
            repo.updateRepositoryEntriesAll();
            List<ResourcePackRepository.Entry> active = repo.getRepositoryEntries();
            for (ResourcePackRepository.Entry entry : repo.getRepositoryEntriesAll()) {
                int index = active.indexOf(entry);
                rows.add(row(entry.getResourcePackName(), entry.getResourcePackName(), entry.getTexturePackDescription().replaceAll("\\u00a7.", ""), index >= 0, index < 0 ? -1 : active.size() - 1 - index, entry.func_183027_f() == 1));
            }
        } else if ("shaders".equals(kind)) {
            return OptifineShaderSettings.packs();
        } else throw new IllegalArgumentException("Unknown settings collection");
        return rows;
    }

    static void write(String kind, List<String> ids) {
        Minecraft mc = Minecraft.getMinecraft();
        if ("language".equals(kind)) {
            if (ids.size() != 1) throw new IllegalArgumentException("Choose one language");
            Language found = null;
            for (Language language : mc.getLanguageManager().getLanguages()) if (ids.get(0).equals(language.getLanguageCode())) found = language;
            if (found == null) throw new IllegalArgumentException("Language is unavailable");
            mc.getLanguageManager().setCurrentLanguage(found);
            mc.gameSettings.language = found.getLanguageCode();
            mc.refreshResources();
            mc.fontRendererObj.setUnicodeFlag(mc.getLanguageManager().isCurrentLocaleUnicode() || mc.gameSettings.forceUnicodeFont);
            mc.fontRendererObj.setBidiFlag(mc.getLanguageManager().isCurrentLanguageBidirectional());
        } else if ("packs".equals(kind)) {
            ResourcePackRepository repo = mc.getResourcePackRepository();
            repo.updateRepositoryEntriesAll();
            List<ResourcePackRepository.Entry> entries = new ArrayList<ResourcePackRepository.Entry>();
            for (String id : ids) {
                ResourcePackRepository.Entry found = null;
                for (ResourcePackRepository.Entry entry : repo.getRepositoryEntriesAll()) if (id.equals(entry.getResourcePackName())) found = entry;
                if (found == null || entries.contains(found)) throw new IllegalArgumentException("Resource pack is unavailable or duplicated");
                entries.add(found);
            }
            // UI displays highest priority first; vanilla stores it last.
            Collections.reverse(entries);
            repo.setRepositories(entries);
            mc.gameSettings.resourcePacks.clear();
            mc.gameSettings.incompatibleResourcePacks.clear();
            for (ResourcePackRepository.Entry entry : entries) {
                mc.gameSettings.resourcePacks.add(entry.getResourcePackName());
                if (entry.func_183027_f() != 1) mc.gameSettings.incompatibleResourcePacks.add(entry.getResourcePackName());
            }
            mc.refreshResources();
        } else if ("shaders".equals(kind)) {
            if (ids.size() != 1) throw new IllegalArgumentException("Choose one shader");
            OptifineShaderSettings.selectPack(ids.get(0));
        } else throw new IllegalArgumentException("Unknown settings collection");
        mc.gameSettings.saveOptions();
    }

    static String[] row(String id, String label, String detail, boolean selected, int order, boolean compatible) {
        return new String[] {id, label, detail, String.valueOf(selected), String.valueOf(order), String.valueOf(compatible)};
    }
}
