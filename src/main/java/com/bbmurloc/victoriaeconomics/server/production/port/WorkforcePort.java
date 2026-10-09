package com.bbmurloc.victoriaeconomics.server.production.port;

import com.bbmurloc.victoriaeconomics.server.production.domain.EmploymentFact;

import java.util.*;

/**
 * Effective formal employments and qualification facts belong to Employment, not production.
 */
public interface WorkforcePort {
    /**
     * All effective formal employments at this building, including unqualified employees.
     * Position capacity counts these facts; only participation filters by qualification.
     * Returned facts are query snapshots, not production-owned employment state.
     */
    Collection<EmploymentFact> effectiveEmployment(UUID buildingId);

    boolean qualified(UUID employeeId, String occupationId);

    /**
     * Cancels excess job reservations, then arranges consenting transfers/dismissals.
     */
    boolean reconcile(UUID buildingId, Map<String, Integer> requiredWorkers);
}
