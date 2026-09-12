package com.bbmurloc.victoriaeconomics.common.definition.industry;

import java.util.Objects;

public record IndustryDefinition(String id) {

    public IndustryDefinition {
        Objects.requireNonNull(id, "id");
    }
}
