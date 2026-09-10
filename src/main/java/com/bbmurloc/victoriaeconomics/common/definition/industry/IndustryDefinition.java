package com.bbmurloc.victoriaeconomics.common.definition.industry;

import java.util.Objects;

public final class IndustryDefinition {

    private final String id;

    public IndustryDefinition(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    public String getId() {
        return id;
    }
}