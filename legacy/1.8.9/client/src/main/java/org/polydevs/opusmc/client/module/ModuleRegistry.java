package org.polydevs.opusmc.client.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The single source of truth for real modules visible to any Opus UI route. */
public final class ModuleRegistry {
    private final Map<String, ClientModule> byId = new LinkedHashMap<String, ClientModule>();

    public void register(ClientModule module) {
        if (module == null || module.id() == null || module.id().trim().isEmpty()) {
            throw new IllegalArgumentException("module with an id is required");
        }
        if (byId.put(module.id(), module) != null) {
            throw new IllegalArgumentException("duplicate module id: " + module.id());
        }
    }

    public ClientModule byId(String moduleId) {
        return byId.get(moduleId);
    }

    public List<ClientModule> all() {
        return Collections.unmodifiableList(new ArrayList<ClientModule>(byId.values()));
    }
}
