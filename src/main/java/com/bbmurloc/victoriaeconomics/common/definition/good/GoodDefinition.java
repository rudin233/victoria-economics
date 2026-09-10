package com.bbmurloc.victoriaeconomics.common.definition.good;

import java.util.Objects;

public final class GoodDefinition {

    private final String id;
    private final GoodCategory category;

    public GoodDefinition(
            String id,
            GoodCategory category
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.category = Objects.requireNonNull(category, "category");
    }

    public String getId() {
        return id;
    }

    public GoodCategory getCategory() {
        return category;
    }

    @Override
    public String toString() {
        return "GoodDefinition{" +
                "id='" + id + '\'' +
                ", category=" + category +
                '}';
    }
}