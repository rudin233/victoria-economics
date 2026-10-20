package com.bbmurloc.victoriaeconomics.server.workforce.domain;

import com.bbmurloc.victoriaeconomics.server.region.domain.RegionId;
import java.util.Objects;

/** Total supply only. Occupied/available are always derived from persisted Staffing. */
public record RegionalLaborSupply(RegionId regionId, String occupationId, long totalSupply, long revision) {
    public RegionalLaborSupply {
        Objects.requireNonNull(regionId);
        if (occupationId == null || occupationId.isBlank() || totalSupply < 0 || revision < 0)
            throw new IllegalArgumentException("Invalid regional supply");
    }
    public RegionalLaborSupply withTotal(long total) {
        return new RegionalLaborSupply(regionId, occupationId, total, Math.incrementExact(revision));
    }
}
