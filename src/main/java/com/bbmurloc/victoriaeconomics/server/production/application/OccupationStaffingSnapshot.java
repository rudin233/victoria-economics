package com.bbmurloc.victoriaeconomics.server.production.application;

import java.util.Objects;
import java.util.OptionalLong;

public record OccupationStaffingSnapshot(String occupationId, int requiredWorkers, int batchOccupied,
                                        OptionalLong totalSupply, long regionalOccupied, OptionalLong available) {
    public OccupationStaffingSnapshot {
        Objects.requireNonNull(occupationId);
        Objects.requireNonNull(totalSupply);
        Objects.requireNonNull(available);
        if (requiredWorkers < 0 || batchOccupied < 0 || regionalOccupied < batchOccupied
                || (available.isPresent() && available.getAsLong() < 0))
            throw new IllegalArgumentException("Invalid batch labor query facts");
    }
}
