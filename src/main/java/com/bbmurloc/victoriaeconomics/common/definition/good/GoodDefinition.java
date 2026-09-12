package com.bbmurloc.victoriaeconomics.common.definition.good;

import java.util.Objects;

public record GoodDefinition(
        String id,
        GoodCategory category
) {

    public GoodDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
    }
}
