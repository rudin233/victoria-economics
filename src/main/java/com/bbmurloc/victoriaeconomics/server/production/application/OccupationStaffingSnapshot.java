package com.bbmurloc.victoriaeconomics.server.production.application;

import java.util.*;

/** Missing supply is explicitly unconfigured. Missing Pending is distinct from a target of zero. */
public record OccupationStaffingSnapshot(String occupationId, int positionCapacity, int employed,
                                        OptionalInt pending, OptionalLong totalSupply, long occupied,
                                        OptionalLong available) {
    public OccupationStaffingSnapshot {
        Objects.requireNonNull(occupationId); Objects.requireNonNull(pending);
        Objects.requireNonNull(totalSupply); Objects.requireNonNull(available);
        if (positionCapacity < 0 || employed < 0 || occupied < 0 || (pending.isPresent() && pending.getAsInt() < 0)
                || (available.isPresent() && available.getAsLong() < 0))
            throw new IllegalArgumentException("Invalid staffing query facts");
    }
}
