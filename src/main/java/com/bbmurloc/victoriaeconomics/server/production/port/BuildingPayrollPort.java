package com.bbmurloc.victoriaeconomics.server.production.port;

import java.util.UUID;

/**
 * Reports the payroll context's authoritative facts; production neither pays nor records wages.
 */
@FunctionalInterface
public interface BuildingPayrollPort {
    enum State {CURRENT, RECOVERY, ARREARS, UNAVAILABLE}

    State state(UUID buildingId);

    static BuildingPayrollPort unavailable() {
        return id -> State.UNAVAILABLE;
    }
}
