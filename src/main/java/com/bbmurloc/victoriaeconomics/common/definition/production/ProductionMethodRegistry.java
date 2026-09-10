package com.bbmurloc.victoriaeconomics.common.definition.production;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public final class ProductionMethodRegistry {

    private final Map<String, ProductionMethodDefinition> definitions =
            new HashMap<>();

    public void register(ProductionMethodDefinition definition) {
        if (definitions.putIfAbsent(
                definition.getId(),
                definition
        ) != null) {
            throw new IllegalArgumentException(
                    "Production method already registered: "
                            + definition.getId()
            );
        }
    }

    public ProductionMethodDefinition get(String id) {
        return definitions.get(id);
    }

    public boolean contains(String id) {
        return definitions.containsKey(id);
    }

    public Collection<ProductionMethodDefinition> getAll() {
        return definitions.values();
    }
}