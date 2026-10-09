package com.bbmurloc.victoriaeconomics.server.production.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Read-only production view of an effective formal employment, supplied by Employment.
 * Qualification is queried separately for this occupation; this fact also counts toward
 * position capacity even if the employee is not qualified to participate.
 */
public record EmploymentFact(UUID employeeId, UUID buildingId, String occupationId) {
    public EmploymentFact {
        Objects.requireNonNull(employeeId, "employeeId cannot be null");
        Objects.requireNonNull(buildingId, "buildingId cannot be null");
        Objects.requireNonNull(occupationId, "occupationId cannot be null");
    }
}
