package com.bbmurloc.victoriaeconomics.common.definition.occupation;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public final class OccupationRegistry {

    private final Map<String, OccupationDefinition> definitions =
            new HashMap<>();

    public void register(
            OccupationDefinition definition
    ) {
        if (definitions.containsKey(definition.id())) {
            throw new IllegalArgumentException(
                    "Duplicate occupation definition: "
                            + definition.id()
            );
        }

        definitions.put(
                definition.id(),
                definition
        );
    }

    public OccupationDefinition get(String id) {
        return definitions.get(id);
    }

    public boolean contains(String id) {
        return definitions.containsKey(id);
    }

    public Collection<OccupationDefinition> getAll() {
        return definitions.values();
    }
}