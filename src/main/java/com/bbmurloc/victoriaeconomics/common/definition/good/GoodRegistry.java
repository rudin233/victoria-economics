package com.bbmurloc.victoriaeconomics.common.definition.good;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class GoodRegistry {

    private final Map<String, GoodDefinition> definitions =
            new HashMap<>();

    public void register(GoodDefinition definition) {
        String id = definition.getId();

        if (definitions.containsKey(id)) {
            throw new IllegalArgumentException(
                    "Good already registered: " + id
            );
        }

        definitions.put(id, definition);
    }

    public GoodDefinition get(String id) {
        return definitions.get(id);
    }

    public boolean contains(String id) {
        return definitions.containsKey(id);
    }

    public Collection<GoodDefinition> getAll() {
        return Collections.unmodifiableCollection(
                definitions.values()
        );
    }
}