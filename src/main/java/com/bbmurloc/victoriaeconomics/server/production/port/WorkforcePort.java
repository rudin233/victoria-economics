package com.bbmurloc.victoriaeconomics.server.production.port;

import com.bbmurloc.victoriaeconomics.server.workforce.employment.EmploymentRecord;

import java.util.*;

/**
 * Effective formal employments and qualification facts belong to Employment, not production.
 */
public interface WorkforcePort {
    Collection<EmploymentRecord> effectiveEmployment(UUID buildingId);

    boolean qualified(UUID employeeId, String occupationId);

    /**
     * Cancels excess job reservations, then arranges consenting transfers/dismissals.
     */
    boolean reconcile(UUID buildingId, Map<String, Integer> requiredWorkers);
}
