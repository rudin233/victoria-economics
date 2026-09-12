package com.bbmurloc.victoriaeconomics.common.definition.building;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class BuildingTypeRegistry {

    private final Map<String, BuildingTypeDefinition> definitions =
            new HashMap<>();

    public void register(BuildingTypeDefinition definition) {
        String id = definition.id();

        if (definitions.containsKey(id)) {
            throw new IllegalArgumentException(
                    "Building type already registered: " + id
            );
        }

        definitions.put(id, definition);
    }

    public BuildingTypeDefinition get(String id) {
        return definitions.get(id);
    }

    public boolean contains(String id) {
        return definitions.containsKey(id);
    }

    public Collection<BuildingTypeDefinition> getAll() {
        return Collections.unmodifiableCollection(
                definitions.values()
        );
    }



}
