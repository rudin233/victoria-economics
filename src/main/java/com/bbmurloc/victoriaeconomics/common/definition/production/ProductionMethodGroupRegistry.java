package com.bbmurloc.victoriaeconomics.common.definition.production;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public final class ProductionMethodGroupRegistry {

    private final Map<String, ProductionMethodGroupDefinition> definitions =
            new HashMap<>();

    public void register(ProductionMethodGroupDefinition definition) {
        if (definitions.putIfAbsent(
                definition.getId(),
                definition
        ) != null) {
            throw new IllegalArgumentException(
                    "Production method group already registered: "
                            + definition.getId()
            );
        }
    }

    public ProductionMethodGroupDefinition get(String id) {
        return definitions.get(id);
    }

    public boolean contains(String id) {
        return definitions.containsKey(id);
    }

    public Collection<ProductionMethodGroupDefinition> getAll() {
        return definitions.values();
    }
}