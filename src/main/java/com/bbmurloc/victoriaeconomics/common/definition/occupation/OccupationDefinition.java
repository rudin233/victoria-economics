package com.bbmurloc.victoriaeconomics.common.definition.occupation;

public record OccupationDefinition(
        String id,
        double wageMultiplier
) {
    public OccupationDefinition {
        java.util.Objects.requireNonNull(
                id,
                "id cannot be null"
        );

        if (wageMultiplier <= 0.0) {
            throw new IllegalArgumentException(
                    "wageMultiplier must be positive"
            );
        }
    }
}