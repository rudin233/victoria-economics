package com.bbmurloc.victoriaeconomics.server.workforce.employment;

import java.util.Map;
import java.util.UUID;

/** Production facts needed by Employment; failed/unknown reads must propagate, never mean unprotected. */
public interface ProductionEmploymentPort {
    Map<String, Integer> positionCapacity(UUID buildingId);
    boolean participationProtected(UUID buildingId);
    boolean employeeProtected(UUID buildingId, UUID employeeId);
}
