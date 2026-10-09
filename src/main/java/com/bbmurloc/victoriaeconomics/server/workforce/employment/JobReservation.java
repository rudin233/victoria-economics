package com.bbmurloc.victoriaeconomics.server.workforce.employment;

import java.util.*;

public record JobReservation(UUID id, UUID employeeId, UUID buildingId, String occupationId) {
    public JobReservation {
        Objects.requireNonNull(id);
        Objects.requireNonNull(employeeId);
        Objects.requireNonNull(buildingId);
        Objects.requireNonNull(occupationId);
    }
}
