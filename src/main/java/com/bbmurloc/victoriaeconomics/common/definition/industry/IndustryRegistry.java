package com.bbmurloc.victoriaeconomics.common.definition.industry;

import com.bbmurloc.victoriaeconomics.common.definition.industry.IndustryDefinition;

import java.util.HashMap;
import java.util.Map;

public final class IndustryRegistry {

    private final Map<String, IndustryDefinition> definitions =
            new HashMap<>();

    public void register(IndustryDefinition definition) {
        if (definitions.putIfAbsent(
                definition.id(),
                definition
        ) != null) {
            throw new IllegalArgumentException(
                    "Industry already registered: "
                            + definition.id()
            );
        }
    }

    public IndustryDefinition get(String id) {
        return definitions.get(id);
    }

    public boolean contains(String id) {
        return definitions.containsKey(id);
    }

}
