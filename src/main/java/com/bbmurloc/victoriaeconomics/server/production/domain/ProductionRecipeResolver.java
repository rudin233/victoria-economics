package com.bbmurloc.victoriaeconomics.server.production.domain;

import com.bbmurloc.victoriaeconomics.common.definition.building.BuildingTypeRegistry;
import com.bbmurloc.victoriaeconomics.common.definition.production.*;
import java.util.*;

public final class ProductionRecipeResolver {
    private final Map<String, ProductionMethodRules> rules;

    public ProductionRecipeResolver(BuildingTypeRegistry types, ProductionMethodGroupRegistry groups, ProductionMethodRegistry methods) {
        Map<String, ProductionMethodRules> copy = new HashMap<>();
        types.getAll().forEach(type -> copy.put(type.id(), new ProductionMethodRules(type, groups.getAll(), methods.getAll())));
        rules = Map.copyOf(copy);
    }

    public ProductionMethodRules rulesFor(String type) {
        ProductionMethodRules value = rules.get(type);
        if (value == null) throw new IllegalArgumentException("Unknown building type: " + type);
        return value;
    }

}
